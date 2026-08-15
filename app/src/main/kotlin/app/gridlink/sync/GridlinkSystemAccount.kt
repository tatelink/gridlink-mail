package app.gridlink.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import app.gridlink.R

/**
 * The AccountManager account Gridlink publishes contacts and calendars under.
 *
 * ## 🔴 Why an account has to exist at all
 * Nothing here is decoration. Both system providers key their rows on an account, and both sweep
 * rows whose account is not registered with AccountManager: a calendar written under an unknown
 * account name disappears on the provider's next cleanup, and so do its events. So the mirror
 * cannot be "just write into the provider". The account is the anchor the rows hang off, and the
 * [GridlinkAuthenticator] exists only so the system has something to bind when it asks who owns it.
 *
 * ## One Android account per mail account
 * Named after the mail account's own login, so Settings → Accounts reads the way the user thinks
 * about it. Two [app.gridlink.core.data.account.StoredAccount]s that share a login (a linked
 * sub-account, Codeberg #31) share one Android account, which is correct: they are one identity on
 * one server. Their rows still do not collide, because every row carries a source id built from the
 * *local* account id — see [SystemMirror.sourceId].
 *
 * ## What this deliberately does NOT do
 * No password is ever handed to AccountManager. `addAccountExplicitly` is called with a null
 * password on purpose: the real credential lives encrypted in the app's own store, and copying it
 * into a system database that other apps' authenticators share would be a downgrade for nothing.
 * The account is an anchor, not a credential.
 */
object GridlinkSystemAccount {

    private const val TAG = "GridlinkSync"

    /** Contacts provider authority the sync adapter registers against. */
    val CONTACTS_AUTHORITY: String = ContactsContract.AUTHORITY

    /** Calendar provider authority the sync adapter registers against. */
    val CALENDAR_AUTHORITY: String = CalendarContract.AUTHORITY

    /**
     * The account type, which is the package name.
     *
     * 🔴 Read from a resource, not a constant, because `-PtestApp` installs a second package beside
     * production. Two packages claiming one account type is not a cosmetic clash: whichever
     * authenticator the system happens to bind then owns both apps' rows, and removing one app
     * takes the other's contacts with it.
     */
    fun type(context: Context): String = context.getString(R.string.gridlink_account_type)

    fun account(context: Context, name: String): Account = Account(name, type(context))

    /** Every Gridlink account currently registered. Own-type access needs no GET_ACCOUNTS. */
    fun all(context: Context): List<Account> =
        AccountManager.get(context).getAccountsByType(type(context)).toList()

    /**
     * Register [name] if it is not already there, and mark both providers syncable.
     *
     * Returns the account either way. `addAccountExplicitly` returning false means it already
     * exists, which is the ordinary case on every run after the first and not a failure.
     */
    fun ensure(context: Context, name: String): Account {
        val account = account(context, name)
        val added = runCatching { AccountManager.get(context).addAccountExplicitly(account, null, null) }
            .onFailure { Log.w(TAG, "could not register $name", it) }
            .getOrDefault(false)
        if (added) Log.i(TAG, "registered system account for $name")
        for (authority in listOf(CONTACTS_AUTHORITY, CALENDAR_AUTHORITY)) {
            ContentResolver.setIsSyncable(account, authority, 1)
            ContentResolver.setSyncAutomatically(account, authority, true)
        }
        return account
    }

    /**
     * Unregister [account], which is also how its mirrored rows are deleted.
     *
     * 🔴 The removal IS the cleanup, and that is why the mirror can be switched off safely without
     * a delete pass of its own: both providers drop everything belonging to an account that no
     * longer exists. Doing it the other way round (delete the rows, keep the account) leaves an
     * empty Gridlink entry sitting in the system's account list forever.
     */
    fun remove(context: Context, account: Account) {
        runCatching { AccountManager.get(context).removeAccountExplicitly(account) }
            .onFailure { Log.w(TAG, "could not remove ${account.name}", it) }
    }

    /**
     * Ask the system to run both sync adapters for [account] now.
     *
     * MANUAL + EXPEDITED, because every caller is a user action (a switch turned on, an account
     * added). The system still batches and defers, which is fine: nothing here is time-critical.
     */
    fun requestSync(account: Account) {
        val extras = android.os.Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(account, CONTACTS_AUTHORITY, extras)
        ContentResolver.requestSync(account, CALENDAR_AUTHORITY, extras)
    }
}
