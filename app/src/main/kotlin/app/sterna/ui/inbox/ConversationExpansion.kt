package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email

/**
 * Pure helpers for inline conversation expansion in the inbox list — extracted from
 * [InboxViewModel] so the expand-state and member-selection rules are unit-testable
 * without an Android runtime.
 */
internal object ConversationExpansion {
    /** The thread a message belongs to: its threadId, or its own id when thread-less. */
    fun threadKey(threadId: String?, id: String): String = threadId ?: id

    /** Toggle a thread key in (or out of) the set of currently-expanded threads. */
    fun toggle(expanded: Set<String>, key: String): Set<String> =
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
     * server: dedup by id (the fresher [fetched] copy wins), drop the representative
     * ([representativeId]) already shown at the top, and order newest-first by receivedAt — so a
     * server completion fills in received messages missing from the cache window without
     * reordering or duplicating what is already on screen.
     */
    fun mergeMembers(cached: List<Email>, fetched: List<Email>, representativeId: String): List<Email> {
        val byId = LinkedHashMap<String, Email>()
        cached.forEach { byId[it.id] = it }
        // The fetched copy refreshes content (keywords, headers, mailboxIds) but comes off the
        // wire without local identity — it must never null out the cached accountId/mailboxId
        // that action routing (account pick, destroy-vs-move) depends on.
        fetched.forEach { f ->
            val c = byId[f.id]
            byId[f.id] = if (c == null) f else f.copy(
                accountId = f.accountId ?: c.accountId,
                mailboxId = f.mailboxId ?: c.mailboxId,
            )
        }
        return byId.values
            .filter { it.id != representativeId }
            .sortedByDescending { it.receivedAt ?: "" }
    }
}
