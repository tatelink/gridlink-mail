package app.gridlink.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import app.gridlink.MainActivity

/**
 * The authenticator behind [GridlinkSystemAccount], and it does almost nothing on purpose.
 *
 * ## 🔴 This holds no credentials and issues no tokens
 * An authenticator normally exists to prove who you are to a server. This one exists because
 * Android will not let an account exist without one, and the account exists because the contacts
 * and calendar providers delete rows belonging to accounts they cannot find. Gridlink's real
 * credentials stay in its own encrypted store; [getAuthToken] therefore refuses rather than
 * inventing a token, so no other app can ever ask the system for a way into this mail account.
 *
 * ## Adding an account from system Settings is not supported
 * [addAccount] returns an intent that simply opens Gridlink. A mail account is set up with a
 * server, a protocol, a password and possibly an OAuth round trip; reproducing that inside the
 * system's account-add flow would be a second setup path to keep correct forever. Sending the user
 * to the app they were going to have to open anyway is the honest answer.
 */
class GridlinkAuthenticator(private val context: Context) : AbstractAccountAuthenticator(context) {

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle = Bundle().apply {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        putParcelable(AccountManager.KEY_INTENT, intent)
    }

    /**
     * Refused, and the refusal is the feature. See the class note: there is no token to give.
     * `ERROR_CODE_UNSUPPORTED_OPERATION` is what a caller gets, rather than an empty token that
     * would look like success.
     */
    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, "Gridlink issues no auth tokens")
    }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle = Bundle()

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?,
    ): Bundle? = null

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle? = null

    /** No features are claimed, so every query answers false rather than guessing. */
    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?,
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}

/**
 * Binds [GridlinkAuthenticator] for the system.
 *
 * Exported so AccountManager (a different process) can bind it. Nothing sensitive is reachable
 * through it: every method above either returns an empty bundle or refuses.
 */
class GridlinkAuthenticatorService : Service() {

    private val authenticator: GridlinkAuthenticator by lazy { GridlinkAuthenticator(this) }

    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}
