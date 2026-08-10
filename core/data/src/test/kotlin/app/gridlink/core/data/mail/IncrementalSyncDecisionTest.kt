package app.gridlink.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a refresh is allowed to skip (RFC 7162 CONDSTORE).
 *
 * The whole point of separating this decision out is that it can be stated exhaustively: three
 * outcomes, every input that leads to each. Two of them read LESS than the app used to, so a wrong
 * answer here does not throw — it means mail silently never appears, or a folder is quietly
 * emptied. Everything uncertain must land on [ImapSyncPlan.Full], and each case below says which
 * uncertainty it is.
 */
class IncrementalSyncDecisionTest {

    /** A folder last synced at modseq 100, holding 40 messages, next uid 41. */
    private val recorded = ImapSyncPoint(uidValidity = 1L, highestModSeq = 100L, uidNext = 41L, messageCount = 40)

    private fun plan(
        stored: ImapSyncPoint? = recorded,
        uidValidity: Long = 1L,
        modSeq: Long = 100L,
        uidNext: Long = 41L,
        exists: Int = 40,
    ) = ImapSyncDecision.plan(stored, uidValidity, modSeq, uidNext, exists)

    @Test fun `nothing moved means the folder is not read at all`() {
        assertEquals(ImapSyncPlan.Unchanged, plan())
    }

    @Test fun `flags moved and nothing else means a delta`() {
        assertEquals(ImapSyncPlan.FlagsOnly(sinceModSeq = 100L), plan(modSeq = 140L))
    }

    /** No CONDSTORE, or a NOMODSEQ folder: there is no watermark to reason with. */
    @Test fun `a server reporting no modseq is read in full`() {
        assertEquals(ImapSyncPlan.Full, plan(modSeq = 0L))
    }

    /** First sight of the folder, and the state of every install the day this ships. */
    @Test fun `a folder never synced before is read in full`() {
        assertEquals(ImapSyncPlan.Full, plan(stored = null))
    }

    /** Recorded under a version that did not store one, or under a NOMODSEQ folder. */
    @Test fun `a stored point with no watermark is read in full`() {
        assertEquals(ImapSyncPlan.Full, plan(stored = recorded.copy(highestModSeq = null)))
        assertEquals(ImapSyncPlan.Full, plan(stored = recorded.copy(highestModSeq = 0L)))
    }

    /**
     * A renumbering (RFC 3501 §2.3.1.1) resets the modseq counter too, so the stored watermark
     * is not merely stale, it belongs to a different sequence of numbers.
     */
    @Test fun `a renumbered folder is read in full`() {
        assertEquals(ImapSyncPlan.Full, plan(uidValidity = 9L))
        assertEquals(ImapSyncPlan.Full, plan(uidValidity = 9L, modSeq = 140L))
    }

    /**
     * HIGHESTMODSEQ is monotonic (RFC 7162 §3.1.2). One that went BACKWARDS means the server is
     * not the same server, or not the same folder, or was restored from a backup — a delta
     * "since" a future watermark returns nothing, which would look exactly like an idle folder.
     */
    @Test fun `a watermark that went backwards is read in full`() {
        assertEquals(ImapSyncPlan.Full, plan(modSeq = 60L))
    }

    /**
     * UIDNEXT moved: mail ARRIVED. The delta carries flags only, so the new messages have no
     * envelope anywhere and would never appear in the list.
     */
    @Test fun `new mail is read in full even though only flags could have changed`() {
        assertEquals(ImapSyncPlan.Full, plan(modSeq = 140L, uidNext = 44L, exists = 43))
    }

    /**
     * EXISTS moved while UIDNEXT stood still: an EXPUNGE. A CHANGEDSINCE delta says NOTHING about
     * a message that vanished (QRESYNC's VANISHED is deliberately not implemented here), so the
     * cache would keep showing mail that is gone from the server.
     */
    @Test fun `an expunge is read in full`() {
        assertEquals(ImapSyncPlan.Full, plan(modSeq = 140L, exists = 38))
    }

    /** Arrivals and expunges in the same interval can leave EXISTS where it was. UIDNEXT cannot. */
    @Test fun `an arrival masked by an expunge is still caught by uidnext`() {
        assertEquals(ImapSyncPlan.Full, plan(modSeq = 140L, uidNext = 43L, exists = 40))
    }

    /**
     * The two counters are what make the delta safe, so a stored point missing either of them
     * cannot license one — this is the row written by the version that recorded a watermark
     * before those columns existed.
     */
    @Test fun `a stored point missing its counters is read in full`() {
        assertEquals(ImapSyncPlan.Full, plan(stored = recorded.copy(uidNext = null), modSeq = 140L))
        assertEquals(ImapSyncPlan.Full, plan(stored = recorded.copy(messageCount = null), modSeq = 140L))
    }

    /**
     * "Unchanged" is decided from the watermark alone and is reached before the counters are even
     * consulted: a folder whose modseq did not move cannot have gained or lost a message, and a
     * server disagreeing with itself about that is a server whose numbers are not usable anyway.
     */
    @Test fun `an unmoved watermark is unchanged even without stored counters`() {
        assertEquals(ImapSyncPlan.Unchanged, plan(stored = recorded.copy(uidNext = null, messageCount = null)))
    }
}
