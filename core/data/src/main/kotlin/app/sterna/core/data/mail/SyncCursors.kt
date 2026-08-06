package app.sterna.core.data.mail

/**
 * The JMAP sync cursor of one (account, mailbox): the pair of server states an incremental
 * sync resumes from. Empty/absent = the next sync of that folder takes the full-query branch
 * of `MailRepository.syncMailbox`, which is the only branch that sends the account's sync
 * window to the server.
 */
data class SyncState(val queryState: String, val emailState: String)

/**
 * The persisted half of the cursors. Implemented by [SyncStateStore] (SharedPreferences);
 * an interface so the bookkeeping in [SyncCursors] — "both halves fall, or neither" — can be
 * executed in a JVM test instead of being read as text.
 */
interface SyncCursorStore {
    fun save(key: String, queryState: String, emailState: String)
    fun load(key: String): Pair<String, String>?
    fun remove(key: String)
    fun clear()

    /** Every cursor key held, excluding the store's own schema markers. */
    fun keys(): Set<String>
}

/**
 * Which cursor keys belong to [accountId], among [keys].
 *
 * A key is `"<localAccountId><mailboxId>"` (`MailRepository.syncKey`), concatenated with no
 * separator, so account membership can only be read as a prefix. That is exact for the ids the
 * app mints — [java.util.UUID] strings, all 36 characters, so no account id can be a prefix of
 * another — but a prefix rule that assumed it would fail SILENTLY the day an id of another shape
 * appears (an import, a migration): it would drop a sibling account's cursors and send a folder
 * the user never touched into a full re-query.
 *
 * So the sibling ids are taken into account and the LONGEST matching id wins: a key claimed by a
 * longer sibling id belongs to that sibling, never to [accountId].
 *
 * ⚠ "Longest wins" is a tie-break, NOT a proof, and it is not symmetric. Account `"1"` asking for
 * its keys will not touch `"12inbox"` — but account `"12"` asking for ITS keys WILL take it, even
 * though it could just as well be account `"1"`'s mailbox `"2inbox"`. Nothing in the key can tell
 * those apart. So the short id fails safe (its cursor survives, the setting stays inert, which is
 * visible) and the long one does not (it drops a cursor that may be a sibling's, which costs that
 * sibling one full re-query). Unreachable today — every shipped id is a 36-character UUID, so no
 * id is a prefix of another — and the rule exists only so the day that stops being true is a
 * bounded cost rather than a silent one.
 *
 * [accountId] must not be blank: the empty string prefixes every key, so a blank id would turn a
 * per-account drop into the global reset this exists to avoid.
 */
fun cursorKeysOfAccount(
    keys: Collection<String>,
    accountId: String,
    siblingAccountIds: Collection<String> = emptyList(),
): List<String> {
    require(accountId.isNotBlank()) {
        "cursorKeysOfAccount() needs a real account id: a blank one prefixes every key and would " +
            "drop every account's cursors."
    }
    // Only an id that is itself longer AND starts with this one can compete for the same key:
    // two prefixes of one string are always prefixes of each other.
    val rivals = siblingAccountIds.filter {
        it != accountId && it.length > accountId.length && it.startsWith(accountId)
    }
    return keys.filter { key -> key.startsWith(accountId) && rivals.none { key.startsWith(it) } }
}

/**
 * The per-(account, mailbox) JMAP sync cursors: an in-memory map with write-through to [store]
 * (when wired) so cursors survive process death — vital once pushes wake a dead process
 * (issue #17); a cold start then still runs a cheap delta.
 *
 * ⚠ Every operation touches BOTH halves. Dropping a cursor only in memory looks right until the
 * next cold start, when [load] reads the stale one back off disk and the folder resumes a delta
 * it was supposed to have forgotten.
 */
class SyncCursors(private val store: SyncCursorStore? = null) {
    private val memory = java.util.concurrent.ConcurrentHashMap<String, SyncState>()

    fun put(key: String, state: SyncState) {
        memory[key] = state
        store?.save(key, state.queryState, state.emailState)
    }

    fun drop(key: String) {
        memory.remove(key)
        store?.remove(key)
    }

    /** The cursor for [key], read back off disk (and re-cached) when the process has restarted. */
    fun load(key: String): SyncState? =
        memory[key]
            ?: store?.load(key)
                ?.let { (queryState, emailState) -> SyncState(queryState, emailState) }
                ?.also { memory[key] = it }

    /**
     * Drop every cursor of ONE account, in memory and on disk, leaving every other account's
     * alone. [siblingAccountIds] are the other known account ids — see [cursorKeysOfAccount].
     *
     * Both halves are asked for their own keys: a cursor written by a previous process life is on
     * disk and not in memory, and it is exactly the one a cold start would resume from.
     */
    fun dropAccount(accountId: String, siblingAccountIds: Collection<String> = emptyList()) {
        cursorKeysOfAccount(memory.keys, accountId, siblingAccountIds).forEach { memory.remove(it) }
        val persisted = store ?: return
        cursorKeysOfAccount(persisted.keys(), accountId, siblingAccountIds).forEach { persisted.remove(it) }
    }

    /** Every account's cursors, both halves — after a cache wipe, where nothing may be resumed. */
    fun clear() {
        memory.clear()
        store?.clear()
    }
}
