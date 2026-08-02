package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The upgrade purge of the cached message bodies: ONCE per version bump, and not one time more.
 *
 * Both halves matter and they pull in opposite directions. Never purging leaves a new reader
 * feature invisible on the mail already in the cache — which, thanks to the prefetch, is the
 * twenty newest messages of the inbox, i.e. the newsletters this lot is about. Purging too often
 * throws away every body on every start and turns each message back into a network round trip.
 */
class BodyCacheUpgradeTest {

    @Test fun `a version bump purges, and records the version it purged for`() {
        assertEquals(167, bodyCachePurgeVersion(purgedForVersion = 166, currentVersion = 167))
    }

    /** ⛔ The witness. Same version, second start: nothing to do. */
    @Test fun `the same version does not purge again`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 166, currentVersion = 166))
    }

    /** A fresh install records the version without ever having had anything to drop. */
    @Test fun `a first launch records the version`() {
        assertEquals(166, bodyCachePurgeVersion(purgedForVersion = 0, currentVersion = 166))
    }

    /** A downgrade (a sideloaded older build) purges nothing: its own bodies are its own. */
    @Test fun `an older build does not purge`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 167, currentVersion = 166))
    }

    /** Two launches of the same build, played out: the second one is a no-op. */
    @Test fun `the purge happens exactly once across restarts`() {
        var recorded = 166
        val purges = mutableListOf<Int>()
        repeat(3) {
            bodyCachePurgeVersion(recorded, 167)?.let { version ->
                purges += version
                recorded = version
            }
        }
        assertEquals(listOf(167), purges)
    }
}
