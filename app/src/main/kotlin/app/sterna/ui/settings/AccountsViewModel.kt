package app.sterna.ui.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.mail.OAuthDeniedException
import app.sterna.core.data.mail.OAuthProvider
import app.sterna.core.data.pgp.PgpResult
import app.sterna.push.NewMailNotifier
import app.sterna.push.PushController
import app.sterna.ui.connect.oauthFailureMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the Accounts list and per-account detail screens in Settings. */
class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val storage = application.container.storageRepository
    private val mail = application.container.mailRepository
    private val pgp = application.container.pgpEngine

    private val _accounts = MutableStateFlow(store.accounts())
    val accounts = _accounts.asStateFlow()

    private val _currentId = MutableStateFlow(store.currentId())
    val currentId = _currentId.asStateFlow()

    /** Imported accounts still awaiting their one-time sign-in (inert, not yet dismissed). Reactive:
     *  derived from [accounts] so signing one in or dismissing it drops it from the Backup list. */
    val pendingImportAccounts: StateFlow<List<StoredAccount>> = accounts
        .map { list -> list.filter { it.importPending && store.credentials(it.id) == null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.pendingImportAccounts())

    /** Whether an account has a stored credential (false == inert, awaiting sign-in). */
    fun isSignedIn(id: String): Boolean = store.credentials(id) != null

    /** Swipe-dismiss: disconnect and REMOVE the imported account entirely (it leaves the account
     *  list; the user can re-import it later). Undoable via [restoreImport]. */
    fun dismissImport(id: String) {
        store.remove(id)
        refresh()
    }

    /** Undo a dismiss: re-add the removed account, back on the "accounts to sign in" list. */
    fun restoreImport(account: StoredAccount) {
        store.readdImportedAccount(account)
        refresh()
    }

    /** Cached-message count for the account detail screen currently open. */
    private val _cacheCount = MutableStateFlow(0)
    val cacheCount = _cacheCount.asStateFlow()

    /** "Test connection" result on the account detail screen. */
    sealed interface ConnTest {
        data object Idle : ConnTest
        data object Testing : ConnTest
        data object Ok : ConnTest
        data class Failed(val message: String) : ConnTest
    }

    private val _connTest = MutableStateFlow<ConnTest>(ConnTest.Idle)
    val connTest = _connTest.asStateFlow()

    fun clearConnTest() { _connTest.value = ConnTest.Idle }

    /**
     * Try the (possibly edited) server settings without saving. A blank password
     * falls back to the stored one, so testing after only changing the host works.
     */
    fun testConnection(
        accountId: String,
        server: String,
        username: String,
        password: String,
        isImap: Boolean,
        imapHost: String,
        imapPort: Int?,
        imapSecurity: ConnectionSecurity,
        smtpHost: String,
        smtpPort: Int?,
        smtpSecurity: ConnectionSecurity,
    ) {
        _connTest.value = ConnTest.Testing
        viewModelScope.launch {
            val pw = password.ifBlank { store.credentials(accountId)?.password.orEmpty() }
            val credentials = AccountCredentials(
                server = server.trim(),
                username = username.trim(),
                password = pw,
                id = accountId,
                protocol = if (isImap) MailProtocol.IMAP else MailProtocol.JMAP,
                imap = if (isImap) MailEndpoint(imapHost.trim(), imapPort ?: 0, imapSecurity) else null,
                smtp = if (isImap) MailEndpoint(smtpHost.trim(), smtpPort ?: 0, smtpSecurity) else null,
                // Keep the stored auth mode so API-token accounts test with Bearer, not Basic.
                authType = store.account(accountId)?.authType ?: AuthType.BASIC,
            )
            _connTest.value = mail.testConnection(credentials).fold(
                onSuccess = { ConnTest.Ok },
                onFailure = { ConnTest.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    /** Re-read the store after any change so the UI reflects the latest state. */
    fun refresh() {
        _accounts.value = store.accounts()
        _currentId.value = store.currentId()
    }

    fun account(id: String): StoredAccount? = store.account(id)

    fun syncWindow(id: String): SyncWindow = store.syncWindow(id)

    fun setSyncWindow(id: String, window: SyncWindow) {
        store.setSyncWindow(id, window)
        refresh()
    }

    /** Set the account's accent colour (ARGB), or null for auto. */
    fun setColor(id: String, color: Int?) {
        store.setColor(id, color)
        refresh()
    }

    /** Enable/disable new-mail notifications for an account; re-arm push to apply. */
    fun setNotificationsEnabled(id: String, enabled: Boolean) {
        store.setNotificationsEnabled(id, enabled)
        if (enabled) {
            // The account's baselines froze while it was excluded from every diff pass;
            // drop them so re-enabling reseeds silently instead of bursting weeks of mail.
            NewMailNotifier.clear(getApplication(), id)
        }
        refresh()
        PushController.apply(getApplication(), userInitiated = true)
    }

    /** Load this account's cached-message count into [cacheCount]. */
    fun loadCacheCount(id: String) {
        viewModelScope.launch { _cacheCount.value = storage.accountMessageCount(id) }
    }

    /** Clear just this account's cached mail, then refresh the displayed count. */
    fun clearAccountCache(id: String) {
        viewModelScope.launch {
            storage.clearAccountCache(id)
            mail.resetSyncState()
            _cacheCount.value = storage.accountMessageCount(id)
        }
    }

    fun switchTo(id: String) {
        store.setCurrent(id)
        refresh()
    }

    /** Persist edits. A blank [password] keeps the existing one. */
    fun save(
        id: String,
        accountName: String,
        server: String,
        username: String,
        password: String,
        signature: String? = null,
        identities: List<StoredIdentity>? = null,
        imapHost: String? = null,
        imapPort: Int? = null,
        imapSecurity: ConnectionSecurity? = null,
        smtpHost: String? = null,
        smtpPort: Int? = null,
        smtpSecurity: ConnectionSecurity? = null,
    ) {
        store.updateAccount(
            id, server = server, username = username, accountName = accountName, signature = signature,
            imapHost = imapHost, imapPort = imapPort, imapSecurity = imapSecurity,
            smtpHost = smtpHost, smtpPort = smtpPort, smtpSecurity = smtpSecurity,
        )
        if (identities != null) store.setIdentities(id, identities)
        if (password.isNotBlank()) {
            store.updatePassword(id, password)
            // Saving a password signs in an inert BASIC import — take it off the pending list.
            store.setImportPending(id, false)
        }
        refresh()
    }

    // --- Sign in an inert (imported) account via Microsoft OAuth device flow ---

    /** Device-flow sign-in state for the account detail screen of an inert OAuth account. */
    sealed interface AccountSignIn {
        data object Idle : AccountSignIn
        data object Starting : AccountSignIn
        data class Approval(
            val userCode: String,
            val verificationUri: String,
            val verificationUriComplete: String?,
        ) : AccountSignIn
        data object Connecting : AccountSignIn
        data class Failed(val message: String, val offerAppPassword: Boolean) : AccountSignIn
        data object Success : AccountSignIn
    }

    private val _accountSignIn = MutableStateFlow<AccountSignIn>(AccountSignIn.Idle)
    val accountSignIn: StateFlow<AccountSignIn> = _accountSignIn.asStateFlow()
    private var accountSignInJob: Job? = null

    /** Launch the Microsoft device flow for an inert OAuth [accountId] and drive it to completion. */
    fun startAccountOAuth(accountId: String) {
        val account = store.account(accountId) ?: return
        val provider = OAuthProvider.forImapHost(account.imapHost) ?: return
        if (_accountSignIn.value != AccountSignIn.Idle && _accountSignIn.value !is AccountSignIn.Failed) return
        _accountSignIn.value = AccountSignIn.Starting
        accountSignInJob = viewModelScope.launch {
            val result = mail.runProviderDeviceFlow(provider) { device ->
                _accountSignIn.value = AccountSignIn.Approval(
                    device.userCode, device.verificationUri, device.verificationUriComplete,
                )
            }
            result.fold(
                onSuccess = { tokens ->
                    _accountSignIn.value = AccountSignIn.Connecting
                    runCatching { mail.signInImportedOAuth(accountId, provider, tokens) }.fold(
                        onSuccess = {
                            store.setImportPending(accountId, false)
                            refresh()
                            _accountSignIn.value = AccountSignIn.Success
                        },
                        onFailure = { e ->
                            _accountSignIn.value = AccountSignIn.Failed(
                                e.message ?: "sign-in failed", offerAppPassword = true,
                            )
                        },
                    )
                },
                onFailure = { e ->
                    val msg = if (e is OAuthDeniedException) {
                        oauthFailureMessage(getApplication(), e.failure)
                    } else {
                        e.message ?: "sign-in failed"
                    }
                    _accountSignIn.value = AccountSignIn.Failed(msg, offerAppPassword = true)
                },
            )
        }
    }

    /** Cancel an in-progress device flow; the account stays inert and retryable. */
    fun cancelAccountOAuth() {
        accountSignInJob?.cancel()
        accountSignInJob = null
        _accountSignIn.value = AccountSignIn.Idle
    }

    /** Reset sign-in state (e.g. on entering/leaving the account detail screen). */
    fun resetAccountSignIn() { _accountSignIn.value = AccountSignIn.Idle }

    /** OAuth→app-password fallback: drop OAuth material so a password field signs the account in. */
    fun switchAccountToAppPassword(accountId: String) {
        accountSignInJob?.cancel()
        accountSignInJob = null
        store.convertToBasicAuth(accountId)
        refresh()
        _accountSignIn.value = AccountSignIn.Idle
    }

    // --- OpenPGP (OpenKeychain) ---

    /** Whether an OpenPGP provider is installed and bindable. */
    private val _pgpAvailable = MutableStateFlow(false)
    val pgpAvailable = _pgpAvailable.asStateFlow()

    /** Sign-key chooser round-trip state for the account detail screen. */
    sealed interface PgpSetup {
        data object Idle : PgpSetup
        data class NeedsInteraction(val pendingIntent: PendingIntent) : PgpSetup
        data class Failed(val message: String?) : PgpSetup
    }

    private val _pgpSetup = MutableStateFlow<PgpSetup>(PgpSetup.Idle)
    val pgpSetup = _pgpSetup.asStateFlow()

    fun refreshPgpAvailable() {
        viewModelScope.launch { _pgpAvailable.value = pgp.isAvailable() }
    }

    fun clearPgpSetup() { _pgpSetup.value = PgpSetup.Idle }

    /**
     * Open the provider's sign-key chooser for this account (hinted with its
     * address). On success the key is persisted and PGP enabled; a
     * [PgpSetup.NeedsInteraction] state asks the UI to run the pending intent
     * and call back with its result Intent.
     */
    fun choosePgpKey(accountId: String, interactionResult: Intent? = null) {
        val account = store.account(accountId) ?: return
        viewModelScope.launch {
            when (val result = pgp.getSignKeyId(account.username, interactionResult)) {
                is PgpResult.Success -> {
                    store.setPgp(
                        accountId,
                        enabled = true,
                        signKeyId = result.value,
                        encryptByDefault = account.pgpEncryptByDefault,
                    )
                    refresh()
                    _pgpSetup.value = PgpSetup.Idle
                }
                is PgpResult.UserInteractionRequired ->
                    _pgpSetup.value = PgpSetup.NeedsInteraction(result.pendingIntent)
                is PgpResult.Error -> _pgpSetup.value = PgpSetup.Failed(result.message)
                PgpResult.NotAvailable -> _pgpSetup.value = PgpSetup.Failed(null)
            }
        }
    }

    /** Toggle PGP off (keeps the chosen key), or update encrypt-by-default. */
    fun setPgp(accountId: String, enabled: Boolean, encryptByDefault: Boolean) {
        val account = store.account(accountId) ?: return
        store.setPgp(accountId, enabled, account.pgpSignKeyId, encryptByDefault)
        refresh()
    }

    /**
     * Sign out and purge that account's cached mail + the attachment cache. Signing out a login
     * cascades to the JMAP sub-accounts linked to it (issue #31): they share its credential, so
     * they cannot outlive it — each has its cache, notification baseline and push torn down too.
     */
    fun signOut(id: String) {
        val app = getApplication<Application>()
        // Everything this sign-out will remove, resolved BEFORE removal so UnifiedPush teardown
        // (which needs the credentials) and the cache purge can run per removed account.
        val target = store.account(id)
        val toRemove = buildList {
            add(id)
            if (target != null && !target.isLinked) addAll(store.linkedAccounts(id).map { it.id })
        }.distinct()
        store.allCredentials().filter { it.id in toRemove }.forEach {
            app.container.unifiedPushManager.teardown(it)
        }
        val removed = store.removeCascading(id).ifEmpty { toRemove }
        removed.forEach { NewMailNotifier.clear(app, it) }
        refresh()
        mail.resetSyncState()
        viewModelScope.launch {
            removed.forEach {
                mail.disconnectImap(it)
                storage.purgeAccount(it)
            }
        }
        PushController.apply(app, userInitiated = true)
    }
}
