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
}
