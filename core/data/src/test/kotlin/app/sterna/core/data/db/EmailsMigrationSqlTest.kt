package app.sterna.core.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the 13→14 rebuilds ([MIGRATION_13_14]) against a real SQLite engine to confirm they
 * (a) preserve every row of `emails`, `email_bodies` and `snoozed` and (b) widen each primary
 * key to `(accountId, <email id>)` so two accounts of one login (issue #31) can each hold a
 * message that shares a JMAP email id.
 */
class EmailsMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    /** The v13 tables: primary key on the email id alone (pre-#31). */
    private val v13EmailsCreate =
        "CREATE TABLE `emails` (" +
            "`id` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL, `mailboxId` TEXT NOT NULL, " +
            "`threadId` TEXT, `subject` TEXT, `preview` TEXT, `receivedAt` TEXT, `fromName` TEXT, " +
            "`fromEmail` TEXT, `seen` INTEGER NOT NULL, `flagged` INTEGER NOT NULL, " +
            "`hasAttachment` INTEGER NOT NULL, `sortKey` INTEGER NOT NULL)"
    private val v13BodiesCreate =
        "CREATE TABLE `email_bodies` (" +
            "`id` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL, `bodyJson` TEXT NOT NULL, " +
            "`inlineImagesJson` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL)"
    private val v13SnoozedCreate =
        "CREATE TABLE `snoozed` (" +
            "`emailId` TEXT NOT NULL PRIMARY KEY, `accountId` TEXT NOT NULL, `until` INTEGER NOT NULL)"

    private fun seedV13() {
        db.createStatement().use { st ->
            st.executeUpdate(v13EmailsCreate)
            st.executeUpdate(v13BodiesCreate)
            st.executeUpdate("CREATE INDEX `index_email_bodies_accountId` ON `email_bodies` (`accountId`)")
            st.executeUpdate(v13SnoozedCreate)
            st.executeUpdate(
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `subject`, `seen`, `flagged`, " +
                    "`hasAttachment`, `sortKey`) VALUES ('e1', 'accA', 'mb1', 'Hello', 0, 0, 0, 100)",
            )
            st.executeUpdate(
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `subject`, `seen`, `flagged`, " +
                    "`hasAttachment`, `sortKey`) VALUES ('e2', 'accA', 'mb1', 'World', 1, 0, 0, 200)",
            )
            st.executeUpdate("INSERT INTO `email_bodies` VALUES ('e1', 'accA', '{}', '{}', 42)")
            st.executeUpdate("INSERT INTO `snoozed` VALUES ('e2', 'accA', 99999)")
        }
    }

    /** Apply the exact statements [MIGRATION_13_14] runs. */
    private fun runMigration() {
        fun rebuild(table: String, createSql: String, columns: String) {
            db.createStatement().use { st ->
                st.executeUpdate(createSql.replace("`$table`", "`${table}_new`"))
                st.executeUpdate("INSERT INTO `${table}_new` ($columns) SELECT $columns FROM `$table`")
                st.executeUpdate("DROP TABLE `$table`")
                st.executeUpdate("ALTER TABLE `${table}_new` RENAME TO `$table`")
            }
        }
        rebuild("emails", EMAILS_CREATE_SQL, EMAILS_COLUMNS)
        db.createStatement().use { st ->
            st.executeUpdate(EMAILS_MAILBOX_INDEX_SQL)
            st.executeUpdate("DROP INDEX IF EXISTS `index_email_bodies_accountId`")
        }
        rebuild("email_bodies", EMAIL_BODIES_CREATE_SQL, EMAIL_BODIES_COLUMNS)
        rebuild("snoozed", SNOOZED_CREATE_SQL, SNOOZED_COLUMNS)
    }

    private fun count(sql: String): Int = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            assertTrue(rs.next())
            rs.getInt(1)
        }
    }

    @Test fun preservesRowsAndColumns() {
        seedV13()
        runMigration()

        val columns = mutableSetOf<String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`emails`)").use { rs ->
                while (rs.next()) columns += rs.getString("name")
            }
        }
        assertEquals(
            setOf(
                "id", "accountId", "mailboxId", "threadId", "subject", "preview", "receivedAt",
                "fromName", "fromEmail", "seen", "flagged", "hasAttachment", "sortKey",
            ),
            columns,
        )

        assertEquals(2, count("SELECT COUNT(*) FROM `emails`"))
        assertEquals(1, count("SELECT COUNT(*) FROM `email_bodies` WHERE `fetchedAt` = 42"))
        // Snoozes are user data: the row must survive the rebuild bit-for-bit.
        assertEquals(1, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2' AND `until` = 99999"))
    }

    @Test fun primaryKeysBecomeAccountIdThenEmailId() {
        seedV13()
        runMigration()

        // PRAGMA reports the pk position (1-based) per column; 0 means "not part of the PK".
        fun pkPositions(table: String): Map<String, Int> {
            val pkPos = mutableMapOf<String, Int>()
            db.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                    while (rs.next()) pkPos[rs.getString("name")] = rs.getInt("pk")
                }
            }
            return pkPos
        }
        assertEquals(1, pkPositions("emails")["accountId"])
        assertEquals(2, pkPositions("emails")["id"])
        assertEquals(1, pkPositions("email_bodies")["accountId"])
        assertEquals(2, pkPositions("email_bodies")["id"])
        assertEquals(1, pkPositions("snoozed")["accountId"])
        assertEquals(2, pkPositions("snoozed")["emailId"])
    }

    @Test fun sameEmailIdCoexistsAcrossAccounts() {
        seedV13()
        runMigration()

        // A second account (sub-account of the same login) caches a message whose JMAP id equals
        // an existing row's id. Under the old single-column PKs these INSERTs would have collided;
        // with the composite keys they are distinct rows.
        db.createStatement().use { st ->
            st.executeUpdate(
                "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `seen`, `flagged`, " +
                    "`hasAttachment`, `sortKey`) VALUES ('e1', 'accB', 'mb9', 0, 0, 0, 300)",
            )
            st.executeUpdate("INSERT INTO `email_bodies` VALUES ('e1', 'accB', '{\"b\":1}', '{}', 43)")
            st.executeUpdate("INSERT INTO `snoozed` VALUES ('e2', 'accB', 11111)")
        }

        assertEquals(2, count("SELECT COUNT(*) FROM `emails` WHERE `id` = 'e1'"))
        assertEquals(2, count("SELECT COUNT(*) FROM `email_bodies` WHERE `id` = 'e1'"))
        assertEquals(2, count("SELECT COUNT(*) FROM `snoozed` WHERE `emailId` = 'e2'"))
    }

    @Test fun duplicateCompositeKeyIsRejected() {
        seedV13()
        runMigration()

        var conflicted = false
        try {
            db.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO `emails` (`id`, `accountId`, `mailboxId`, `seen`, `flagged`, " +
                        "`hasAttachment`, `sortKey`) VALUES ('e1', 'accA', 'mb1', 0, 0, 0, 999)",
                )
            }
        } catch (e: Exception) {
            conflicted = true
        }
        assertTrue("Re-inserting the same (accountId, id) must violate the composite PK", conflicted)
    }
}
