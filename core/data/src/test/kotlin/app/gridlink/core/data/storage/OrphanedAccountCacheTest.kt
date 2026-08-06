package app.gridlink.core.data.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The guard on the orphaned-cache sweep (Codeberg #121), and the one place in this fix where a
 * mistake costs mail rather than tidiness.
 *
 * `StorageRepository.purgeOrphanedAccounts` deletes the cached messages of every account the app
 * no longer has. Written as `DELETE … WHERE accountId NOT IN (:known)`, an empty `known` deletes
 * EVERYTHING — and "empty" is not a rare state: it is what `AccountStore.accounts()` returns when
 * the stored JSON cannot be decoded, and what any caller that ran before the accounts were read
 * would pass. The decision therefore lives in a pure function that answers "nothing" to it, and
 * these cases pin that answer.
 */
class OrphanedAccountCacheTest {

    @Test fun `no known accounts purges nothing`() {
        // The cold-start / failed-read case. Three accounts' worth of cached mail, and the app
        // saying it knows of none: the sweep must decline, not empty the cache.
        assertEquals(
            emptyList<String>(),
            OrphanedAccountCache.orphans(
                knownAccountIds = emptyList(),
                cachedAccountIds = listOf("accA", "accB", "accC"),
            ),
        )
    }

    @Test fun `an account the app no longer has is an orphan`() {
        // The witness for the case above: with a known account present, the sweep does select the
        // one that is gone — so "purges nothing" is the guard talking, not a function that never
        // returns anything.
        assertEquals(
            listOf("gone"),
            OrphanedAccountCache.orphans(
                knownAccountIds = listOf("accA"),
                cachedAccountIds = listOf("accA", "gone"),
            ),
        )
    }

    @Test fun `every known account is spared`() {
        assertEquals(
            emptyList<String>(),
            OrphanedAccountCache.orphans(
                knownAccountIds = listOf("accA", "accB"),
                cachedAccountIds = listOf("accB", "accA"),
            ),
        )
    }

    @Test fun `a cached account id appearing twice is reported once`() {
        assertEquals(
            listOf("gone"),
            OrphanedAccountCache.orphans(
                knownAccountIds = listOf("accA"),
                cachedAccountIds = listOf("gone", "accA", "gone"),
            ),
        )
    }

    @Test fun `an account with nothing cached is not reported`() {
        // A freshly added account has no rows yet; the sweep has nothing to say about it either
        // way, and must not invent a delete for it.
        assertEquals(
            emptyList<String>(),
            OrphanedAccountCache.orphans(
                knownAccountIds = listOf("accA", "brandNew"),
                cachedAccountIds = listOf("accA"),
            ),
        )
    }
}
