package app.gridlink.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codeberg #56: one arrival must announce itself once. A group where the summary AND its
 * children alert gives two banners, two sounds and a status icon that flashes and goes — but a
 * blanket "children stay quiet" makes a SINGLE message silent, because a single message gets no
 * summary to speak for it. The rule that decides which of the two speaks is [summaryShownFor],
 * fed by [liveChildIdsAfter]; both are tested here rather than left inside the builder.
 */
class GroupAlertTest {

    @Test fun `a single message announces itself`() {
        // No summary is posted for one child, so the child is what the user hears.
        assertFalse(Notifications.summaryShownFor(1))
    }

    @Test fun `nothing left on screen is not a summary either`() {
        assertFalse(Notifications.summaryShownFor(0))
    }

    @Test fun `from two messages up the summary speaks`() {
        assertTrue(Notifications.summaryShownFor(2))
        assertTrue(Notifications.summaryShownFor(7))
    }

    @Test fun `the first message of an empty group stands alone`() {
        val live = Notifications.liveChildIdsAfter(
            active = emptySet(),
            accountId = "acct-a",
            cleared = emptyList(),
            added = listOf("M1"),
        )
        assertEquals(1, live.size)
        assertFalse(Notifications.summaryShownFor(live.size))
    }

    @Test fun `two messages arriving together are announced by the summary`() {
        val live = Notifications.liveChildIdsAfter(emptySet(), "acct-a", emptyList(), listOf("M1", "M2"))
        assertEquals(2, live.size)
        assertTrue(Notifications.summaryShownFor(live.size))
    }

    @Test fun `a message joining one already on screen is announced by the summary`() {
        val active = setOf(Notifications.childId("acct-a", "M1"))
        val live = Notifications.liveChildIdsAfter(active, "acct-a", emptyList(), listOf("M2"))
        assertEquals(2, live.size)
        assertTrue(Notifications.summaryShownFor(live.size))
    }

    @Test fun `a message that arrives as another is cleared stands alone`() {
        // M1 was read on another device: its notification goes as M2's is posted, so M2 is
        // alone once the pass is done and must be the one that makes the noise.
        val active = setOf(Notifications.childId("acct-a", "M1"))
        val live = Notifications.liveChildIdsAfter(active, "acct-a", listOf("M1"), listOf("M2"))
        assertEquals(setOf(Notifications.childId("acct-a", "M2")), live)
        assertFalse(Notifications.summaryShownFor(live.size))
    }

    @Test fun `a notification posted by the previous build is cleared too`() {
        // Pre-update children carry the bare message id; it must not be counted as still live.
        val live = Notifications.liveChildIdsAfter(setOf("M1".hashCode()), "acct-a", listOf("M1"), listOf("M2"))
        assertEquals(1, live.size)
    }

    @Test fun `accounts are counted apart`() {
        // Account B's live notifications never reach A's set, so an arrival alone in A is
        // announced by itself even while B has a group of its own (each group has its summary).
        val active = setOf(Notifications.childId("acct-a", "M1"))
        val liveA = Notifications.liveChildIdsAfter(active, "acct-a", emptyList(), listOf("M2"))
        val liveB = Notifications.liveChildIdsAfter(emptySet(), "acct-b", emptyList(), listOf("M9"))
        assertTrue(Notifications.summaryShownFor(liveA.size))
        assertFalse(Notifications.summaryShownFor(liveB.size))
    }

    @Test fun `re-posting a message already on screen does not conjure a summary`() {
        val active = setOf(Notifications.childId("acct-a", "M1"))
        val live = Notifications.liveChildIdsAfter(active, "acct-a", emptyList(), listOf("M1"))
        assertEquals(1, live.size)
        assertFalse(Notifications.summaryShownFor(live.size))
    }
}
