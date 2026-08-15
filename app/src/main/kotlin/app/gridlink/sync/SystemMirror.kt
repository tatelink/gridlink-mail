package app.gridlink.sync

import android.Manifest
import android.accounts.Account
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.gridlink.core.data.account.AccountStore
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
     * 🔴 The local account id, not the server href. Two mail accounts that share a login are one
     * Android account (see [GridlinkSystemAccount]), and their hrefs come out of the same server
     * namespace, so an href alone would have one account's contacts overwriting the other's. The
     * prefix is also what scopes every query and every delete, which is why it ends in a separator
     * that cannot appear in an id.
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
        val wanted = if (settings.systemAccountMirror.first() && hasPermissions(context)) {
            accountStore.accounts()
                .filter { it.syncSelection.calendar || it.syncSelection.contacts }
                .map { it.username }
                .filter { it.isNotBlank() }
                .toSet()
        } else {
            emptySet()
        }

        val existing = GridlinkSystemAccount.all(context)
        for (account in existing) {
            if (account.name !in wanted) {
                Log.i(TAG, "unregistering ${account.name}; its mirrored rows go with it")
                GridlinkSystemAccount.remove(context, account)
            }
        }
        for (name in wanted) {
            val account = GridlinkSystemAccount.ensure(context, name)
            if (existing.none { it.name == name }) GridlinkSystemAccount.requestSync(account)
        }
    }

    /** Ask both adapters to run now, for every registered account. */
    fun requestSync(context: Context) {
        GridlinkSystemAccount.all(context).forEach { GridlinkSystemAccount.requestSync(it) }
    }

    /**
     * The local account ids that publish under [account].
     *
     * Normally one. More than one when several mail accounts share a login, which is the case the
     * [prefix] scheme exists for.
     */
    suspend fun accountIdsFor(account: Account, accountStore: AccountStore): List<String> =
        accountStore.accounts().filter { it.username == account.name }.map { it.id }
}
