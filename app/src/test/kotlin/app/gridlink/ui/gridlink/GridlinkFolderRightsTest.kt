package app.gridlink.ui.gridlink

import app.gridlink.core.jmap.model.Mailbox
import app.gridlink.core.jmap.model.MailboxRights
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a folder is allowed to do, which is the stricter of two answers and never the looser one.
 *
 * 🔴 The rule these pin down is asymmetric on purpose: the server's `myRights` can only ever REMOVE
 * an action, and a missing `myRights` removes nothing. Both halves are easy to "tidy" into a single
 * `right == true`, and either direction of that tidying breaks a real account — one locks every IMAP
 * user out of their own folders, the other offers a rename on a mailbox the server will refuse.
 */
class GridlinkFolderRightsTest {

    private fun mailbox(
        id: String,
        role: String? = null,
        rights: MailboxRights? = null,
    ) = Mailbox(id = id, name = id, role = role, myRights = rights)

    private fun folder(vararg mailboxes: Mailbox) =
        GridlinkFolderMapping.tree(mailboxes.toList()).single()

    @Test fun `a user folder the server said nothing about keeps both actions`() {
        // The pre-v21 row, and every mailbox on the IMAP path, where there is no such property to
        // fetch. Null is "never asked", so the answer is the one the app gave before this existed.
        val f = folder(mailbox("work"))
        assertTrue(f.mayRename)
        assertTrue(f.mayDelete)
    }

    @Test fun `an explicit false takes exactly the one action it names`() {
        // The shared or delegated mailbox: an ordinary user folder the account may read and may not
        // touch. Nothing else in the app can know that.
        val f = folder(mailbox("team", rights = MailboxRights(mayRename = false)))
        assertFalse(f.mayRename)
        assertTrue(f.mayDelete)
    }

    @Test fun `a right the server withholds on both counts leaves no long-press at all`() {
        val f = folder(mailbox("team", rights = MailboxRights(mayRename = false, mayDelete = false)))
        assertFalse(f.hasActions)
    }

    @Test fun `a role mailbox is refused even when the server grants the right`() {
        // 🔴 Local policy wins. The app files mail into the six by role, and a renamed Trash is a
        // Trash it can no longer find — so a permissive server cannot talk it into offering one.
        val f = folder(mailbox("t", role = "trash", rights = MailboxRights(true, true)))
        assertFalse(f.mayRename)
        assertFalse(f.mayDelete)
        assertTrue(f.role == GridlinkFolderRole.TRASH)
    }

    @Test fun `a role this app has no glyph for keeps the glyph and loses the actions`() {
        // The fallthrough that used to be the bug: `all` maps to USER because there is no icon for
        // "All Mail", and USER used to mean renameable. The glyph was never the problem.
        val f = folder(mailbox("all", role = "all"))
        assertTrue(f.role == GridlinkFolderRole.USER)
        assertFalse(f.hasActions)
    }

    @Test fun `every unmapped server role is refused, not just the one`() {
        for (role in listOf("all", "flagged", "important", "subscribed")) {
            val f = folder(mailbox(role, role = role))
            assertFalse("role=$role", f.mayRename)
            assertFalse("role=$role", f.mayDelete)
        }
    }

    @Test fun `a role that is blank rather than absent is still a user folder`() {
        // Servers do send `""` for "no role". Treating it as a role name would take the actions off
        // every folder the user made.
        val f = folder(mailbox("work", role = ""))
        assertTrue(f.hasActions)
    }

    @Test fun `children carry their own rights, not their parent's`() {
        val tree = GridlinkFolderMapping.tree(
            listOf(
                mailbox("work"),
                Mailbox(
                    id = "shared",
                    name = "shared",
                    parentId = "work",
                    myRights = MailboxRights(mayRename = false, mayDelete = false),
                ),
            ),
        )
        val work = tree.single()
        assertTrue(work.mayRename)
        assertFalse(work.children.single().hasActions)
    }

    // ---- the roleless folder the app archives into ----------------------------------------------

    @Test fun `a roleless Archive the app files into may not be renamed or deleted`() {
        // 🔴 Brandon's own account, read off the device: Stalwart gives "Archive" no role at all and
        // grants both rights, so all three rules above said "ordinary user folder" about the folder
        // holding 231 archived messages. The repository resolves the archive destination by NAME
        // when no role exists, so the name is the whole link and renaming it breaks archiving
        // silently.
        val f = folder(Mailbox(id = "h", name = "Archive", role = null))
        assertFalse(f.mayRename)
        assertFalse(f.mayDelete)
    }

    @Test fun `the name match is case and locale insensitive`() {
        for (name in listOf("archive", "ARCHIVES", "Archivio", "Архив")) {
            val f = folder(Mailbox(id = "h", name = name, role = null))
            assertFalse(name, f.hasActions)
        }
    }

    @Test fun `a folder merely named Archive is fair game once a real archive role exists`() {
        // With a role-bearing archive present, nothing resolves by name, so a user's own folder that
        // happens to be called "Archive" is just a folder. Protecting it would take a rename away
        // for no reason at all.
        val tree = GridlinkFolderMapping.tree(
            listOf(
                Mailbox(id = "real", name = "Archive", role = "archive"),
                Mailbox(id = "mine", name = "Archives", role = null),
            ),
        )
        assertFalse(tree.first { it.id == "real" }.hasActions)
        assertTrue(tree.first { it.id == "mine" }.hasActions)
    }

    @Test fun `the top-level Archive is the protected one, not a nested namesake`() {
        // Same pick the repository makes. Protecting the nested one would leave the folder actually
        // being archived into renameable, which is the whole bug.
        val tree = GridlinkFolderMapping.tree(
            listOf(
                Mailbox(id = "top", name = "Archive", role = null),
                Mailbox(id = "work", name = "work", role = null),
                Mailbox(id = "nested", name = "Archive", parentId = "work", role = null),
            ),
        )
        assertFalse(tree.first { it.id == "top" }.hasActions)
        assertTrue(tree.first { it.id == "work" }.children.single().hasActions)
    }
}
