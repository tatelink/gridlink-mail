package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The list screen's Back rule (Codeberg #86). Back is global: the two failure modes are
 * leaving the app while a mode is still open, and trapping the user on a screen Back can
 * no longer leave — so both ends of the rule are pinned here.
 */
class InboxBackTest {

    @Test fun `search closes instead of leaving the app`() {
        assertEquals(
            InboxBackAction.CLOSE_SEARCH,
            inboxBackAction(selectionActive = false, searching = true, atInbox = true),
        )
    }

    @Test fun `search inside a folder closes the search first`() {
        assertEquals(
            InboxBackAction.CLOSE_SEARCH,
            inboxBackAction(selectionActive = false, searching = true, atInbox = false),
        )
    }

    @Test fun `once the search is closed a folder still returns to the inbox`() {
        assertEquals(
            InboxBackAction.SHOW_INBOX,
            inboxBackAction(selectionActive = false, searching = false, atInbox = false),
        )
    }

    @Test fun `a selection outranks the search`() {
        assertEquals(
            InboxBackAction.CLEAR_SELECTION,
            inboxBackAction(selectionActive = true, searching = true, atInbox = true),
        )
    }

    @Test fun `a selection outranks the folder`() {
        assertEquals(
            InboxBackAction.CLEAR_SELECTION,
            inboxBackAction(selectionActive = true, searching = false, atInbox = false),
        )
    }

    @Test fun `a plain inbox still leaves the app`() {
        // The list is the start destination: with no mode open, Back must NOT be swallowed,
        // or the user is trapped in the app.
        assertEquals(
            InboxBackAction.LEAVE_APP,
            inboxBackAction(selectionActive = false, searching = false, atInbox = true),
        )
    }

    @Test fun `exactly one action applies to any state`() {
        // The screen binds one handler per action; overlapping conditions would make Back
        // depend on composition order.
        val seen = mutableListOf<InboxBackAction>()
        listOf(true, false).forEach { selection ->
            listOf(true, false).forEach { searching ->
                listOf(true, false).forEach { atInbox ->
                    seen += inboxBackAction(selection, searching, atInbox)
                }
            }
        }
        assertEquals(8, seen.size)
        assertEquals(InboxBackAction.entries.toSet(), seen.toSet())
    }
}
