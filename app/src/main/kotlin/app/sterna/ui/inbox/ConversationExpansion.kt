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
