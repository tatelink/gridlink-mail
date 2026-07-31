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
     * The members of every unfolded conversation, keyed by thread, recomputed whenever the set of
     * unfolded rows changes, the [scope] the list was built with changes, or the cache itself
     * changes underneath.
     *
     * [read] is the observed cache query (`MailRepository.observeThreadEmails`), [representative]
     * the id the collapsed row already draws — excluded from the list below it, see
     * [ConversationExpansion.membersBelow] — and [fallbackAccountId] the current account, used only
     * for a thread key that carries none, exactly as the one-shot load did.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun members(
        expanded: Flow<Set<ThreadKey>>,
        scope: Flow<ListScope>,
        fallbackAccountId: () -> String?,
        representative: (ThreadKey) -> String?,
        read: (accountId: String, folders: List<String>, threadKey: String) -> Flow<List<Email>>,
    ): Flow<Map<ThreadKey, List<Email>>> =
        combine(expanded, scope) { keys, s -> keys to s }
            .flatMapLatest { (keys, s) ->
                if (keys.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(keys.map { key -> one(key, s, fallbackAccountId, representative, read) }) { it.toMap() }
                }
            }

    private fun one(
        key: ThreadKey,
        scope: ListScope,
        fallbackAccountId: () -> String?,
        representative: (ThreadKey) -> String?,
        read: (accountId: String, folders: List<String>, threadKey: String) -> Flow<List<Email>>,
    ): Flow<Pair<ThreadKey, List<Email>>> {
        val accountId = key.accountId ?: fallbackAccountId() ?: return flowOf(key to emptyList())
        // The SAME decision, on the SAME recorded resolution, that the chip's query was bound with.
        return read(accountId, scope.folders(accountId), key.threadId)
            .map { all -> key to ConversationExpansion.membersBelow(all, representative(key).orEmpty()) }
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
     * The members to list beneath a collapsed conversation row: every cached message of the
     * thread except the representative ([representativeId]) already shown on the row itself.
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
