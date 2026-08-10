package app.gridlink.core.data.mail

import app.gridlink.core.data.db.MAILBOX_SYNC_POINT_SQL
import app.gridlink.core.data.db.MAILBOX_UIDVALIDITY_CREATE_SQL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The storage side of the CONDSTORE sync point, against real in-memory SQLite and running the
 * statements the app actually ships — the 22→23 migration's own SQL, and the DAO `@Query` text read
 * out of the shipped source ([DaoQuerySource]) rather than retyped here.
 *
 * Two properties that cannot be seen from Kotlin. That an install upgrading into this reads NULL,
 * which is what makes it do the full re-read instead of trusting a watermark it never had. And that
 * a folder renumbered between the SELECT and the write matches no row, so the write silently does
 * nothing — the rule is in the statement's own `WHERE`, and this is the only place it can be proved.
 */
class IncrementalSyncSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st -> st.executeUpdate(MAILBOX_UIDVALIDITY_CREATE_SQL) }
    }

    @After fun tearDown() = db.close()

    /** The 22→23 migration, statement for statement. */
    private fun migrate() = db.createStatement().use { st ->
        MAILBOX_SYNC_POINT_SQL.forEach { st.executeUpdate(it) }
    }

    /** `MailboxUidValidityDao.record`, i.e. the `@Insert(REPLACE)` that stores a numbering. */
    private fun record(mailboxId: String, uidValidity: Long) =
        db.prepareStatement("INSERT OR REPLACE INTO mailbox_uidvalidity (accountId, mailboxId, uidValidity) VALUES(?, ?, ?)")
            .use { ps ->
                ps.setString(1, "accA"); ps.setString(2, mailboxId); ps.setLong(3, uidValidity)
                ps.executeUpdate()
            }

    /** `MailboxUidValidityDao.recordSyncPoint`, through the statement the DAO declares. */
    private fun recordSyncPoint(
        mailboxId: String,
        uidValidity: Long,
        highestModSeq: Long,
        uidNext: Long,
        messageCount: Int,
    ): Int {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("MailboxUidValidityEntity", "recordSyncPoint"))
        val values = mapOf<String, Any>(
            "accountId" to "accA", "mailboxId" to mailboxId, "uidValidity" to uidValidity,
            "highestModSeq" to highestModSeq, "uidNext" to uidNext, "messageCount" to messageCount,
        )
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name -> ps.setObject(i + 1, values.getValue(name)) }
            ps.executeUpdate()
        }
    }

    /** `MailboxUidValidityDao.rowsForAccount`, mapped the way [MailboxUidValidityStore] maps it. */
    private fun syncPoints(): Map<String, ImapSyncPoint> {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("MailboxUidValidityEntity", "rowsForAccount"))
        check(order == listOf("accountId")) { "rowsForAccount now binds $order" }
        return db.prepareStatement(sql).use { ps ->
            ps.setString(1, "accA")
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        fun long(column: String) = rs.getLong(column).takeIf { !rs.wasNull() }
                        put(
                            rs.getString("mailboxId"),
                            ImapSyncPoint(
                                uidValidity = rs.getLong("uidValidity"),
                                highestModSeq = long("highestModSeq"),
                                uidNext = long("uidNext"),
                                messageCount = long("messageCount")?.toInt(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * The state of every existing install the day this ships: a numbering on record, no sync point.
     * The nulls have to survive the upgrade, because a null is what sends the folder down the full
     * re-read — a zero would read as a watermark and could declare the folder unchanged.
     */
    @Test fun `a row written before the columns existed reads as no sync point`() {
        record("INBOX", uidValidity = 5L)

        migrate()

        assertEquals(ImapSyncPoint(5L, null, null, null), syncPoints().getValue("INBOX"))
        assertEquals(ImapSyncPlan.Full, ImapSyncDecision.plan(syncPoints()["INBOX"], 5L, 900L, 41L, 40))
    }

    @Test fun `a recorded sync point comes back whole`() {
        migrate()
        record("INBOX", uidValidity = 5L)

        assertEquals(1, recordSyncPoint("INBOX", uidValidity = 5L, highestModSeq = 900L, uidNext = 41L, messageCount = 40))

        assertEquals(ImapSyncPoint(5L, 900L, 41L, 40), syncPoints().getValue("INBOX"))
    }

    /**
     * 🔴 The rule that keeps a renumbering from being papered over: the folder was renumbered
     * between the SELECT that produced these numbers and this write, so the UPDATE matches no row
     * and writes NOTHING. The cursor stays null and the next refresh reads the folder in full.
     */
    @Test fun `a write against a moved numbering changes nothing`() {
        migrate()
        record("INBOX", uidValidity = 12L) // the renumbering landed first

        assertEquals(0, recordSyncPoint("INBOX", uidValidity = 5L, highestModSeq = 900L, uidNext = 41L, messageCount = 40))

        assertEquals(ImapSyncPoint(12L, null, null, null), syncPoints().getValue("INBOX"))
    }

    /**
     * And the other order: the sync point is on record, THEN the folder is renumbered. `record` is
     * an `@Insert(REPLACE)` on the same row, so it rewrites the whole row and the three columns go
     * back to null in the same statement — nobody has to remember to clear them.
     */
    @Test fun `recording a new numbering clears the sync point with it`() {
        migrate()
        record("INBOX", uidValidity = 5L)
        recordSyncPoint("INBOX", uidValidity = 5L, highestModSeq = 900L, uidNext = 41L, messageCount = 40)

        record("INBOX", uidValidity = 12L)

        assertEquals(ImapSyncPoint(12L, null, null, null), syncPoints().getValue("INBOX"))
    }

    /** One account's folders, and only that account's: same folder name on two accounts (issue #31). */
    @Test fun `sync points are read per account`() {
        migrate()
        record("INBOX", uidValidity = 5L)
        record("Archive", uidValidity = 6L)
        db.prepareStatement("INSERT OR REPLACE INTO mailbox_uidvalidity (accountId, mailboxId, uidValidity) VALUES(?, ?, ?)")
            .use { ps -> ps.setString(1, "accB"); ps.setString(2, "INBOX"); ps.setLong(3, 9L); ps.executeUpdate() }

        assertEquals(setOf("INBOX", "Archive"), syncPoints().keys)
        assertEquals(5L, syncPoints().getValue("INBOX").uidValidity)
    }

    /** A folder never seen at all is absent, not a row of zeroes. */
    @Test fun `a folder with no row at all has no sync point`() {
        migrate()

        assertNull(syncPoints()["INBOX"])
        assertEquals(ImapSyncPlan.Full, ImapSyncDecision.plan(syncPoints()["INBOX"], 5L, 900L, 41L, 40))
    }
}
