package app.gridlink.core.data.mail

import app.gridlink.core.data.db.EmailRetentionRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a background IMAP pass removes after re-reading a folder's newest window
 * ([imapWindowEvictions]). Before this the pass removed nothing while still recording the folder as
 * synced, so mail moved away on another device stayed on this one until something else changed the
 * folder's counters.
 */
class ImapPushEvictionTest {

    private fun row(id: String, sortKey: Long) = EmailRetentionRow(id = id, sortKey = sortKey)

    private fun evict(
        cached: List<EmailRetentionRow>,
        window: List<EmailRetentionRow>,
        whole: Boolean,
        spare: Set<String> = emptySet(),
    ) = imapWindowEvictions(cached, window, coversWholeFolder = whole, spareIds = spare)

    @Test fun mailMovedAwayOnAnotherDeviceLeavesTheList() {
        // The reported case: four in the Inbox, one moved to Trash elsewhere, the window now has three.
        val cached = listOf(row("a", 400), row("moved", 300), row("b", 200), row("c", 100))
        val window = listOf(row("a", 400), row("b", 200), row("c", 100))
        assertEquals(listOf("moved"), evict(cached, window, whole = true))
    }

    @Test fun inADeepFolderOnlyTheWindowsRangeIsJudged() {
        // The user scrolled back past the window; the pass said nothing about that mail.
        val cached = listOf(row("a", 400), row("moved", 300), row("b", 200), row("pagedIn", 50))
        val window = listOf(row("a", 400), row("b", 200))
        assertEquals(listOf("moved"), evict(cached, window, whole = false))
    }

    @Test fun aFolderEmptiedElsewhereEmptiesHere() {
        // EXISTS 0 fits inside any limit, so an empty window is the whole (empty) folder.
        val cached = listOf(row("a", 400), row("b", 200))
        assertEquals(listOf("a", "b"), evict(cached, emptyList(), whole = true))
    }

    @Test fun anEmptyPartialWindowJudgesNothing() {
        val cached = listOf(row("a", 400))
        assertEquals(emptyList<String>(), evict(cached, emptyList(), whole = false))
    }

    @Test fun aPartialWindowWithNoDatedRowHasNoHorizon() {
        // An undated row would otherwise set the horizon to 0 and judge every scrolled-back row.
        val cached = listOf(row("u", 0), row("pagedIn", 50))
        val window = listOf(row("u", 0))
        assertEquals(emptyList<String>(), evict(cached, window, whole = false))
    }

    @Test fun anUndatedWindowRowDoesNotLowerTheHorizon() {
        val cached = listOf(row("a", 400), row("u", 0), row("b", 200), row("pagedIn", 50))
        val window = listOf(row("a", 400), row("u", 0), row("b", 200))
        assertEquals(emptyList<String>(), evict(cached, window, whole = false))
    }

    @Test fun aLocallyMutatedRowIsSpared() {
        // An optimistic Undo the server has not caught up on yet.
        val cached = listOf(row("a", 400), row("restored", 300))
        val window = listOf(row("a", 400))
        assertEquals(emptyList<String>(), evict(cached, window, whole = true, spare = setOf("restored")))
    }
}
