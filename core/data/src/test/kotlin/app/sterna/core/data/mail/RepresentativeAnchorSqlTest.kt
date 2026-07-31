package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * Verifies, against in-memory SQLite, the anchor selection of
 * [app.sterna.core.data.db.EmailDao.oldestRepresentativeEmailId] (and its companion count,
 * [app.sterna.core.data.db.EmailDao.representativeCountForMailbox]): the fetch-older page is
 * anchored on the oldest cached row that is its thread's NEWEST within the folder — a cached
 * older thread member may sit below the contiguous window (fetched on expand), and anchoring
 * on it would skip the gap.
 *
 * Both statements are read out of the shipped DAO by [DaoQuerySource], not retyped, so changing
 * the DAO's SQL changes this test's SQL with it — a retyped copy would keep passing against a
 * query the pager no longer runs. Each query names `:accountId` and `:mailboxId` twice (outer
 * scope, then correlated sub-select), so [DaoQuerySource.bindOrder] is what says which `?` is
 * which: the statement's order, not the signature's.
 */
class RepresentativeAnchorSqlTest {
    private lateinit var db: Connection

    private val anchorSql = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("oldestRepresentativeEmailId"))

    private val countSql = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("representativeCountForMailbox"))

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

    private fun insert(
        id: String, threadId: String?, sortKey: Long,
        mailbox: String = "inbox", accountId: String = "acc",
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e', 0, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox); ps.setString(4, threadId)
            ps.setLong(5, sortKey)
            ps.executeUpdate()
        }
    }

    /** Prepare one of the shipped statements, its `?` filled in the order [DaoQuerySource] gives. */
    private fun prepare(
        query: Pair<String, List<String>>,
        accountId: String,
        mailbox: String,
    ): PreparedStatement {
        val (sql, order) = query
        val ps = db.prepareStatement(sql)
        order.forEachIndexed { i, name ->
            ps.setString(
                i + 1,
                when (name) {
                    "accountId" -> accountId
                    "mailboxId" -> mailbox
                    else -> error("Unexpected parameter ':$name' in the representative queries")
                },
            )
        }
        return ps
    }

    private fun anchor(accountId: String = "acc", mailbox: String = "inbox"): String? =
        prepare(anchorSql, accountId, mailbox).use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun representativeCount(accountId: String = "acc", mailbox: String = "inbox"): Int =
        prepare(countSql, accountId, mailbox).use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    @Test fun skipsOlderThreadMemberAndAnchorsOnItsRepresentative() {
        insert("m1", threadId = "T1", sortKey = 100) // oldest ROW, but not T1's newest
        insert("m2", threadId = "T1", sortKey = 300) // T1's representative
        insert("solo", threadId = null, sortKey = 200) // its own representative
        // The raw oldest row (m1) must NOT be the anchor: the collapsed query never lists it.
        assertEquals("solo", anchor())
        assertEquals(2, representativeCount())
    }

    @Test fun threadlessOldestRowIsItsOwnAnchor() {
        insert("a", threadId = null, sortKey = 100)
        insert("b", threadId = null, sortKey = 200)
        assertEquals("a", anchor())
        assertEquals(2, representativeCount())
    }

    @Test fun scopesToAccountAndMailbox() {
        insert("in1", threadId = "T1", sortKey = 100)
        insert("sent1", threadId = "T1", sortKey = 300, mailbox = "sent") // newer, other folder
        insert("other1", threadId = "T1", sortKey = 50, accountId = "accB") // other account
        // Within (acc, inbox), in1 is T1's newest — the Sent reply doesn't demote it.
        assertEquals("in1", anchor())
        assertEquals(1, representativeCount())
    }

    @Test fun emptyMailboxYieldsNoAnchor() {
        assertEquals(null, anchor())
        assertEquals(0, representativeCount())
    }
}
