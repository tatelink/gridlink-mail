package app.sterna.ui.sender

import app.sterna.core.data.mail.SenderVolume
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions the "Mail by sender" screen makes, taken out of the ViewModel so a JVM test can
 * RUN them: what the header number is, whether a Trash can be named, and whether the row menu may
 * offer the rule at all. (`MailBySenderViewModel` is an `AndroidViewModel`; there is no
 * Robolectric and no instrumented test in this repo.)
 */
class MailBySenderTest {

    private fun volume(email: String, total: Int, unread: Int = 0) =
        SenderVolume(email = email, name = null, total = total, unread = unread, latest = 0)

    @Test fun `the header number is the sum of the lines`() {
        val rows = listOf(volume("a@x", 3), volume("b@x", 2), volume("c@x", 1))
        assertEquals(6, cachedTotal(rows))
    }

    @Test fun `no lines is zero, not an empty-looking number from somewhere else`() {
        assertEquals(0, cachedTotal(emptyList()))
    }

    @Test fun `the header number counts messages, not senders`() {
        // The distinction the screen lives on: three senders, twelve messages.
        assertEquals(12, cachedTotal(listOf(volume("a@x", 7), volume("b@x", 4), volume("c@x", 1))))
    }

    // -- naming the Trash --------------------------------------------------------------------

    @Test fun `a JMAP trash at the root is named by its name`() {
        val trash = Mailbox(id = "m5", name = "Trash", role = "trash")
        val folders = listOf(Mailbox(id = "m1", name = "Inbox", role = "inbox"), trash)
        assertEquals("Trash", trashFilePath(folders))
    }

    @Test fun `a nested trash is named by its whole path`() {
        // Sieve names a subfolder by its path; the leaf alone reaches the wrong folder, or none.
        val root = Mailbox(id = "m1", name = "INBOX", role = "inbox")
        val trash = Mailbox(id = "m9", name = "Trash", role = "trash", parentId = "m1")
        assertEquals("INBOX/Trash", trashFilePath(listOf(root, trash)))
    }

    @Test fun `an account with no trash names nothing`() {
        val folders = listOf(
            Mailbox(id = "m1", name = "Inbox", role = "inbox"),
            Mailbox(id = "m2", name = "Archive", role = "archive"),
        )
        assertNull(trashFilePath(folders))
        assertNull(trashFilePath(emptyList()))
    }

    @Test fun `a trash whose parent is missing names nothing`() {
        // The folder cannot be named with certainty, so nothing is offered — rather than filing
        // future mail somewhere nobody chose.
        val trash = Mailbox(id = "m9", name = "Trash", role = "trash", parentId = "gone")
        assertNull(trashFilePath(listOf(trash)))
    }

    // -- when the rule may be offered at all ---------------------------------------------------

    @Test fun `the rule is offered only with both a supporting server and a nameable trash`() {
        assertTrue(canBlockSender(supported = true, trashPath = "Trash"))
        // IMAP, or a JMAP server without the Sieve capability: exactly where the Filters screen
        // shows its "not supported" note. Nothing local is invented to stand in for it.
        assertFalse(canBlockSender(supported = false, trashPath = "Trash"))
        // Supported, but there is no Trash to name as the rule's target.
        assertFalse(canBlockSender(supported = true, trashPath = null))
        assertFalse(canBlockSender(supported = false, trashPath = null))
    }
}
