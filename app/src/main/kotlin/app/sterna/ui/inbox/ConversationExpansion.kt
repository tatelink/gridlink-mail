package app.sterna.ui.inbox

import app.sterna.core.data.mail.ConversationScope
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Which conversation, in which account. Servers number threads PER ACCOUNT, so two accounts of the
 * same server routinely carry the same thread id and the unified inbox shows both rows side by
 * side: everything that identifies a conversation across accounts — the expanded set, the loaded
 * members, the recorded representatives, the reading view's swipe context — must carry the account
 * too. Keyed on the bare thread id, unfolding account A's row also unfolded B's homonym and the
 * reader could be handed the OTHER account's messages (#92).
 *
 * [accountId] is null only for a message that never came from the cache (single-account fallback);
 * it is then null for every message of that view, so keys still match each other.
 *
 * Navigation carries the key as ONE opaque string ([encode] / [decode]) rather than a bare thread
 * id: a route argument that only names the thread is exactly how this bug returns.
 */
data class ThreadKey(val accountId: String?, val threadId: String) {
    /** The key as a single route argument. The account id (a UUID) never contains '|'. */
    fun encode(): String = "${accountId.orEmpty()}|$threadId"

    companion object {
        /**
         * Parse an [encode]d route argument. Null for anything that isn't one — including a bare
         * thread id left in a saved route by an older version: no account, no key, and the reader
         * falls back to the single message it was given rather than guessing an account.
         */
        fun decode(raw: String): ThreadKey? {
            val cut = raw.indexOf('|')
            if (cut < 0) return null
            val thread = raw.substring(cut + 1)
            if (thread.isEmpty()) return null
            return ThreadKey(raw.substring(0, cut).ifEmpty { null }, thread)
        }
    }
}

/**
 * The scope the list currently on screen was BUILT with: the folder(s) it is paging, and the Sent
 * resolution its chips counted over. One value, recorded once where the pager is built, so the
 * unfold describes the same conversation the row's chip announced.
 *
 * Both halves are recorded, not re-read. Re-reading the selection at unfold time is the same
 * mistake the Sent lookup made, on the other argument: switching folders collapses the threads and
 * changes the selection, but the previous folder's rows stay drawn until the new pager loads, so a
 * chip tapped in that window unfolded with the NEW folder's scope — no members under a chip of 3.
 */
internal data class ListScope(
    val viewedMailboxIds: List<String> = emptyList(),
    val sentMailboxes: List<Pair<String, String>> = emptyList(),
) {
    /**
     * The folders a conversation of [accountId] covers in this scope: the viewed folder(s) plus
     * that account's Sent folder(s). The chip's query is bound from the same [ConversationScope]
     * decision on the same recorded pairs, so the row's number and the messages under it are two
     * readings of one folder set.
     *
     * [accountId] is the REPRESENTATIVE's — a unified-view conversation of another account keeps
     * its own Sent folder, never the current account's (#92).
     */
    fun folders(accountId: String?): List<String> =
        ConversationScope.folders(viewedMailboxIds, sentMailboxes, accountId).toList()
}

/**
 * The messages beneath the unfolded rows, as a LIVE reading of the cache — the other half of the
 * fix that gave the chip and the unfold one folder resolution.
 *
 * Sharing the input was not sharing the answer. The chip is a live query (Room re-runs it on every
 * write to `emails`); the unfold was a snapshot taken when the row opened. So the two agreed at the
 * instant of the tap and drifted from the next write on: a reply arriving in a thread already
 * unfolded moved the chip to 4 and left three messages under it, and a folder cache that finished
 * syncing after the tap rebuilt the chip with the Sent folder the snapshot never saw. Neither
 * corrected itself while the folder stayed open.
 *
 * Here both sides read the same table through the same [ConversationScope] decision, and the read
 * is a flow on either side. What the row says and what it shows are two views of one write.
 */
internal object ThreadMemberStream {

    /**
     * The WHOLE membership of every unfolded conversation, keyed by thread — exactly the messages
     * the row's chip counts — recomputed whenever the set of unfolded rows changes, the [scope] the
     * list was built with changes, the cache changes underneath, or a snooze starts or lapses.
     *
     * The representative is NOT taken out here, and that is deliberate. It used to be, from an id
     * recorded when the row was tapped, and that put the very snapshot this path exists to remove
     * one notch further down: the members became a live reading while the id subtracted from them
     * stayed frozen, so a message arriving in an open conversation was drawn twice — once on the
     * row, which picks its representative live, and once beneath it — while the message it
     * displaced vanished, under a chip that went on counting right. The subtraction now happens
     * where BOTH values are drawn, on the row's own representative: see [membersBelow].
     *
     * [read] is the observed cache query (`MailRepository.observeThreadEmails`), [snoozed] the
     * account-qualified ids an active snooze hides — the chip's SQL excludes them and the two must
     * count the same messages, which also means the unfold has to see the snooze LAPSE, and the
     * message table alone never says so — and [fallbackAccountId] the current account, used only
     * for a thread key that carries none, exactly as the one-shot load did.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun members(
        expanded: Flow<Set<ThreadKey>>,
        scope: Flow<ListScope>,
        snoozed: Flow<Set<EmailKey>>,
        fallbackAccountId: () -> String?,
        read: (accountId: String, folders: List<String>, threadKey: String) -> Flow<List<Email>>,
    ): Flow<Map<ThreadKey, List<Email>>> =
        combine(expanded, scope) { keys, s -> keys to s }
            .flatMapLatest { (keys, s) ->
                if (keys.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(keys.map { key -> one(key, s, fallbackAccountId, read) }) { it.toMap() }
                }
            }
            .combine(snoozed) { all, hidden ->
                if (hidden.isEmpty()) all
                else all.mapValues { (_, members) -> members.filterNot { it.emailKey() in hidden } }
            }

    private fun one(
        key: ThreadKey,
        scope: ListScope,
        fallbackAccountId: () -> String?,
        read: (accountId: String, folders: List<String>, threadKey: String) -> Flow<List<Email>>,
    ): Flow<Pair<ThreadKey, List<Email>>> {
        val accountId = key.accountId ?: fallbackAccountId() ?: return flowOf(key to emptyList())
        // The SAME decision, on the SAME recorded resolution, that the chip's query was bound with.
        return read(accountId, scope.folders(accountId), key.threadId).map { all -> key to all }
    }

    /**
     * What to draw after a live reading: [live] decides MEMBERSHIP — a message that joined the
     * conversation appears, one that left the viewed folders goes — while the copies already
     * [drawn] decide CONTENT, so a row under the reader's eyes is never rewritten mid-read.
     *
     * That second half is the append-only rule the server completion used to carry, kept for the
     * reason it was written (Codeberg #63): a cache row cannot carry recipients (the `emails`
     * table has no `to` column — see EmailMapper's in-memory memo), so a self-authored member can
     * render with the sender fallback until the memo is warm, and adopting a richer copy a beat
     * later flipped that row's name line from the (self) sender to "To: …", monogram letter and
     * colour with it. The expand animation absorbed the swap; with the OS "Remove animations"
     * setting on, the rows are instantly at rest and it read as a blink. It also protects the
     * optimistic star and read toggles, which are written on screen before the server has
     * acknowledged anything and only reach the cache once it has.
     *
     * [removed] are the members a swipe has just taken off the screen while its server round-trip
     * is still in flight: their cache rows outlive the gesture by a moment, and a live reading
     * landing in that window would put the row back under the reader's thumb. Dropped again here,
     * like the search snapshot's own tombstones.
     *
     * Identity is preserved for unchanged members, so an unchanged conversation produces an EQUAL
     * map — which a StateFlow does not re-emit, and nothing on screen recomposes.
     */
    fun reconcile(drawn: List<Email>, live: List<Email>, removed: Set<EmailKey>): List<Email> {
        val byKey = drawn.associateBy { it.emailKey() }
        return live.mapNotNull { m ->
            val key = m.emailKey()
            if (key in removed) null else byKey[key] ?: m
        }
    }
}

/**
 * The members currently masked from the live reading of the unfolded rows — and, above all, the
 * only way to mask one.
 *
 * The mask covers ONE window: a gesture has already taken a row off the screen, and the cache row
 * it was drawn from only leaves when the server acknowledges the move, so a reading landing in
 * between would put it straight back under the reader's thumb. That window is exactly the length
 * of the call, which is why [hiding] is the whole API: it raises the mask, runs the call, and
 * lifts the mask in a `finally`.
 *
 * The shape matters more than it looks. The mask used to be a plain set that callers raised and
 * lowered themselves, and lowering it was a DUTY — one every path but one discharged. The path
 * that forgot was the selection bar's "Snooze": it masked the messages it snoozed and never
 * unmasked them, and a snooze does not remove anything, it writes a date and leaves the message
 * where it is. So the message came back into the chip's count at its due date, and never back
 * under the row; folding and unfolding did not help, because nothing lifts a mask nobody lifts.
 * Here there is no duty to forget: whatever the call does — succeed, fail, snooze, or throw — the
 * mask comes down with it, and what the reader then sees is decided by the cache and the snooze
 * table, which are the things that actually know.
 *
 * INVARIANT, for the next caller: this is a SET, not a counter. Two [hiding] calls overlapping on
 * the same member would have the inner one lift a mask the outer still needs, and the member would
 * come back under the reader's thumb for the rest of the outer call. Nothing reaches that today —
 * the one gesture that raises the mask twice, bulk delete, PARTITIONS its selection into the
 * held-back destroy and the move, so the two sets are disjoint by construction. A path that masks a
 * member already masked has to make the mask count first.
 */
internal class ThreadMemberMask {
    private val hidden = mutableSetOf<EmailKey>()

    /** The masked members, for the reading to drop — see [ThreadMemberStream.reconcile]. */
    val keys: Set<EmailKey> get() = hidden

    /** Mask [keys] for the duration of [op], and no longer. */
    suspend fun <T> hiding(keys: Set<EmailKey>, op: suspend () -> T): T {
        hidden += keys
        return try {
            op()
        } finally {
            hidden -= keys
        }
    }

    /** Forget everything: the rows the mask applied to are no longer on screen at all. */
    fun clear() = hidden.clear()
}

/**
 * Pure helpers for inline conversation expansion in the inbox list — extracted from
 * [InboxViewModel] so the expand-state and member-selection rules are unit-testable
 * without an Android runtime.
 */
internal object ConversationExpansion {
    /**
     * The conversation a message belongs to: its owning account plus its threadId — or its own
     * id when thread-less. ACCOUNT-QUALIFIED, always: see [ThreadKey].
     */
    fun threadKey(accountId: String?, threadId: String?, id: String): ThreadKey =
        ThreadKey(accountId, threadId ?: id)

    /** Toggle a thread key in (or out of) the set of currently-expanded threads. */
    fun toggle(expanded: Set<ThreadKey>, key: ThreadKey): Set<ThreadKey> =
        if (key in expanded) expanded - key else expanded + key

    /**
     * The members to list beneath an unfolded conversation row: every message of the thread except
     * the representative ([representativeId]) the row itself is drawing.
     *
     * Called AT THE DRAW SITE, with the id of the message that row is showing at that instant —
     * never with a copy taken earlier. The representative is a live value: the paging query picks
     * it as the newest message of the thread in the viewed folder(s) and re-picks it on every
     * write, so mail arriving in an open conversation changes it. Subtracting a remembered one
     * takes the wrong message out: the newcomer is drawn on the row AND listed below it, the
     * message it displaced disappears altogether, and the count — one row plus N−1 members — still
     * comes to N, which is why this went unseen.
     *
     * The off-by-one is the point: the chip counts the WHOLE conversation, the list beneath a row
     * holds one fewer, because the row is already drawing that one.
     */
    fun membersBelow(all: List<Email>, representativeId: String): List<Email> =
        all.filter { it.id != representativeId }

    /**
     * The messages of an unfolded conversation, in the order the list shows them: the
     * representative on the collapsed row first, then the members listed beneath it (see
     * [membersBelow], which keeps the cache's newest-first order). Each entry pairs the
     * message id with its owning account, which is what the reading view's pager needs.
     *
     * This is the swipe context for a message opened from inside a conversation: the pager
     * runs over exactly what the unfolded conversation showed, and stops at its ends.
     * Deduped by id so a member copy of the representative — a merge that raced a refresh —
     * can never produce two pages for the same message.
     */
    fun threadEntries(
        representativeId: String,
        representativeAccountId: String?,
        members: List<Email>,
    ): List<Pair<String, String?>> =
        (listOf(representativeId to representativeAccountId) + members.map { it.id to it.accountId })
            .distinctBy { it.first }

}
