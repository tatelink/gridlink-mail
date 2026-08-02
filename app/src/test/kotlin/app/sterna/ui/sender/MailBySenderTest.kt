package app.sterna.ui.sender

import app.sterna.core.data.mail.FilterRulesState
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

    @Test fun `the rule is offered on a server that takes it, with a trash to name`() {
        assertTrue(
            "a Sieve-capable account with a nameable Trash is the case the gesture exists for",
            canBlockSender(FilterRulesState.Loaded(emptyList()), trashPath = "Trash"),
        )
    }

    @Test fun `no server support, no entry`() {
        // IMAP, or a JMAP server without the Sieve capability: exactly where the Filters screen
        // shows its "not supported" note. Nothing local is invented to stand in for it.
        assertFalse(
            "the server would refuse the rule; offering it promises something that cannot happen",
            canBlockSender(FilterRulesState.Unsupported, trashPath = "Trash"),
        )
    }

    @Test fun `no trash to name, no entry`() {
        val noTrash = "with no Trash to name there is no target to write into the rule, and " +
            "nothing is invented to stand in for one"
        assertFalse(noTrash, canBlockSender(FilterRulesState.Loaded(emptyList()), trashPath = null))
        assertFalse(noTrash, canBlockSender(FilterRulesState.Unsupported, trashPath = null))
        assertFalse(noTrash, canBlockSender(null, trashPath = null))
    }

    @Test fun `an account running its own Sieve script does not get the entry`() {
        // Saving activates Sterna's script and switches hers off. The filter editor warns about
        // that in red before its Save button and stays the place to do it knowingly; a list row
        // has nowhere to put the warning, so it does not carry the gesture.
        assertFalse(
            "one tap would switch off the account's own Sieve script; the entry must be absent, " +
                "and the Filters screen — which warns in red before saving — stays the way in",
            canBlockSender(
                FilterRulesState.Loaded(emptyList(), foreignActiveScript = true),
                trashPath = "Trash",
            ),
        )
    }

    @Test fun `a read that failed keeps the entry`() {
        // Offline is not "unsupported". Hiding the entry here makes an unreachable server
        // indistinguishable from an IMAP account — no word, no retry, and the difference is
        // never explained. Tapping it reads again and reports the failure honestly.
        assertTrue(
            "a read that failed is not an unsupported account: hiding the entry makes an " +
                "unreachable server look like IMAP, with no word and no retry",
            canBlockSender(null, trashPath = "Trash"),
        )
    }

    // -- what an Undo has to move back ------------------------------------------------------------

    @Test fun `only the ids the server confirmed are moved back`() {
        val targets = restoreTargets(
            succeeded = setOf("a", "b"),
            sources = mapOf("a" to "inbox", "b" to "archive", "c" to "inbox"),
            dest = "trash",
        )
        assertEquals(listOf("a", "b"), targets.map { it.emailId })
        assertEquals(listOf("inbox", "archive"), targets.map { it.sourceMailboxId })
        assertEquals(listOf("trash", "trash"), targets.map { it.destMailboxId })
    }

    @Test fun `a message that was already in the destination is not moved back`() {
        // Its "restore" would be a move to where it already sits: the server accepts it, the undo
        // reports success, and the mail stays in the Trash. Nothing to undo, so nothing offered.
        val targets = restoreTargets(
            succeeded = setOf("a", "b"),
            sources = mapOf("a" to "trash", "b" to "inbox"),
            dest = "trash",
        )
        assertEquals(listOf("b"), targets.map { it.emailId })
    }

    @Test fun `a message whose source folder is unknown is not moved back`() {
        val targets = restoreTargets(
            succeeded = setOf("a"),
            sources = mapOf("a" to null),
            dest = "trash",
        )
        assertEquals(emptyList<Any>(), targets)
    }

    // -- the number the confirmation announces -----------------------------------------------------

    @Test fun `the dialog counts the list it will delete, not the row it came from`() {
        // The row's total was read when the screen loaded; the ids are read when the dialog
        // opens. A message arriving in between makes them differ, and the dialog is the one that
        // is right — so PendingDelete carries the ids and NOT the row's total, and a dialog that
        // cannot reach the stale number cannot print it.
        val row = volume("a@x", total = 3)
        val pending = PendingDelete(row, listOf("m1", "m2", "m3", "m4"))
        assertEquals(4, pending.ids.size)
        assertEquals("a@x", pending.sender.email)
    }
}
