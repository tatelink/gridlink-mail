package app.gridlink.core.data.account

import kotlinx.serialization.Serializable

/**
 * What the user asked this account to sync, chosen during setup and editable afterwards.
 *
 * All three are live. [mail] drives the JMAP/IMAP client the app is built on; [calendar] and
 * [contacts] drive `core/dav`'s CalDAV and CardDAV sync through
 * [app.gridlink.core.data.dav.DavRepository], which discovers the collections from the account's own
 * server and caches them into Room.
 *
 * ## Why all three default to on
 * "Set up my account" means the account, and a user who has just typed a password into a mail app is
 * not expecting to have to ask a second time for the calendar that came with it. The toggles exist
 * for the person who deliberately wants mail only, which is a real preference and a rarer one.
 *
 * 🔴 Defaulting to on also decides what happens to accounts saved BEFORE these fields existed. Their
 * stored JSON has no entry for either, so they deserialize onto these defaults and start syncing.
 * That is the intended behaviour: those accounts were created by a build where the toggles were
 * documented as doing nothing, so a stored `false` there records the state of the app, not a choice
 * the user made.
 *
 * ## ⚠️ What a `true` here still cannot promise
 * Only that Gridlink will try. The DAV sync needs a password to do Basic auth with, so an OAuth or
 * token account skips it (a refresh token sent as a password is a failed login, and on a server that
 * bans a source IP after repeated auth failures that is how an account locks itself out of mail as
 * well). A server with no CalDAV at all simply fails discovery. Both are reported into the log and
 * neither touches the mail sync indicator.
 */
@Serializable
data class SyncSelection(
    /** Mail, over JMAP or IMAP depending on the account. */
    val mail: Boolean = true,
    /** Calendars, over CalDAV. Discovered from the account's server; read-only. */
    val calendar: Boolean = true,
    /** Address books, over CardDAV. Discovered from the account's server; read-only. */
    val contacts: Boolean = true,
)
