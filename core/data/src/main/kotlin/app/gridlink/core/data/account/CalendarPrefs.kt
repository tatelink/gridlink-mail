package app.gridlink.core.data.account

import app.gridlink.core.data.db.DavCollectionEntity
import kotlinx.serialization.Serializable

/**
 * What the user chose about an account's calendars, and what the server said is its default.
 *
 * Every calendar in here is named by [calendarPrefKey], not by its local url. The url is not stable
 * enough: the same server calendar is `jmap:calendar/d` on an account added fresh over JMAP, but
 * keeps its old CalDAV path on an account that synced over CalDAV first and was then moved to JMAP
 * (see `DavMappers.adoptCollectionUrls`). Removing and re-adding such an account switches from the
 * second naming to the first, which is exactly the case these choices have to survive.
 *
 * ## 🔴 Stored per login, not per account record
 * A [StoredAccount] id is minted fresh every time an account is added, so anything filed under it is
 * gone the moment the user removes the account and adds it back, which is the first thing anyone
 * tries when mail misbehaves. These choices are filed under [calendarPrefsKey] instead (the login
 * and the calendar server), which is the same before and after. Two account records on the same
 * login and the same calendar server share one entry, on purpose: they are the same calendars, and
 * a calendar hidden or renamed on one would otherwise reappear under its old name on the other.
 *
 * @property hidden the calendars switched off in the Calendars sheet. The HIDDEN set rather than the
 *   shown one, so a calendar the server adds later appears on its own. May also hold plain urls,
 *   carried over from the old per-record set ([StoredAccount.hiddenCalendars]).
 * @property names local names, for a server that refused (or cannot store) the rename.
 * @property colors local colours as `#RRGGBB`, for the same reason.
 * @property serverDefaults the calendar the server names as the account's default (JMAP
 *   `isDefault`, CalDAV `schedule-default-calendar-URL`), at most one per protocol: two account
 *   records sharing this entry can sync over different protocols, and each must not erase the
 *   other's answer on every sync.
 */
@Serializable
data class CalendarPrefs(
    val hidden: Set<String> = emptySet(),
    val names: Map<String, String> = emptyMap(),
    val colors: Map<String, String> = emptyMap(),
    val serverDefaults: Set<String> = emptySet(),
) {
    /** Whether this entry carries nothing worth storing. */
    fun isEmpty(): Boolean = hidden.isEmpty() && names.isEmpty() && colors.isEmpty() && serverDefaults.isEmpty()

    /** Whether [collection] is switched off, under its stable key or (old records) its url. */
    fun isHidden(collection: DavCollectionEntity): Boolean =
        calendarPrefKey(collection) in hidden || collection.url in hidden

    /** Whether the server calls [collection] the account's default. */
    fun isServerDefault(collection: DavCollectionEntity): Boolean = calendarPrefKey(collection) in serverDefaults

    /** This entry with [account]'s legacy per-record hidden set folded in. */
    internal fun withLegacy(account: StoredAccount?): CalendarPrefs =
        if (account == null || account.hiddenCalendars.isEmpty()) {
            this
        } else {
            copy(hidden = hidden + account.hiddenCalendars)
        }

    /** This entry with the default for one protocol ([jmap] or not) replaced by [key]. */
    internal fun withServerDefault(key: String?, jmap: Boolean): CalendarPrefs {
        val others = serverDefaults.filterNot { it.startsWith(JMAP_CALENDAR_KEY) == jmap }.toSet()
        return copy(serverDefaults = if (key == null) others else others + key)
    }
}

/** The prefix of a [calendarPrefKey] that names a calendar by its JMAP id. */
const val JMAP_CALENDAR_KEY = "jmap:calendar/"

/**
 * The name [CalendarPrefs] files [collection] under: its JMAP id when it has one (which is the same
 * whichever local url the calendar was given), else its CalDAV url (which is the server's own path,
 * and the same on every sync).
 */
fun calendarPrefKey(collection: DavCollectionEntity): String =
    collection.remoteId?.let { JMAP_CALENDAR_KEY + it } ?: collection.url

/**
 * The key an account's [CalendarPrefs] are filed under: its login, lower case, and the host its
 * calendars come from.
 *
 * The host is picked in the order the calendar sync picks its server (the calendar server the user
 * typed, then the mail server, then the IMAP host, then the address's own domain), with any scheme,
 * port and path taken off, so `https://mail.example.com/` and `mail.example.com` are one key.
 */
fun calendarPrefsKey(account: StoredAccount): String {
    val raw = account.davServer.orEmpty().ifBlank { account.server }.ifBlank { account.imapHost.orEmpty() }
        .ifBlank { account.username.substringAfter('@', "") }
    val host = raw.trim().substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    return account.username.trim().lowercase() + "|" + host
}

/**
 * [account]'s entry in [all] (the store's [AccountStore.calendarPrefsFlow] value), with the hidden
 * set it kept on its own record before the keyed store existed folded in.
 */
fun calendarPrefsFor(account: StoredAccount, all: Map<String, CalendarPrefs>): CalendarPrefs =
    (all[calendarPrefsKey(account)] ?: CalendarPrefs()).withLegacy(account)
