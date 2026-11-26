package app.gridlink.core.data.mail

import app.gridlink.core.data.db.WIDGET_UNREAD_SQL
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The widget's unread number (Play report 2026-11-25: an IMAP inbox listed a new unread message
 * while the widget did not count it).
 *
 * IMAP folder rows always carry `unreadEmails = 0`, so a widget that read the folder row showed 0
 * on every IMAP account. These tests hold the IMAP path to the account's stored inbox count and to
 * the cached unread rows, and keep JMAP on the folder counter it always used.
 */
class WidgetUnreadCountTest {

    @Test
    fun `IMAP ignores the folder row's hard zero and uses the stored inbox count`() = runTest {
        val count = widgetUnreadCount(isImap = true, folderCounter = 0, storedInboxUnread = 3, cachedUnread = { 1 })
        assertEquals(3, count)
    }

    @Test
    fun `IMAP counts mail a background pass cached before the stored count caught up`() = runTest {
        // IDLE or the fallback worker added an unread row; the stored count is from the last
        // foreground refresh and still says 0. The list shows the row bold, so the widget counts it.
        val count = widgetUnreadCount(isImap = true, folderCounter = 0, storedInboxUnread = 0, cachedUnread = { 1 })
        assertEquals(1, count)
    }

    @Test
    fun `IMAP with no stored count for this folder falls back to the cached rows`() = runTest {
        val count = widgetUnreadCount(isImap = true, folderCounter = 0, storedInboxUnread = null, cachedUnread = { 2 })
        assertEquals(2, count)
    }

    @Test
    fun `JMAP keeps the folder counter and never queries the cache`() = runTest {
        val count = widgetUnreadCount(
            isImap = false,
            folderCounter = 7,
            storedInboxUnread = 99,
            cachedUnread = {
                fail("JMAP must not count cached rows")
                0
            },
        )
        assertEquals(7, count)
    }

    @Test
    fun `an uncached folder is unknown on both protocols, never zero`() = runTest {
        for (imap in listOf(true, false)) {
            assertNull(
                widgetUnreadCount(isImap = imap, folderCounter = null, storedInboxUnread = 4, cachedUnread = { 1 }),
            )
        }
    }

    // ---- the cached-unread query itself, run against a real SQLite ----

    private lateinit var db: Connection

    @Before fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE emails (accountId TEXT NOT NULL, id TEXT NOT NULL, " +
                    "mailboxId TEXT NOT NULL, seen INTEGER NOT NULL)",
            )
            st.executeUpdate(
                "CREATE TABLE snoozed (accountId TEXT NOT NULL, emailId TEXT NOT NULL, until INTEGER NOT NULL)",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun email(accountId: String, id: String, mailboxId: String, seen: Boolean) =
        db.prepareStatement("INSERT INTO emails VALUES (?, ?, ?, ?)").use {
            it.setString(1, accountId)
            it.setString(2, id)
            it.setString(3, mailboxId)
            it.setInt(4, if (seen) 1 else 0)
            it.executeUpdate()
        }

    private fun snooze(accountId: String, emailId: String, until: Long) =
        db.prepareStatement("INSERT INTO snoozed VALUES (?, ?, ?)").use {
            it.setString(1, accountId)
            it.setString(2, emailId)
            it.setLong(3, until)
            it.executeUpdate()
        }

    private fun unread(accountId: String, mailboxId: String): Int =
        db.prepareStatement(WIDGET_UNREAD_SQL.replace(":accountId", "?").replace(":mailboxId", "?")).use {
            it.setString(1, accountId)
            it.setString(2, mailboxId)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    @Test
    fun `the query counts unseen rows of one account's folder only`() {
        email("a", "1", "INBOX", seen = false)
        email("a", "2", "INBOX", seen = false)
        email("a", "3", "INBOX", seen = true)
        email("a", "4", "Trash", seen = false)
        email("b", "5", "INBOX", seen = false) // same folder path on a sibling account
        assertEquals(2, unread("a", "INBOX"))
    }

    @Test
    fun `the query leaves out mail snoozed into the future but counts an expired snooze`() {
        val now = System.currentTimeMillis()
        email("a", "1", "INBOX", seen = false)
        email("a", "2", "INBOX", seen = false)
        snooze("a", "1", until = now + 3_600_000)
        snooze("a", "2", until = now - 3_600_000)
        assertEquals(1, unread("a", "INBOX"))
    }
}
