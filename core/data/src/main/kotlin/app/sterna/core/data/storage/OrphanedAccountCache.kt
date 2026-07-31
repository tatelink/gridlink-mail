package app.sterna.core.data.storage

/**
 * Which cached accounts no longer exist — the decision behind
 * [StorageRepository.purgeOrphanedAccounts], kept as a pure function so the guard below can be
 * tested without a database.
 *
 * Codeberg #121. Scoping the unified list on (account, folder) pairs stops an orphaned row from
 * being LISTED, but it does not remove it: nothing syncs a row no account covers, so it can never
 * be marked read, never disappear, and never be pruned — it just sits in the database. This is the
 * other half of the fix, and the half that actually ends the row's life.
 *
 * An account id is a locally minted UUID; a mailbox id comes from the server. Removing an account
 * and adding it back therefore gives the same mail a NEW account id while the old rows keep the
 * old one, orphaned.
 */
object OrphanedAccountCache {

    /**
     * The cached account ids in [cachedAccountIds] that [knownAccountIds] does not contain.
     *
     * ⛔ AN EMPTY [knownAccountIds] PURGES NOTHING, and that is the whole point of this function
     * existing separately from a `DELETE … WHERE accountId NOT IN (…)`. "No accounts" is not only
     * the state of a user who has signed out of everything: it is also what
     * `AccountStore.accounts()` returns when the stored JSON fails to decode, and what any caller
     * that runs before the account list is read would pass. Treating that as "every cached row is
     * an orphan" would delete the entire cache of a perfectly healthy install — every account's
     * mail, on one bad read, on a cold start. A signed-out install has nothing worth reclaiming
     * anyway: sign-out already purges its account (StorageRepository.purgeAccount).
     *
     * Order is the caller's ([cachedAccountIds]'s), and duplicates are dropped.
     */
    fun orphans(knownAccountIds: Collection<String>, cachedAccountIds: Collection<String>): List<String> {
        if (knownAccountIds.isEmpty()) return emptyList()
        val known = knownAccountIds.toSet()
        return cachedAccountIds.distinct().filterNot { it in known }
    }
}
