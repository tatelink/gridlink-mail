package app.gridlink.sync

import android.Manifest
import android.accounts.Account
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Keeps the set of registered [GridlinkSystemAccount]s in step with the mail accounts, and owns the
 * naming rules both mirrors identify their rows by.
 *
 * ## The switch is one switch
 * [SettingsRepository.systemAccountMirror] governs the whole feature. Off is the default and off
 * means no account is registered at all, which is also what deletes every mirrored row: see
 * [GridlinkSystemAccount.remove]. There is deliberately no per-account switch. The mail accounts
 * already carry [app.gridlink.core.data.account.SyncSelection], so an account sharing nothing has
 * nothing to publish and needs no second answer to the same question.
 *
 * ## 🔴 Permissions are checked here, not asked for here
 * A sync adapter runs in the background with no Activity to attach a prompt to, so it cannot ask
 * for anything. If the contacts or calendar permission is missing, [apply] refuses to register the
 * account rather than registering one that would silently mirror nothing. Asking happens in
 * Settings, where the user turned the switch on.
 */
object SystemMirror {

    private const val TAG = "GridlinkSync"

    /**
     * What a mirrored row's identity begins with.
     *
     * 🔴 The local account id, not the server href. Two mail accounts that share a login have
     * hrefs out of the same server namespace, so an href alone would have one account's contacts
     * overwriting the other's, and 2.1.3 published both into one Android account. Each now has its
     * own Android account (see [SystemAccountPlanner]), and the prefix still scopes every query and
     * every delete, which is why it ends in a separator that cannot appear in an id.
     */
    fun prefix(accountId: String): String = "$accountId|"

    /** The `SOURCE_ID` / `_SYNC_ID` for one cached row. */
    fun sourceId(accountId: String, href: String): String = prefix(accountId) + href

    /**
     * What "has this row changed" is decided on.
     *
     * The server's etag when there is one, because that is exactly the question an etag answers.
     * Falling back to the content itself rather than to a constant: a server that omits etags would
     * otherwise make every card look unchanged forever, and the first edit would never appear.
     */
    fun fingerprint(etag: String?, raw: String): String =
        etag?.takeIf { it.isNotBlank() } ?: raw.hashCode().toString()

    /** True when both providers can be written. See the class note on why this is only a check. */
    fun hasPermissions(context: Context): Boolean =
        listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Other installed apps that publish CardDAV/CalDAV collections into the same two system
     * providers Gridlink's mirror writes to.
     *
     * DAVx5 and PeopleSync are the same codebase under two package names; both are here because
     * either one is equally capable of syncing the very collections this account already carries.
     * Declared in `<queries>` so Android 11+ package visibility does not hide them.
     */
    private val OTHER_SYNCER_PACKAGES = listOf(
        "at.bitfire.davdroid",
        "com.messageconcept.peoplesyncclient",
    )

    /**
     * The label of an installed DAV syncer, or null when there is none.
     *
     * ## Why this only warns
     * Two syncers publishing the same collections corrupts nothing: each writes under its own
     * AccountManager account, and an edit to either copy goes to the server through the app that
     * owns that copy (the other then downloads it like any server change), so neither overwrites
     * the other's rows. What it does is show every
     * event twice in the system Calendar app, once per source, which looks like a bug and is
     * nobody's fault. So the user is told and then left to decide (Tate, 2026-08-16): blocking
     * the switch would also block the case where DAVx5 is installed for an entirely different
     * account and there is no overlap at all.
     *
     * 🔴 Deliberately checks only whether the app is INSTALLED, not whether it syncs this same
     * account. Reading that would mean reaching into another app's accounts and matching collection
     * URLs against its internals, which is a lot of fragile work to sharpen a dialog the user can
     * already answer for themselves.
     */
    fun otherSyncerLabel(context: Context): String? {
        val pm = context.packageManager
        for (name in OTHER_SYNCER_PACKAGES) {
            val info = runCatching { pm.getApplicationInfo(name, 0) }.getOrNull() ?: continue
            return pm.getApplicationLabel(info).toString()
        }
        return null
    }

    /** The permissions [hasPermissions] wants, for the Settings screen to request in one go. */
    val PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    /**
     * Register the accounts the current settings call for, unregister the rest, and ask for a sync.
     *
     * Safe to call as often as convenient: registering an account that exists is a no-op, and the
     * sync request coalesces. Called on every app start, so an account added or removed inside
     * Gridlink reaches the system without the user going back to Settings.
     */
    suspend fun apply(context: Context, accountStore: AccountStore, settings: SettingsRepository) {
        val on = settings.systemAccountMirror.first() && hasPermissions(context)
        val sources = accountStore.accounts().map { stored ->
            MirrorSource(
                id = stored.id,
                label = stored.label(),
                username = stored.username,
                host = hostOf(stored),
                wanted = on && (stored.syncSelection.calendar || stored.syncSelection.contacts),
            )
        }
        val registered = GridlinkSystemAccount.all(context).map {
            RegisteredAccount(it.name, GridlinkSystemAccount.accountId(context, it))
        }
        val plan = SystemAccountPlanner.plan(sources, registered)

        for (name in plan.remove) {
            Log.i(TAG, "unregistering a system account; its mirrored rows go with it")
            GridlinkSystemAccount.remove(context, GridlinkSystemAccount.account(context, name))
        }
        for ((name, id) in plan.keep) GridlinkSystemAccount.ensure(context, name, id)
        // An adopted 2.1.3 account is synced straight away too: its rows are still read-only in
        // the system apps until the first pass of this version rewrites them.
        for ((name, id) in plan.adopt + plan.create) {
            GridlinkSystemAccount.requestSync(GridlinkSystemAccount.ensure(context, name, id))
        }
        Log.i(
            TAG,
            "system accounts: keep ${plan.keep.size}, adopt ${plan.adopt.size}, " +
                "create ${plan.create.size}, remove ${plan.remove.size}",
        )
    }

    /**
     * The server a mail account lives on, for telling apart two accounts that read the same. The
     * IMAP host for IMAP, the host of the session URL otherwise.
     */
    private fun hostOf(stored: StoredAccount): String {
        if (stored.protocol == MailProtocol.IMAP && stored.imapHost.isNotBlank()) return stored.imapHost
        return runCatching { java.net.URI(stored.server.trim()).host }.getOrNull()
            ?: stored.server.trim().substringAfter("://").substringBefore('/')
    }

    /** Ask both adapters to run now, for every registered account. */
    fun requestSync(context: Context) {
        GridlinkSystemAccount.all(context).forEach { GridlinkSystemAccount.requestSync(it) }
    }

    /**
     * The local account ids that publish under [account]: the one stored on it.
     *
     * A 2.1.3 account with no id yet is matched by login, but only when that login is unambiguous;
     * otherwise nothing is published until [apply] has sorted it out on the next app start, which
     * is better than writing one account's rows into another's.
     */
    suspend fun accountIdsFor(context: Context, account: Account, accountStore: AccountStore): List<String> {
        val accounts = accountStore.accounts()
        val id = GridlinkSystemAccount.accountId(context, account)
        if (id == null) {
            // singleOrNull is null when two accounts share the login, which is the point.
            return listOfNotNull(accounts.singleOrNull { it.username == account.name }?.id)
        }
        return accounts.filter { it.id == id }.map { it.id }
    }
}
