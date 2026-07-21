package app.sterna.core.data.db

import androidx.room.Embedded

/**
 * One collapsed conversation row: the representative (latest) message of a thread,
 * plus how many messages the thread has in this view and whether any is unread.
 * Returned by the conversation-grouping paging query.
 */
data class ConversationRow(
    @Embedded val email: EmailEntity,
    /** Messages of the thread in the viewed mailbox(es) — the number the chip shows. */
    val threadCount: Int,
    /** Cached messages of the thread across the whole account — gates expandability. */
    val threadTotal: Int,
    /** 0 when at least one message in the thread is unread (MIN(seen) over the group). */
    val threadUnread: Int,
)
