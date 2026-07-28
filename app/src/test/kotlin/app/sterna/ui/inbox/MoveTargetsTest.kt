package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which folders a move-to-folder picker offers, and in which order — the rule behind both the
 * list's selection-bar picker and the reader's overflow entry (#73).
 */
class MoveTargetsTest {

    private fun mailbox(id: String, role: String? = null, name: String = id) =
        Mailbox(id = id, name = name, role = role)

    private val folders = listOf(
        mailbox("custom-b", name = "Bills"),
        mailbox("trash-1", role = "trash"),
        mailbox("custom-a", name = "Archive 2024"),
        mailbox("inbox-1", role = "inbox"),
        mailbox("junk-1", role = "junk"),
        mailbox("archive-1", role = "archive"),
        mailbox("drafts-1", role = "drafts"),
        mailbox("sent-1", role = "sent"),
    )

    @Test fun `the folder the message is already in is not offered`() {
        val targets = moveTargets(folders, currentMailboxId = "junk-1")
        assertTrue(targets.none { it.id == "junk-1" })
        assertEquals(folders.size - 1, targets.size)
    }

    @Test fun `standard folders lead in a fixed order, custom folders follow`() {
        val ids = moveTargets(folders, currentMailboxId = null).map { it.id }
        assertEquals(
            listOf("inbox-1", "drafts-1", "sent-1", "junk-1", "archive-1", "trash-1", "custom-b", "custom-a"),
            ids,
        )
    }

    @Test fun `custom folders keep the order they came in`() {
        // The sort is stable, so the drawer's own ordering of the user's folders survives.
        val custom = moveTargets(folders, currentMailboxId = null).filter { it.role == null }
        assertEquals(listOf("custom-b", "custom-a"), custom.map { it.id })
    }

    @Test fun `a message read from Trash can be moved back out`() {
        // The reported case (#73): a draft that only Archive could rescue from the Trash.
        val ids = moveTargets(folders, currentMailboxId = "trash-1").map { it.id }
        assertTrue("inbox-1" in ids)
        assertTrue("drafts-1" in ids)
        assertTrue("trash-1" !in ids)
    }

    @Test fun `an unknown current folder excludes nothing`() {
        // Unified inbox, or a message whose body fetch dropped its mailbox: offering one folder
        // that happens to be its own beats hiding a guessed one.
        assertEquals(folders.size, moveTargets(folders, currentMailboxId = null).size)
    }

    @Test fun `an all-mail folder ranks with Archive`() {
        // Gmail-style servers expose "all" instead of an Archive role.
        val gmail = listOf(mailbox("all-1", role = "all"), mailbox("trash-1", role = "trash"), mailbox("inbox-1", role = "inbox"))
        assertEquals(listOf("inbox-1", "all-1", "trash-1"), moveTargets(gmail, null).map { it.id })
    }

    @Test fun `an unknown role is treated as a custom folder`() {
        val exotic = listOf(mailbox("weird-1", role = "templates"), mailbox("inbox-1", role = "inbox"))
        assertEquals(listOf("inbox-1", "weird-1"), moveTargets(exotic, null).map { it.id })
    }

    @Test fun `an account with a single folder offers nothing`() {
        // The reader hides its menu entry on this — a picker with nothing to pick is a dead end.
        assertTrue(moveTargets(listOf(mailbox("inbox-1", role = "inbox")), "inbox-1").isEmpty())
        assertTrue(moveTargets(emptyList(), "inbox-1").isEmpty())
    }

    @Test fun `ids are matched exactly, not by prefix`() {
        // IMAP ids are paths: a subfolder of the current folder is a legitimate destination.
        val nested = listOf(mailbox("Work"), mailbox("Work/2026"), mailbox("Workshop"))
        assertEquals(listOf("Work/2026", "Workshop"), moveTargets(nested, "Work").map { it.id })
    }
}
