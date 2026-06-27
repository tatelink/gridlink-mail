package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the real conversation-grouping SQL ([conversationSql]) against an in-memory
 * SQLite engine, so the thread collapse / count / unread / snooze logic is verified
 * without an Android device.
 */
class ConversationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT PRIMARY KEY, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER
                )
                """.trimIndent(),
            )
            st.executeUpdate("CREATE TABLE snoozed(emailId TEXT PRIMARY KEY, until INTEGER)")
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(
        id: String, threadId: String?, seen: Int, flagged: Int, sortKey: Long,
        mailbox: String = "inbox", accountId: String = "acc",
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e', ?, ?, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox); ps.setString(4, threadId)
            ps.setInt(5, seen); ps.setInt(6, flagged); ps.setLong(7, sortKey)
            ps.executeUpdate()
        }
    }

    private fun snooze(id: String, untilMillis: Long) {
        db.prepareStatement("INSERT INTO snoozed VALUES(?, ?)").use {
            it.setString(1, id); it.setLong(2, untilMillis); it.executeUpdate()
        }
    }

    /** Run the grouping SQL for a single "inbox" mailbox; returns rows as maps. */
    private fun run(sort: SortOrder = SortOrder.DATE_DESC, unreadOnly: Boolean = false): List<Map<String, Any?>> {
        val sql = conversationSql(mailboxCount = 1, sort = sort, unreadOnly = unreadOnly)
        return db.prepareStatement(sql).use { ps ->
            // in-view sub-query, cross-folder count scope, outer WHERE — each binds "inbox".
            ps.setString(1, "inbox"); ps.setString(2, "inbox"); ps.setString(3, "inbox")
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            mapOf(
                                "id" to rs.getString("id"),
                                "threadCount" to rs.getInt("threadCount"),
                                "threadUnread" to rs.getInt("threadUnread"),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test fun collapsesThreadToLatestMessageWithCountAndUnread() {
        // A 2-message thread (m2 newest, m1 read / m2 unread) + a standalone read message.
        insert("m1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100)
        insert("m2", threadId = "T1", seen = 0, flagged = 0, sortKey = 200)
        insert("s1", threadId = null, seen = 1, flagged = 0, sortKey = 150)

        val rows = run()
        assertEquals(2, rows.size)
        // Newest-first: the thread's representative (m2) then the standalone.
        assertEquals("m2", rows[0]["id"])
        assertEquals(2, rows[0]["threadCount"])
        assertEquals(0, rows[0]["threadUnread"]) // 0 = has an unread message
        assertEquals("s1", rows[1]["id"])
        assertEquals(1, rows[1]["threadCount"])
        assertEquals(1, rows[1]["threadUnread"]) // 1 = all read
    }

    @Test fun unreadOnlyKeepsThreadsWithAnyUnread() {
        insert("m1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100)
        insert("m2", threadId = "T1", seen = 0, flagged = 0, sortKey = 200) // unread → thread kept
        insert("s1", threadId = null, seen = 1, flagged = 0, sortKey = 150) // read → dropped

        val rows = run(unreadOnly = true)
        assertEquals(1, rows.size)
        assertEquals("m2", rows[0]["id"])
    }

    @Test fun snoozedMessagesAreExcluded() {
        insert("s1", threadId = null, seen = 0, flagged = 0, sortKey = 100)
        insert("s2", threadId = null, seen = 0, flagged = 0, sortKey = 300) // would be first…
        snooze("s2", untilMillis = Long.MAX_VALUE) // …but snoozed into the future

        val rows = run()
        assertEquals(1, rows.size)
        assertEquals("s1", rows[0]["id"])
    }

    @Test fun accountScopeExcludesOtherAccountSharingAMailboxId() {
        // Two accounts whose inbox shares the same server-assigned mailbox id ("inbox")
        // — the case that made a single-account folder view show a mix of both accounts.
        insert("a1", threadId = null, seen = 1, flagged = 0, sortKey = 100, accountId = "accA")
        insert("b1", threadId = null, seen = 1, flagged = 0, sortKey = 200, accountId = "accB")

        val sql = conversationSql(mailboxCount = 1, sort = SortOrder.DATE_DESC, unreadOnly = false, hasAccountId = true)
        val rows = db.prepareStatement(sql).use { ps ->
            // in-view (mailbox, accountId), cross-folder count scope (accountId), outer (mailbox, accountId).
            ps.setString(1, "inbox"); ps.setString(2, "accA")
            ps.setString(3, "accA")
            ps.setString(4, "inbox"); ps.setString(5, "accA")
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
        assertEquals(listOf("a1"), rows) // only account A's mail; b1 is excluded
    }

    @Test fun threadCountSpansFoldersSoAnInboxMessageWithASentReplyIsAConversation() {
        // A thread with one message in the Inbox and its reply filed in Sent.
        insert("in1", threadId = "T1", seen = 0, flagged = 0, sortKey = 100, mailbox = "inbox")
        insert("sent1", threadId = "T1", seen = 1, flagged = 0, sortKey = 200, mailbox = "sent")

        val sql = conversationSql(mailboxCount = 1, sort = SortOrder.DATE_DESC, unreadOnly = false, hasAccountId = true)
        val rows = db.prepareStatement(sql).use { ps ->
            // Viewing the Inbox for account "acc".
            ps.setString(1, "inbox"); ps.setString(2, "acc")
            ps.setString(3, "acc")
            ps.setString(4, "inbox"); ps.setString(5, "acc")
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(mapOf("id" to rs.getString("id"), "threadCount" to rs.getInt("threadCount")))
                }
            }
        }
        assertEquals(1, rows.size)
        assertEquals("in1", rows[0]["id"])   // representative is the in-view (Inbox) message…
        assertEquals(2, rows[0]["threadCount"]) // …but the count includes the Sent reply.
    }

    @Test fun favouritesPinToTop() {
        insert("a", threadId = null, seen = 1, flagged = 0, sortKey = 300)
        insert("b", threadId = null, seen = 1, flagged = 1, sortKey = 100) // older but flagged

        val rows = run()
        assertEquals("b", rows[0]["id"]) // flagged pinned above the newer unflagged
        assertEquals("a", rows[1]["id"])
    }
}
