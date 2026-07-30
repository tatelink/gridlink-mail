package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a folder sits, as a picker row says it (#109) and as a server-side rule must name it.
 *
 * The reported case: "ProjectA.Done" and "ProjectB.Done" both read "Done" in the move-to-folder
 * picker, so there is no telling which is which — while the drawer's tree has always shown it.
 */
class MailboxPathTest {

    /** An IMAP folder: the id IS the path the server named, the name only its last segment. */
    private fun imap(path: String, delimiter: Char = '.', role: String? = null) =
        Mailbox(id = path, name = path.substringAfterLast(delimiter), role = role)

    /** A JMAP folder: an opaque id, the hierarchy carried by parentId. */
    private fun jmap(id: String, name: String, parentId: String? = null, role: String? = null) =
        Mailbox(id = id, name = name, parentId = parentId, role = role)

    // ---- what the picker shows -------------------------------------------------------------

    @Test fun `an IMAP dot path names the parents under the folder`() {
        val done = imap("INBOX.ProjectA.Done")
        assertEquals("Done", done.name)
        assertEquals("INBOX / ProjectA", mailboxPathLabel(done, listOf(done)))
    }

    @Test fun `two folders sharing a leaf are told apart`() {
        // The reporter's own example, and the only thing the row exists for.
        val a = imap("INBOX.ProjectA.Done")
        val b = imap("INBOX.ProjectB.Done")
        val folders = listOf(a, b)
        assertEquals(a.name, b.name)
        assertNotEquals(mailboxPathLabel(a, folders), mailboxPathLabel(b, folders))
    }

    @Test fun `an IMAP slash path reads the same way`() {
        val q1 = imap("Work/2026/Q1", delimiter = '/')
        assertEquals("Work / 2026", mailboxPathLabel(q1, listOf(q1)))
    }

    @Test fun `a JMAP hierarchy is read from parentId`() {
        val work = jmap("mb1", "Work")
        val year = jmap("mb2", "2026", parentId = "mb1")
        val q1 = jmap("mb3", "Q1", parentId = "mb2")
        assertEquals("Work / 2026", mailboxPathLabel(q1, listOf(work, year, q1)))
    }

    @Test fun `a folder at the root has no second line`() {
        // Not an empty string: the row must not reserve a blank line under the name.
        val root = imap("Bills")
        assertNull(mailboxPathLabel(root, listOf(root)))
        assertNull(mailboxPathLabel(jmap("mb1", "Bills"), listOf(jmap("mb1", "Bills"))))
    }

    @Test fun `a standard folder keeps its localized name alone`() {
        // Dovecot files Trash under INBOX; "Trash" is unique in the account anyway, and the
        // raw namespace prefix under a translated name is noise, not information.
        val trash = imap("INBOX.Trash", role = "trash")
        assertNull(mailboxPathLabel(trash, listOf(trash)))
        val sent = jmap("mb2", "Sent Items", parentId = "mb1", role = "sent")
        assertNull(mailboxPathLabel(sent, listOf(jmap("mb1", "Whatever"), sent)))
    }

    @Test fun `an unrecognised role is a custom folder and keeps its path`() {
        // mailboxDisplayName has no label for it either, so it shows its raw name.
        val templates = imap("INBOX.Work.Templates", role = "templates")
        assertEquals("INBOX / Work", mailboxPathLabel(templates, listOf(templates)))
    }

    @Test fun `three levels deep, every ancestor is named`() {
        val deep = imap("INBOX.Clients.Acme.Invoices.2026")
        assertEquals("INBOX / Clients / Acme / Invoices", mailboxPathLabel(deep, listOf(deep)))
    }

    @Test fun `a path too long to fit loses its OUTERMOST folders, never the nearest`() {
        // Eliding at the end would cut off the segment that tells one "Done" from another —
        // the reported bug, one line lower.
        val deep = imap("INBOX.Clients.Very Long Client Name Indeed.Archived Threads.Done")
        val label = mailboxPathLabel(deep, listOf(deep))!!
        assertTrue(label, label.startsWith("…"))
        assertTrue(label, label.endsWith("Archived Threads"))
        assertTrue(label, label.length <= 40)
    }

    @Test fun `bidi overrides are stripped from a displayed path`() {
        // The IMAP layer filters the leaf it expects to show; the path was an identifier
        // until this row started displaying it (#101).
        // U+202E is a right-to-left override, written escaped so this file stays readable.
        val spoof = Mailbox(id = "INBOX.pro\u202EjectA.Done", name = "Done")
        assertEquals("INBOX / projectA", mailboxPathLabel(spoof, listOf(spoof)))
    }

    @Test fun `an opaque id that merely contains a dot invents no path`() {
        // A JMAP id is not a path; splitting one would show a hierarchy nobody has.
        val opaque = jmap("a1.b2.c3", "Bills")
        assertNull(mailboxPathLabel(opaque, listOf(opaque)))
    }

    @Test fun `a folder whose own name holds the delimiter is not cut in the middle`() {
        // "Foo/Bar" on a dot-delimited server: the delimiter is the character before the leaf.
        val odd = Mailbox(id = "INBOX.Foo/Bar", name = "Foo/Bar")
        assertEquals("INBOX", mailboxPathLabel(odd, listOf(odd)))
    }

    @Test fun `a parent the server hides still shows in the path`() {
        // An IMAP \Noselect parent is dropped from the folder list; the child still lives in it.
        val child = imap("INBOX.ProjectA.Done")
        assertEquals("INBOX / ProjectA", mailboxPathLabel(child, listOf(child)))
    }

    @Test fun `a cycle in parentId does not hang the picker`() {
        // Two folders each claiming the other as parent: the walk stops the first time it
        // meets a folder it has already climbed through, and shows what it got.
        val a = jmap("mb1", "A", parentId = "mb2")
        val b = jmap("mb2", "B", parentId = "mb1")
        assertEquals("A", mailboxPathLabel(b, listOf(a, b)))
        assertEquals("B", mailboxPathLabel(a, listOf(a, b)))
    }

    // ---- what a server-side rule must name ------------------------------------------------

    @Test fun `an IMAP rule names the whole path, in the server's own delimiter`() {
        val done = imap("INBOX.ProjectA.Done")
        assertEquals("INBOX.ProjectA.Done", mailboxFilePath(done, listOf(done)))
        val q1 = imap("Work/2026/Q1", delimiter = '/')
        assertEquals("Work/2026/Q1", mailboxFilePath(q1, listOf(q1)))
    }

    @Test fun `a JMAP rule names the chain of folder names`() {
        val work = jmap("mb1", "Work")
        val year = jmap("mb2", "2026", parentId = "mb1")
        val q1 = jmap("mb3", "Q1", parentId = "mb2")
        assertEquals("Work/2026/Q1", mailboxFilePath(q1, listOf(work, year, q1)))
    }

    @Test fun `a folder at the root is named by itself`() {
        assertEquals("Bills", mailboxFilePath(imap("Bills"), listOf(imap("Bills"))))
        val jmapRoot = jmap("mb1", "Bills")
        assertEquals("Bills", mailboxFilePath(jmapRoot, listOf(jmapRoot)))
    }

    @Test fun `a standard folder is still named by its path, never by its label`() {
        // The display hides the path here; the value must not — a rule filing into Trash on a
        // Dovecot server has to say INBOX.Trash.
        val trash = imap("INBOX.Trash", role = "trash")
        assertEquals("INBOX.Trash", mailboxFilePath(trash, listOf(trash)))
    }

    @Test fun `the value keeps what the display filters out`() {
        // A path sent to the server must reproduce the folder the server named, byte for byte;
        // only the label is sanitized.
        val spoof = Mailbox(id = "INBOX.pro\u202EjectA.Done", name = "Done")
        assertEquals("INBOX.pro\u202EjectA.Done", mailboxFilePath(spoof, listOf(spoof)))
    }

    @Test fun `an opaque id is never mistaken for a path`() {
        val opaque = jmap("a1.b2", "Bills")
        assertEquals("Bills", mailboxFilePath(opaque, listOf(opaque)))
    }
}
