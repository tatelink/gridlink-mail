package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email

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
     * [membersBelow] / [mergeMembers], which order them newest-first). Each entry pairs the
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

    /**
     * The server-fetched thread members an unfolded conversation may DISPLAY: those living in
     * one of the [allowed] mailboxes (the viewed folder(s) plus the account's Sent folder).
     * A wire member carries no local mailboxId, so membership is judged on its server
     * [Email.mailboxIds] map — kept if ANY of its mailboxes is allowed. Members whose only
     * homes are elsewhere (Trash, Spam, Drafts, another folder) are dropped from display:
     * they belong to that folder's own conversation. The caller still persists ALL fetched
     * members to the cache; only what is shown is scoped.
     */
    fun membersInScope(fetched: List<Email>, allowed: Set<String>): List<Email> =
        fetched.filter { f ->
            f.mailboxIds.keys.any { it in allowed } || f.mailboxId?.let { it in allowed } == true
        }

    /**
     * Merge the instantly-shown [cached] members with the [fetched] full-thread members from the
     * server, drop the representative ([representativeId]) already shown at the top, and order
     * newest-first by receivedAt.
     *
     * The merge is APPEND-ONLY: a member the list is already showing keeps the copy it was drawn
     * with, and only ids the cache had no row for are added. The server completion exists to fill
     * in messages that fell outside the folder's short cache window — adding rows, not redrawing
     * the ones under the user's eyes.
     *
     * Rewriting them used to be visible (Codeberg #63). A cache row cannot carry recipients (the
     * `emails` table has no `to` column — see EmailMapper's in-memory memo), so after a cold start
     * a self-authored member renders with the sender fallback; the wire copy does carry them, and
     * swapping it in flipped that row's name line from the (self) sender to "To: …" — with the
     * monogram changing letter and colour with it — a beat after the conversation unfolded. The
     * expand animation absorbed that swap, so it was invisible with animations ON; with the OS
     * "Remove animations" setting ON the rows are instantly at rest and it rendered as a blink.
     * Keeping the drawn copy also stops a late completion from reverting an optimistic star or
     * read toggle applied to a member while the fetch was in flight.
     *
     * The wire copies are still persisted by the caller, so the cache — and the recipients memo
     * warmed on the way in — are complete for the next read; only the snapshot on screen is left
     * alone. Keeping the cached copy also keeps the local accountId/mailboxId that action routing
     * (account pick, destroy-vs-move) depends on and that a wire member has no way to supply.
     */
    fun mergeMembers(cached: List<Email>, fetched: List<Email>, representativeId: String): List<Email> {
        val byId = LinkedHashMap<String, Email>()
        cached.forEach { byId[it.id] = it }
        fetched.forEach { f -> if (!byId.containsKey(f.id)) byId[f.id] = f }
        return byId.values
            .filter { it.id != representativeId }
            .sortedByDescending { it.receivedAt ?: "" }
    }
}
