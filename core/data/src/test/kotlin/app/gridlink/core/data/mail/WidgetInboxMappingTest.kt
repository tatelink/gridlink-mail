package app.gridlink.core.data.mail

import app.gridlink.core.data.db.EmailEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a cached row becomes on the home screen.
 *
 * The mapping is small, but every case below is one the widget cannot recover from: it draws from
 * the cache with no network and no second chance, so a field that arrives wrong here is simply
 * wrong on the user's home screen until the next sync.
 */
class WidgetInboxMappingTest {

    private fun row(
        fromName: String? = "Ada Lovelace",
        fromEmail: String? = "ada@example.org",
        subject: String? = "Analytical engine",
        preview: String? = "Notes on the machine",
        seen: Boolean = false,
        flagged: Boolean = false,
        hasAttachment: Boolean = false,
        sortKey: Long = 1_700_000_000_000L,
    ) = EmailEntity(
        id = "m1",
        accountId = "a1",
        mailboxId = "inbox",
        threadId = null,
        subject = subject,
        preview = preview,
        receivedAt = null,
        fromName = fromName,
        fromEmail = fromEmail,
        seen = seen,
        flagged = flagged,
        hasAttachment = hasAttachment,
        sortKey = sortKey,
    )

    @Test
    fun `a display name wins over the address`() {
        assertEquals("Ada Lovelace", row().toWidgetMessage().sender)
    }

    @Test
    fun `a message with no display name falls back to the address`() {
        assertEquals("ada@example.org", row(fromName = null).toWidgetMessage().sender)
    }

    /**
     * A blank display name is not a display name. Some senders set `From: "" <a@b>`, and taking it
     * at face value would print an empty row where the address would have done.
     */
    @Test
    fun `a blank display name falls back to the address`() {
        assertEquals("ada@example.org", row(fromName = "   ").toWidgetMessage().sender)
    }

    /** Nothing to show, and nothing invented: the widget names this case, not the reader. */
    @Test
    fun `a message from nobody yields an empty sender`() {
        assertEquals("", row(fromName = null, fromEmail = null).toWidgetMessage().sender)
    }

    @Test
    fun `a missing subject and preview come through empty rather than null`() {
        val message = row(subject = null, preview = null).toWidgetMessage()
        assertEquals("", message.subject)
        assertEquals("", message.preview)
    }

    /**
     * 🔴 `seen` and `unread` are opposites, and the widget draws a dot on one of them. Inverting
     * this puts an unread marker on every message the user has already read, which reads as an
     * inbox that never empties.
     */
    @Test
    fun `unread is the inverse of seen`() {
        assertTrue(row(seen = false).toWidgetMessage().unread)
        assertFalse(row(seen = true).toWidgetMessage().unread)
    }

    @Test
    fun `the row carries the ids the tap needs`() {
        val message = row().toWidgetMessage()
        assertEquals("m1", message.emailId)
        assertEquals("a1", message.accountId)
        assertEquals("inbox", message.mailboxId)
    }

    /** sortKey, not receivedAt: it is the epoch-millis field the list is already ordered by. */
    @Test
    fun `the timestamp is the sort key`() {
        assertEquals(1_700_000_000_000L, row().toWidgetMessage().receivedAtMillis)
    }

    @Test
    fun `flags come through`() {
        val message = row(flagged = true, hasAttachment = true).toWidgetMessage()
        assertTrue(message.flagged)
        assertTrue(message.hasAttachment)
    }

    /**
     * 🔴 The signed-out snapshot must not claim a count. Zero would be indistinguishable on screen
     * from a genuinely empty inbox, which is the difference between "you have no mail" and "you
     * are not signed in" — two things the user would do completely different things about.
     */
    @Test
    fun `the signed-out snapshot has no count and no rows`() {
        assertFalse(WidgetInboxSnapshot.SIGNED_OUT.signedIn)
        assertNull(WidgetInboxSnapshot.SIGNED_OUT.unreadCount)
        assertTrue(WidgetInboxSnapshot.SIGNED_OUT.messages.isEmpty())
    }
}
