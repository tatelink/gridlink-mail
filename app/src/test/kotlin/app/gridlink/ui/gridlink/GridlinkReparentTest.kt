package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6d's drag, as a pure question about a tree.
 *
 * 🔴 [mayReparent] is under test here rather than in a UI test on purpose: it is asked on every
 * frame of the gesture and it is what decides whether a row under the finger lights up, so the
 * outline and the drop are the same predicate and can be proved without a pointer.
 *
 * 🔴 The refusal that MUST hold is the subtree one. [moveFolder] is a remove followed by an add, so
 * dropping a folder into its own descendant would delete the destination along with the folder and
 * the add would find no parent — the branch would silently vanish from the tree. `losing the folder`
 * below is the regression test for that, and it is the reason the check lives inside [moveFolder]
 * rather than being left to callers.
 */
class GridlinkReparentTest {

    private fun folder(
        id: String,
        role: GridlinkFolderRole = GridlinkFolderRole.USER,
        children: List<GridlinkFolder> = emptyList(),
        mayRename: Boolean = role == GridlinkFolderRole.USER,
    ) = GridlinkFolder(
        id = id,
        name = id,
        role = role,
        children = children,
        mayRename = mayRename,
    )

    /** inbox > (ops > (456, belmont), people); plus a top-level trash and a top-level user folder. */
    private val tree = listOf(
        folder(
            "inbox",
            GridlinkFolderRole.INBOX,
            listOf(
                folder("ops", children = listOf(folder("456"), folder("belmont"))),
                folder("people"),
            ),
        ),
        folder("trash", GridlinkFolderRole.TRASH),
        // A user folder that already lives at the top level, so the "already in this parent" rule
        // can be tested at the root without a role mailbox short-circuiting it first.
        folder("misc"),
    )

    // -----------------------------------------------------------------------------------------
    // mayReparent
    // -----------------------------------------------------------------------------------------

    @Test fun `a user folder may move into a sibling`() {
        assertTrue(tree.mayReparent("456", "people"))
    }

    @Test fun `a user folder may move out to the top level`() {
        assertTrue(tree.mayReparent("456", null))
    }

    @Test fun `a folder may not be dropped into its own subtree`() {
        // Both a direct child and a grandchild, because "is it below me" is the check that gets
        // simplified to "is it my child" by someone who has only ever tested one level.
        assertFalse(tree.mayReparent("ops", "456"))
        val deep = listOf(folder("a", children = listOf(folder("b", children = listOf(folder("c"))))))
        assertFalse(deep.mayReparent("a", "c"))
    }

    @Test fun `a folder may not be dropped onto itself`() {
        assertFalse(tree.mayReparent("456", "456"))
    }

    @Test fun `a folder may not be dropped back into the parent it is already in`() {
        assertFalse(tree.mayReparent("456", "ops"))
        // The top-level case: a root's ancestor list is empty, so "the parent it is already in" is
        // null, which is exactly the id of the top level. Without that, dragging a root folder onto
        // the root's own New folder row would fire a Mailbox/set that changes nothing — and servers
        // that reject a no-op update would answer with an error for a gesture that did nothing.
        assertFalse(tree.mayReparent("misc", null))
    }

    @Test fun `a role mailbox may not be moved`() {
        // 🔴 mayRename IS the move right. RFC 8621 §2 defines it as "may change the name or
        // parentId", so there is no separate mayMove to consult and a mailbox that refuses a rename
        // refuses a reparent for the same reason.
        assertFalse(tree.mayReparent("trash", "people"))
    }

    @Test fun `a user folder the server has locked may not be moved`() {
        val locked = listOf(folder("shared", mayRename = false), folder("mine"))
        assertFalse(locked.mayReparent("shared", "mine"))
        assertTrue(locked.mayReparent("mine", "shared"))
    }

    @Test fun `a name already taken at the destination refuses the drop`() {
        // JMAP's uniqueness rule is per-parent and case-insensitive, the same rule the rename dialog
        // spells out under its field. Here there is no field to spell it out under, so the target
        // simply never lights up.
        val clash = listOf(
            folder("a", children = listOf(GridlinkFolder(id = "a-notes", name = "Notes"))),
            folder("b", children = listOf(GridlinkFolder(id = "b-notes", name = "notes"))),
        )
        assertFalse(clash.mayReparent("a-notes", "b"))
        assertTrue(clash.mayReparent("a-notes", null))
    }

    @Test fun `an unknown folder and an unknown destination are both refused`() {
        // Guards a drag surviving a folder list that a sync replaced underneath it.
        assertFalse(tree.mayReparent("nope", "people"))
        assertFalse(tree.mayReparent("456", "nope"))
    }

    // -----------------------------------------------------------------------------------------
    // moveFolder
    // -----------------------------------------------------------------------------------------

    @Test fun `a move takes the folder and its children with it`() {
        val moved = tree.moveFolder("ops", null)
        assertNull(moved.findFolder("inbox")?.children?.firstOrNull { it.id == "ops" })
        val ops = moved.findFolder("ops")
        assertEquals(listOf("456", "belmont"), ops?.children?.map { it.id })
        // Appended at the destination rather than sorted in, same as a create.
        assertEquals("ops", moved.last().id)
    }

    @Test fun `a move into another branch lands at the end of it`() {
        val moved = tree.moveFolder("456", "people")
        assertEquals(listOf("456"), moved.findFolder("people")?.children?.map { it.id })
        assertEquals(listOf("belmont"), moved.findFolder("ops")?.children?.map { it.id })
        // The whole tree still has the same folders in it, one of them somewhere else.
        assertEquals(tree.flatten().size, moved.flatten().size)
    }

    @Test fun `a refused move returns the tree untouched and loses nothing`() {
        // 🔴 The silent-deletion guard. Remove-then-add would take "456" out of the tree along with
        // its new home, leaving an add with no parent to add to and a tree missing the branch.
        val same = tree.moveFolder("ops", "456")
        assertSame(tree, same)
        assertEquals(tree.flatten().map { it.id }, same.flatten().map { it.id })
    }

    @Test fun `every refusal mayReparent names leaves the tree alone`() {
        listOf(
            "ops" to "456",
            "456" to "456",
            "456" to "ops",
            "trash" to "people",
            "misc" to null,
            "nope" to "people",
        ).forEach { (id, parent) ->
            assertSame("$id -> $parent should have been refused", tree, tree.moveFolder(id, parent))
        }
    }

    // -----------------------------------------------------------------------------------------
    // The real fixture
    // -----------------------------------------------------------------------------------------

    @Test fun `the sample tree can move a store folder under vendors and back out`() {
        val top = GridlinkSampleTree.mailboxes
        assertTrue(top.mayReparent("ops-456", "vendors"))
        val moved = top.moveFolder("ops-456", "vendors")
        assertTrue(moved.findFolder("vendors")?.children?.any { it.id == "ops-456" } == true)
        assertTrue(moved.findFolder("ops")?.children?.none { it.id == "ops-456" } == true)
        assertEquals(top.flatten().size, moved.flatten().size)

        val out = moved.moveFolder("ops-456", null)
        assertEquals("ops-456", out.last().id)
        assertEquals(top.flatten().size, out.flatten().size)
    }

    @Test fun `none of the sample's six role mailboxes may be dragged anywhere`() {
        val top = GridlinkSampleTree.mailboxes
        top.flatten()
            .filter { it.role != GridlinkFolderRole.USER }
            .forEach { role ->
                assertFalse(
                    "${role.id} must not be draggable",
                    top.mayReparent(role.id, "people") || top.mayReparent(role.id, null),
                )
            }
    }
}
