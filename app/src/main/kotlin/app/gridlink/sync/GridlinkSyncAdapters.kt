package app.gridlink.sync

import android.accounts.Account
import android.app.Application
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import app.gridlink.container
import kotlinx.coroutines.runBlocking
import java.time.ZoneId

/**
 * The two sync adapters the system runs to keep the mirror fresh, and the services that bind them.
 *
 * ## 🔴 Each pass fetches, then publishes, and the publish happens either way
 * The DAV fetch is attempted first so a "Sync now" from system Settings means what it says. If it
 * fails, the publish still runs from the cache. That is not resilience theatre: it is the same rule
 * the rest of the app follows, that the cache is what the UI reads and the network only ever writes
 * into it. A phone with no signal still gets its contacts into the dialler.
 *
 * ## Why `runBlocking` is right here
 * `onPerformSync` is already called on a background thread the system owns, and it is expected to
 * return only when the sync is finished. Handing the work to another scope and returning early
 * would tell the system a sync completed while it was still running.
 */
private abstract class GridlinkSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {

    protected abstract val label: String

    /**
     * Fetch from the server, send the provider's own changes up, and write the result into the
     * system provider, for one mail account. [primary] is true for the first mail account on this
     * Android account, the one that takes rows created in the system app (see [ContactsUploader]).
     */
    protected abstract suspend fun sync(account: Account, accountId: String, primary: Boolean)

    final override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
    ) {
        if (!SystemMirror.hasPermissions(context)) {
            Log.i(TAG, "$label sync skipped: the mirror's permissions are not granted")
            return
        }
        val container = (context.applicationContext as Application).container
        runBlocking {
            val ids = SystemMirror.accountIdsFor(account, container.accountStore)
            if (ids.isEmpty()) {
                // The mail account is gone but its system account has not been swept yet. Nothing
                // to publish, and nothing to clean up either: unregistering the account is what
                // deletes the rows, and SystemMirror.apply does that on the next app start.
                Log.i(TAG, "$label sync skipped: no mail account named ${account.name}")
                return@runBlocking
            }
            for ((index, id) in ids.withIndex()) {
                runCatching { sync(account, id, primary = index == 0) }
                    .onFailure {
                        Log.w(TAG, "$label sync failed for $id", it)
                        syncResult.stats.numIoExceptions++
                    }
            }
        }
    }

    private companion object {
        const val TAG = "GridlinkSync"
    }
}

private class ContactsSyncAdapter(context: Context) : GridlinkSyncAdapter(context) {

    override val label = "contacts"

    private val mirror = ContactsMirror(context.contentResolver)

    override suspend fun sync(account: Account, accountId: String, primary: Boolean) {
        val repository = (context.applicationContext as Application).container.davRepository
        // Failure here is not failure of the pass; see the file note.
        runCatching { repository.syncContacts(accountId) }
            .onFailure { Log.i(TAG, "CardDAV fetch failed; publishing the cache anyway", it) }
        // Same order as the calendar adapter, for the same reasons: download, upload, download
        // again only if something was written, publish.
        val uploaded = runCatching {
            ContactsUploader(
                accountId = accountId,
                local = ProviderContactsLocal(context.contentResolver, account, accountId),
                remote = RepositoryContactsRemote(repository, accountId),
                includeNew = primary,
            ).run()
        }.onFailure { Log.w(TAG, "contacts upload failed; unsent edits stay marked for the next pass", it) }
            .getOrNull()
        if (uploaded?.wroteAnything == true) {
            runCatching { repository.syncContacts(accountId) }
                .onFailure { Log.i(TAG, "post-upload fetch failed; publishing the cache anyway", it) }
        }
        val cards = repository.allContacts(accountId)
        val written = mirror.publish(account, accountId, cards)
        Log.i(TAG, "contacts mirror: ${cards.size} cached, $written written, upload $uploaded for $accountId")
    }

    private companion object {
        const val TAG = "GridlinkSync"
    }
}

private class CalendarSyncAdapter(context: Context) : GridlinkSyncAdapter(context) {

    override val label = "calendar"

    private val mirror = CalendarMirror(context.contentResolver)

    override suspend fun sync(account: Account, accountId: String, primary: Boolean) {
        val repository = (context.applicationContext as Application).container.davRepository
        runCatching { repository.syncCalendars(accountId) }
            .onFailure { Log.i(TAG, "CalDAV fetch failed; publishing the cache anyway", it) }
        // Download first, so the uploader compares the user's edits against the newest server
        // copy; then upload; then download again only if something was written, so the publish
        // shows the server's own version of what was just sent.
        val zone = ZoneId.systemDefault()
        val uploaded = runCatching {
            CalendarUploader(
                accountId = accountId,
                local = ProviderCalendarLocal(context.contentResolver, account, accountId),
                remote = RepositoryCalendarRemote(repository, accountId, zone),
                displayZone = zone,
            ).run()
        }.onFailure { Log.w(TAG, "calendar upload failed; unsent edits stay marked for the next pass", it) }
            .getOrNull()
        if (uploaded?.wroteAnything == true) {
            runCatching { repository.syncCalendars(accountId) }
                .onFailure { Log.i(TAG, "post-upload fetch failed; publishing the cache anyway", it) }
        }
        val calendars = repository.calendars(accountId)
        val events = repository.allEvents(accountId)
        val written = mirror.publish(account, accountId, calendars, events)
        Log.i(TAG, "calendar mirror: ${events.size} cached, $written written, upload $uploaded for $accountId")
    }

    private companion object {
        const val TAG = "GridlinkSync"
    }
}

/**
 * Binds the contacts adapter.
 *
 * The adapter is created once and shared, which the framework requires: it serialises calls per
 * account itself, and a fresh instance per bind would lose that.
 */
class ContactsSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder = adapter(applicationContext).syncAdapterBinder

    private companion object {
        private var instance: ContactsSyncAdapter? = null

        @Synchronized
        fun adapter(context: Context): ContactsSyncAdapter =
            instance ?: ContactsSyncAdapter(context).also { instance = it }
    }
}

/** Binds the calendar adapter. See [ContactsSyncService]. */
class CalendarSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder = adapter(applicationContext).syncAdapterBinder

    private companion object {
        private var instance: CalendarSyncAdapter? = null

        @Synchronized
        fun adapter(context: Context): CalendarSyncAdapter =
            instance ?: CalendarSyncAdapter(context).also { instance = it }
    }
}
