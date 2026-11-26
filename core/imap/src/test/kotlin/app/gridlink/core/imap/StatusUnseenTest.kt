package app.gridlink.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `STATUS <mailbox> (UNSEEN)`: how a background pass reads the inbox unread count without
 * SELECTing the inbox (the widget count fix, 2026-11-25).
 */
class StatusUnseenTest {

    @Test
    fun `reads the UNSEEN count out of the STATUS response with one command`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("STATUS") -> "* STATUS INBOX (UNSEEN 4)\r\n$tag OK status done\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val unseen = server.session().use { it.statusUnseen("INBOX") }
            assertEquals(4, unseen)
            assertEquals(listOf("""STATUS "INBOX" (UNSEEN)""", "LOGOUT"), server.issued())
        }
    }

    @Test
    fun `a quoted mailbox name and extra items are parsed`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("STATUS") -> "* STATUS \"INBOX\" (MESSAGES 9 UNSEEN 0)\r\n$tag OK done\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            assertEquals(0, server.session().use { it.statusUnseen("INBOX") })
        }
    }

    @Test
    fun `a response without an UNSEEN count is null, not zero`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("STATUS") -> "$tag OK no data\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            assertNull(server.session().use { it.statusUnseen("INBOX") })
        }
    }

    /** A refusal throws, and the session stays usable: the caller keeps syncing after it. */
    @Test
    fun `a refused STATUS throws and leaves the stream in step`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("STATUS") -> "$tag NO status not allowed\r\n"
                line.startsWith("SEARCH") -> "* SEARCH 1 2\r\n$tag OK done\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                assertThrows(ImapException::class.java) { session.statusUnseen("INBOX") }
                assertEquals(2, session.unseenCount())
            }
        }
    }
}
