package app.gridlink.core.jmap.model

import kotlinx.serialization.Serializable

/**
 * What this account may do to a mailbox: JMAP's `myRights` object (RFC 8621 §2).
 *
 * Only the two rights the folder tree acts on. The server sends nine; the rest describe reading and
 * writing messages, which this app finds out about by being refused a write, not by asking first.
 *
 * 🔴 Both are **nullable, and null means "the server did not say"** rather than "no". A mailbox
 * cached before these columns existed, and every mailbox on the IMAP path (where there is no such
 * property to fetch), arrives with both null — and a null must not lock a user out of renaming their
 * own folder. The fallback lives in the UI mapping, which is the only place that knows what the app
 * would have guessed without this.
 */
@Serializable
data class MailboxRights(
    val mayRename: Boolean? = null,
    val mayDelete: Boolean? = null,
)

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
     * What this account may do to this mailbox, as the server reports it. Null when the response
     * carried no `myRights` — see [MailboxRights], where null is a different fact from false.
     */
    val myRights: MailboxRights? = null,
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
