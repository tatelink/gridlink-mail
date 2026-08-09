package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Move picker offers, which is the whole tree flattened and nothing dropped.
 *
 * 🔴 The rule under test is the one that is easy to "simplify" away later: [gridlinkMoveTargets]
 * recurses THROUGH a folder even when that folder is one the picker will refuse. Drafts and the
 * mailbox you are standing in are drawn greyed out, but their children are ordinary user folders and
 * perfectly good destinations. Skipping the branch would remove real folders from the list because
 * of what their parent happened to be, and it would do it silently.
 */
class GridlinkMoveTargetsTest {

    private fun folder(
        id: String,
        role: GridlinkFolderRole = GridlinkFolderRole.USER,
        children: List<GridlinkFolder> = emptyList(),
    ) = GridlinkFolder(id = id, name = id, role = role, children = children)

    @Test fun `an empty tree offers nothing`() {
        assertTrue(gridlinkMoveTargets(emptyList()).isEmpty())
    }

    @Test fun `a flat tree keeps its order and sits at depth zero`() {
        val rows = gridlinkMoveTargets(listOf(folder("a"), folder("b"), folder("c")))
        assertEquals(listOf("a", "b", "c"), rows.map { it.folder.id })
        assertTrue(rows.all { it.depth == 0 })
    }

    @Test fun `children follow their parent and are indented one level per depth`() {
        val tree = listOf(
            folder("top", children = listOf(folder("mid", children = listOf(folder("deep"))))),
            folder("next"),
        )
        val rows = gridlinkMoveTargets(tree)
        // Parents before children, and the second top-level folder after the whole first branch:
        // this is the reading order of the folder tree, and the picker is a list of the same names.
        assertEquals(listOf("top", "mid", "deep", "next"), rows.map { it.folder.id })
        assertEquals(listOf(0, 1, 2, 0), rows.map { it.depth })
    }

    @Test fun `a subfolder of the inbox is still offered`() {
        // The Inbox itself is what the picker refuses (it is where the messages already are), and
        // the child is the thing that must survive that.
        val rows = gridlinkMoveTargets(
            listOf(folder("inbox", GridlinkFolderRole.INBOX, listOf(folder("receipts")))),
        )
        assertEquals(listOf("inbox", "receipts"), rows.map { it.folder.id })
    }

    @Test fun `a subfolder of drafts is still offered`() {
        val rows = gridlinkMoveTargets(
            listOf(folder("drafts", GridlinkFolderRole.DRAFTS, listOf(folder("half-written")))),
        )
        assertEquals(listOf("drafts", "half-written"), rows.map { it.folder.id })
    }

    @Test fun `the sample tree flattens to more rows than it has top-level folders`() {
        // Guards the real fixture rather than a hand-built one: the sample has nesting, and a
        // flattener that quietly stopped recursing would still pass every test above if they were
        // the only ones here.
        val top = GridlinkSampleTree.mailboxes
        val rows = gridlinkMoveTargets(top)
        assertTrue(rows.size > top.size)
        assertEquals(top.map { it.id }, rows.filter { it.depth == 0 }.map { it.folder.id })
    }
}
