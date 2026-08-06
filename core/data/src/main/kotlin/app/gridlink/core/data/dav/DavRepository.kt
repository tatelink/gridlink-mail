package app.gridlink.core.data.dav

import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.account.AuthType
import app.gridlink.core.data.calendar.CalendarOccurrence
import app.gridlink.core.data.calendar.ICalendarStream
import app.gridlink.core.data.db.AddressBookContactDao
import app.gridlink.core.data.db.AddressBookContactEntity
import app.gridlink.core.data.db.CalendarEventDao
import app.gridlink.core.data.db.CalendarEventEntity
import app.gridlink.core.data.db.DavCollectionDao
import app.gridlink.core.data.db.DavCollectionEntity
import app.gridlink.core.dav.DavClient
import app.gridlink.core.dav.DavCredentials
import app.gridlink.core.dav.DavException
import app.gridlink.core.dav.DavKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/**
 * What one sync attempt did, so a caller can say why the Calendar tab is empty.
 *
 * A count of zero and a failure are different answers to "where is my calendar", and an app that
 * shows the same blank screen for both is one the user cannot debug. [error] carries the reason,
 * already reduced to something worth putting on screen.
 */
data class DavSyncOutcome(
    val collections: Int,
    val itemsChanged: Int,
    val itemsRemoved: Int,
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null

    companion object {
        fun skipped() = DavSyncOutcome(collections = 0, itemsChanged = 0, itemsRemoved = 0)
    }
}

/**
 * The Calendar and Contacts tabs' offline-first store.
 *
 * Reads come from Room and only from Room, exactly as mail does: [syncCalendars] and [syncContacts]
 * write into the cache and never hand data straight to the UI, so a tab renders the same whether or
 * not the network happened to be reachable when it opened.
 *
 * ## What this deliberately does NOT do
 * It is read-only. Creating an event or a contact from the app writes nothing back to the server
 * yet; the new-event and new-contact forms still save locally. Making it two-way needs conditional
 * PUT (If-Match on the etag) and a conflict story, and shipping half of that is how a calendar loses
 * an appointment quietly.
 */
class DavRepository(
    private val client: DavClient,
    private val accountStore: AccountStore,
    private val collectionDao: DavCollectionDao,
    private val eventDao: CalendarEventDao,
    private val contactDao: AddressBookContactDao,
    /** The device's zone, injected so the whole class is testable without touching the clock. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    // ---- Reads -------------------------------------------------------------------------------

    /**
     * Every occurrence between [from] and [to] inclusive, as seen from the device's zone.
     *
     * 🔴 The query window is widened by a day at each end before it reaches SQL. `startDay` is an
     * epoch day in the EVENT's zone, and an appointment at 23:00 in Ashvale is already tomorrow in
     * London: without the widening, the first and last day of a month view can silently lose events.
     * The real filtering happens after conversion, in [ICalendarStream.occurrences].
     */
    fun observeOccurrences(
        accountId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<CalendarOccurrence>> =
        eventDao.observeInRange(accountId, from.toEpochDay() - 1, to.toEpochDay() + 1)
            .map { rows -> occurrencesOf(rows, from, to) }

    /** The expansion, split out so it can be tested without a database. */
    internal fun occurrencesOf(
        rows: List<CalendarEventEntity>,
        from: LocalDate,
        to: LocalDate,
    ): List<CalendarOccurrence> {
        val displayZone = zone()
        val events = rows.mapNotNull { DavMappers.toParsed(it, displayZone) }
        return ICalendarStream.occurrences(events, from..to, displayZone)
    }

    fun observeContacts(accountId: String): Flow<List<AddressBookContactEntity>> =
        contactDao.observeAll(accountId)

    fun observeEventCount(accountId: String): Flow<Int> = eventDao.observeCount(accountId)

    fun observeContactCount(accountId: String): Flow<Int> = contactDao.observeCount(accountId)

    fun observeCalendars(accountId: String): Flow<List<DavCollectionEntity>> =
        collectionDao.observe(accountId, DavCollectionEntity.KIND_CALENDAR)

    fun observeAddressBooks(accountId: String): Flow<List<DavCollectionEntity>> =
        collectionDao.observe(accountId, DavCollectionEntity.KIND_CONTACTS)

    suspend fun event(accountId: String, href: String): CalendarEventEntity? =
        eventDao.byHref(accountId, href)

    suspend fun contact(accountId: String, href: String): AddressBookContactEntity? =
        contactDao.byHref(accountId, href)

    // ---- Sync --------------------------------------------------------------------------------

    /** Bring the account's calendars up to date, if the user asked for calendars at all. */
    suspend fun syncCalendars(accountId: String): DavSyncOutcome {
        if (accountStore.account(accountId)?.syncSelection?.calendar != true) {
            return DavSyncOutcome.skipped()
        }
        return sync(accountId, DavKind.CALENDAR)
    }

    /** Bring the account's address books up to date, if the user asked for contacts at all. */
    suspend fun syncContacts(accountId: String): DavSyncOutcome {
        if (accountStore.account(accountId)?.syncSelection?.contacts != true) {
            return DavSyncOutcome.skipped()
        }
        return sync(accountId, DavKind.ADDRESS_BOOK)
    }

    /** Forget everything cached for one account (sign-out, or clearing its cache). */
    suspend fun clearAccount(accountId: String) {
        eventDao.deleteForAccount(accountId)
        contactDao.deleteForAccount(accountId)
        collectionDao.deleteForAccount(accountId)
    }

    private suspend fun sync(accountId: String, kind: DavKind): DavSyncOutcome {
        val credentials = accountStore.credentials(accountId)
            ?: return DavSyncOutcome(0, 0, 0, error = "No stored credential for this account")
        if (credentials.authType != AuthType.BASIC || credentials.password.isBlank()) {
            // OAuth and token accounts put something that is not a password in the password slot, and
            // Basic auth with a refresh token is a failed login. On a server that bans a source IP
            // after repeated auth failures, retrying that on a schedule is how an account locks
            // itself out of mail as well.
            return DavSyncOutcome(0, 0, 0, error = "Calendar and contacts need a password login")
        }
        val dav = DavCredentials(credentials.username, credentials.password)
        // 🔴 A blank server is not a missing server. Accounts created through autodiscovery store it
        // blank on purpose ("work it out for me"), and DavClient would build `https://` out of that
        // and throw. The address the user typed is the one thing we always have.
        val server = credentials.server.ifBlank { credentials.username.substringAfter('@', "") }
        if (server.isBlank()) {
            return DavSyncOutcome(0, 0, 0, error = "No server address is stored for this account")
        }
        val kindKey = when (kind) {
            DavKind.CALENDAR -> DavCollectionEntity.KIND_CALENDAR
            DavKind.ADDRESS_BOOK -> DavCollectionEntity.KIND_CONTACTS
        }

        val discovered = try {
            client.discover(server, dav, kind)
        } catch (e: DavException) {
            return DavSyncOutcome(0, 0, 0, error = describe(e))
        }
        collectionDao.replaceDiscovered(
            accountId = accountId,
            kind = kindKey,
            discovered = discovered.mapIndexed { i, c -> DavMappers.collection(accountId, c, i) },
        )
        // A collection the server no longer lists takes its items with it. Done before the syncs so
        // a later failure cannot leave rows pointing at a calendar that is gone.
        val keep = discovered.map { it.url }
        if (keep.isEmpty()) {
            clearItems(accountId, kindKey)
        } else {
            when (kind) {
                DavKind.CALENDAR -> eventDao.deleteNotInCollections(accountId, keep)
                DavKind.ADDRESS_BOOK -> contactDao.deleteNotInCollections(accountId, keep)
            }
        }

        val stored = collectionDao.forKind(accountId, kindKey).associateBy { it.url }
        var changed = 0
        var removed = 0
        var firstError: String? = null

        for (collection in discovered) {
            val token = stored[collection.url]?.syncToken
            val result = try {
                client.sync(collection.url, dav, kind, token)
            } catch (e: DavException) {
                // One unreadable calendar must not abandon the others: a shared collection the
                // account has lost access to answers 403 forever, and the user's own calendar has
                // done nothing wrong. The reason is kept and reported once at the end.
                if (firstError == null) firstError = describe(e)
                continue
            }

            // A full resync means the delta was refused, so what came back IS the collection, and
            // anything still cached for it that the response did not name is gone. Clearing first is
            // the only way to notice a deletion that happened while the app was not syncing.
            if (result.fullResync) {
                when (kind) {
                    DavKind.CALENDAR -> eventDao.deleteForCollection(accountId, collection.url)
                    DavKind.ADDRESS_BOOK -> contactDao.deleteForCollection(accountId, collection.url)
                }
            }

            for (item in result.changed) {
                // An item that arrived with only an etag (no body) is not an empty item: it is an
                // item we were not sent. Overwriting the cached copy with nothing would blank a real
                // event, so it is left alone until a sync carries its data.
                if (item.data == null) continue
                val prefix = DavMappers.hrefPrefix(item.href)
                when (kind) {
                    DavKind.CALENDAR -> {
                        val rows = DavMappers.events(accountId, collection.url, item, zone())
                        // One .ics can produce several rows and can lose one on an edit (a
                        // rescheduled instance put back where it belonged). Clearing the file's
                        // rows first is what keeps the deleted override from surviving forever.
                        eventDao.deleteForFile(accountId, item.href, prefix)
                        eventDao.upsertAll(rows)
                        changed += rows.size
                    }
                    DavKind.ADDRESS_BOOK -> {
                        val rows = DavMappers.contacts(accountId, collection.url, item)
                        contactDao.deleteForFile(accountId, item.href, prefix)
                        contactDao.upsertAll(rows)
                        changed += rows.size
                    }
                }
            }

            for (href in result.removed) {
                val prefix = DavMappers.hrefPrefix(href)
                when (kind) {
                    DavKind.CALENDAR -> eventDao.deleteForFile(accountId, href, prefix)
                    DavKind.ADDRESS_BOOK -> contactDao.deleteForFile(accountId, href, prefix)
                }
                removed++
            }

            // 🔴 Written last and only from this collection's own REPORT. Recording a token before
            // the rows it describes are stored would make a crash mid-sync look like a completed
            // one, and the items in between would never be asked for again.
            collectionDao.setSyncToken(accountId, collection.url, result.token)
        }

        return DavSyncOutcome(
            collections = discovered.size,
            itemsChanged = changed,
            itemsRemoved = removed,
            error = firstError,
        )
    }

    private suspend fun clearItems(accountId: String, kindKey: String) {
        when (kindKey) {
            DavCollectionEntity.KIND_CALENDAR -> eventDao.deleteForAccount(accountId)
            DavCollectionEntity.KIND_CONTACTS -> contactDao.deleteForAccount(accountId)
        }
    }

    /**
     * A DAV failure in words worth showing.
     *
     * 401 is the one the user can act on, and saying "sync failed" instead of "wrong password" is
     * how someone spends an evening blaming their network.
     */
    private fun describe(e: DavException): String = when (e.code) {
        401 -> "Sign-in was refused. Check the password for this account."
        403 -> "The server refused access to this collection."
        404 -> "The server has no calendar or address book at that address."
        else -> e.message ?: "Sync failed"
    }
}
