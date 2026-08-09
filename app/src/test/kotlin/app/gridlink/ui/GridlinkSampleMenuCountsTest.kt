package app.gridlink.ui

import app.gridlink.ui.gridlink.GRIDLINK_SAMPLE_MENU_COUNTS
import app.gridlink.ui.gridlink.GridlinkMenuItem
import app.gridlink.ui.gridlink.GridlinkSample
import app.gridlink.ui.gridlink.GridlinkSampleFolders
import app.gridlink.ui.gridlink.gridlinkSampleScheduled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * The drawer's counts have to be backed by rows somebody can go and look at.
 *
 * 🔴 This exists because they were not. `GRIDLINK_SAMPLE_MENU_COUNTS` declared "Drafts 4" as a
 * literal while the sample held no drafts at all, so the menu offered four unsent messages and the
 * Drafts folder one tap away said "Nothing in Drafts". Nothing failed, nothing logged, and the only
 * way to find it was to tap the row in front of Tate. Counted-not-typed fixes the Drafts side;
 * this fixes the class of bug, including for Scheduled, whose count is still a literal for a reason
 * spelled out where it is declared.
 */
class GridlinkSampleMenuCountsTest {

    @Test fun `the drafts count is the number of sample drafts`() {
        assertEquals(
            GridlinkSample.draftMessages.size,
            GRIDLINK_SAMPLE_MENU_COUNTS[GridlinkMenuItem.DRAFTS],
        )
    }

    @Test fun `the scheduled count is the number of sample scheduled sends`() {
        assertEquals(
            gridlinkSampleScheduled(ZonedDateTime.now()).items.size,
            GRIDLINK_SAMPLE_MENU_COUNTS[GridlinkMenuItem.SCHEDULED],
        )
    }

    @Test fun `the drafts folder holds exactly what the drawer counts`() {
        assertEquals(
            GRIDLINK_SAMPLE_MENU_COUNTS[GridlinkMenuItem.DRAFTS],
            GridlinkSampleFolders.messagesIn("drafts").size,
        )
    }

    /**
     * The tap that resumes a draft reads [app.gridlink.ui.gridlink.GridlinkMessage.body] straight
     * into the composer, so a draft with an empty body would open a blank one. `bodyFor` throws on
     * a miss, which covers a *missing* body; this covers a present but empty one.
     */
    @Test fun `every sample draft carries a body for the composer to open`() {
        GridlinkSample.draftMessages.forEach { draft ->
            assertTrue(
                "Draft '${draft.id}' has no body, so resuming it would open an empty composer.",
                draft.body.isNotBlank(),
            )
        }
    }

    /**
     * 🔴 Mail you have not sent is not mail that arrived. The sample Inbox is the whole message
     * pool, so a draft that leaked into [GridlinkSample.messages] would be filed as received.
     */
    @Test fun `no draft is in the inbox pool`() {
        val inboxIds = GridlinkSampleFolders.messagesIn("inbox").map { it.id }.toSet()
        GridlinkSample.draftMessages.forEach { draft ->
            assertTrue(
                "Draft '${draft.id}' is in the Inbox, which means it was added to " +
                    "GridlinkSample.messages rather than to draftMessages.",
                draft.id !in inboxIds,
            )
        }
    }

    /** A draft is not unread, so the folder tree must not badge Drafts. */
    @Test fun `drafts carries no unread badge`() {
        assertEquals(0, GridlinkSampleFolders.unreadIn("drafts"))
    }
}
