package app.gridlink.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Applying a CONDSTORE flag delta to the cache (RFC 7162 §3.1.3).
 *
 * A delta carries a UID and its flags and NOTHING else — no subject, no sender, no date. So the
 * statement that applies it has to be an UPDATE and must never be able to create a row: an upsert
 * fed a delta would insert a blank message, and a blank message in the list looks like a bug in the
 * mail server rather than one here. That is the property proved below, on the shipped statement.
 */
class FlagDeltaSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(id: String, seen: Boolean, flagged: Boolean) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, 'accA', 'INBOX', 't', 'Subject', 'p', '', 'A', 'a@b.c', ?, ?, 0, 1)",
        ).use { ps ->
            ps.setString(1, id); ps.setInt(2, if (seen) 1 else 0); ps.setInt(3, if (flagged) 1 else 0)
            ps.executeUpdate()
        }
    }

    /** `EmailDao.setFlags`, through the statement the DAO declares. */
    private fun setFlags(id: String, seen: Boolean, flagged: Boolean): Int {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("setFlags"))
        val values = mapOf<String, Any>("accountId" to "accA", "id" to id, "seen" to seen, "flagged" to flagged)
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name -> ps.setObject(i + 1, values.getValue(name)) }
            ps.executeUpdate()
        }
    }

    private fun rows(): List<Triple<String, Boolean, Boolean>> = db.createStatement().use { st ->
        st.executeQuery("SELECT id, seen, flagged FROM emails ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getInt(2) == 1, rs.getInt(3) == 1)) }
        }
    }

    /** Both directions: a parser that only ever sets flags makes CLEARING one invisible. */
    @Test fun `a flag change is applied both ways`() {
        insert("imap:accA:INBOX:7", seen = false, flagged = true)

        assertEquals(1, setFlags("imap:accA:INBOX:7", seen = true, flagged = false))

        assertEquals(listOf(Triple("imap:accA:INBOX:7", true, false)), rows())
    }

    /**
     * 🔴 The one that matters: a delta names a message this install never cached — it sits outside
     * the window the list keeps, or was read on another device. Nothing is written, and above all
     * nothing is CREATED. An upsert here would put a blank row in the user's inbox.
     */
    @Test fun `a change for an uncached message creates nothing`() {
        assertEquals(0, setFlags("imap:accA:INBOX:999", seen = true, flagged = true))

        assertEquals(emptyList<Triple<String, Boolean, Boolean>>(), rows())
    }

    /** Scoped to the account: two accounts on one server cache the same folder name (issue #31). */
    @Test fun `a change never crosses into another account`() {
        insert("imap:accA:INBOX:7", seen = false, flagged = false)
        db.prepareStatement(
            "INSERT INTO emails VALUES('imap:accA:INBOX:7', 'accB', 'INBOX', 't', 'S', 'p', '', 'A', 'a@b.c', 0, 0, 0, 1)",
        ).use { it.executeUpdate() }

        setFlags("imap:accA:INBOX:7", seen = true, flagged = true)

        val other = db.createStatement().use { st ->
            st.executeQuery("SELECT seen FROM emails WHERE accountId = 'accB'").use { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals(0, other)
    }
}
