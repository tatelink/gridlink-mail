package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

/** A JMAP Mailbox (RFC 8621 §2). Only the fields we use so far. */
@Serializable
data class Mailbox(
    val id: String,
    val name: String,
    val role: String? = null,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val totalEmails: Int = 0,
    val unreadEmails: Int = 0,
    /**
     * Unread count shown as the drawer badge. For JMAP accounts this is a LIVE local aggregate
     * over the cached `emails` table, mode-appropriate (unread threads in conversation view,
     * unread messages in flat view) and folder-scoped exactly like the collapsed list — so the
     * badge equals the visible bold rows. For IMAP accounts (partial cache window) it falls back
     * to the stored server counter. Not a server field; filled in by the repository. Distinct
     * from [unreadEmails] (the server's stored count, kept for meta/reconciliation).
     */
    val unreadForList: Int = 0,
)
