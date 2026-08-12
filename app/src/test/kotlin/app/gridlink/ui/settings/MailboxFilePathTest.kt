package app.gridlink.ui.settings

import app.gridlink.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The path a server-side rule must name to file mail into a folder.
 *
 * The picker-label half of this file went with the upstream move picker it described; Gridlink's
 * own picker answers that question itself. What survives is the value that goes on a wire, which
 * is exactly the half that could file mail somewhere nobody chose.
 */
class MailboxFilePathTest {

    /** An IMAP folder: the id IS the path the server named, the name only its last segment. */
    private fun imap(path: String, delimiter: Char = '.', role: String? = null) =
        Mailbox(id = path, name = path.substringAfterLast(delimiter), role = role)

    /** A JMAP folder: an opaque id, the hierarchy carried by parentId. */
    private fun jmap(id: String, name: String, parentId: String? = null, role: String? = null) =
        Mailbox(id = id, name = name, parentId = parentId, role = role)

    // ---- what a server-side rule must name -------------------------------------------------
    //
    // Server-side rules are JMAP-ONLY: MailRepository.loadFilterRules answers Unsupported for
    // every IMAP account, and the rule editor is this function's single caller. So only the
    // JMAP cases below describe something that reaches a wire today; the IMAP ones pin the
    // behaviour of a branch kept for the day IMAP filters exist, and say so in their name.

    @Test fun `a JMAP rule names the chain of folder names`() {
        val work = jmap("mb1", "Work")
        val year = jmap("mb2", "2026", parentId = "mb1")
        val q1 = jmap("mb3", "Q1", parentId = "mb2")
        assertEquals("Work/2026/Q1", mailboxFilePath(q1, listOf(work, year, q1)))
    }

    @Test fun `a JMAP folder at the root is named by itself`() {
        val jmapRoot = jmap("mb1", "Bills")
        assertEquals("Bills", mailboxFilePath(jmapRoot, listOf(jmapRoot)))
    }

    @Test fun `a JMAP name holding the separator is not named at all`() {
        // "Q1/Q2" joined into a path would name Q2 inside Q1: two folders that do not exist.
        // No fileinto syntax can tell that slash from a folder boundary, so the folder is left
        // out of the rule editor's choices rather than offered under a path that lies.
        val work = jmap("mb1", "Work")
        val odd = jmap("mb2", "Q1/Q2", parentId = "mb1")
        assertNull(mailboxFilePath(odd, listOf(work, odd)))
        // At the root it is fine: nothing is joined, so there is nothing to misread.
        val alone = jmap("mb3", "Q1/Q2")
        assertEquals("Q1/Q2", mailboxFilePath(alone, listOf(alone)))
    }

    @Test fun `a chain that does not reach a root names nothing`() {
        // Half a path names a different folder, silently - the failure this change is about.
        // Both ways of stopping short: a parent absent from the list, and a loop.
        val orphan = jmap("mb2", "Done", parentId = "mb1")
        assertNull(mailboxFilePath(orphan, listOf(orphan)))
        val a = jmap("mb1", "A", parentId = "mb2")
        val b = jmap("mb2", "B", parentId = "mb1")
        assertNull(mailboxFilePath(b, listOf(a, b)))
    }

    @Test fun `an opaque JMAP id is never mistaken for a path`() {
        val opaque = jmap("a1.b2.c3", "Bills")
        assertEquals("Bills", mailboxFilePath(opaque, listOf(opaque)))
    }

    @Test fun `IMAP, not reachable today - a rule would name the whole path, own delimiter`() {
        val done = imap("INBOX.ProjectA.Done")
        assertEquals("INBOX.ProjectA.Done", mailboxFilePath(done, listOf(done)))
        val q1 = imap("Work/2026/Q1", delimiter = '/')
        assertEquals("Work/2026/Q1", mailboxFilePath(q1, listOf(q1)))
        val root = imap("Bills")
        assertEquals("Bills", mailboxFilePath(root, listOf(root)))
    }

    @Test fun `IMAP, not reachable today - a standard folder is named by path, not by label`() {
        // The display hides the path here; the value must not - a rule filing into Trash on a
        // Dovecot server has to say INBOX.Trash.
        val trash = imap("INBOX.Trash", role = "trash")
        assertEquals("INBOX.Trash", mailboxFilePath(trash, listOf(trash)))
    }

    @Test fun `IMAP, not reachable today - the value keeps what the display filters out`() {
        // A path sent to a server must reproduce the folder the server named, byte for byte;
        // only the label is sanitized.
        val spoof = Mailbox(id = "INBOX.pro\u202EjectA.Done", name = "Done")
        assertEquals("INBOX.pro\u202EjectA.Done", mailboxFilePath(spoof, listOf(spoof)))
    }
}
