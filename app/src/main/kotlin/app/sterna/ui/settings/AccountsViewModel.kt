package app.sterna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.SyncWindow
import app.sterna.push.PushService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Backs the Accounts list and per-account detail screens in Settings. */
class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val storage = application.container.storageRepository
    private val mail = application.container.mailRepository

    private val _accounts = MutableStateFlow(store.accounts())
    val accounts = _accounts.asStateFlow()

    private val _currentId = MutableStateFlow(store.currentId())
    val currentId = _currentId.asStateFlow()

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
        refresh()
        PushService.start(getApplication())
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
        if (password.isNotBlank()) store.updatePassword(id, password)
        refresh()
    }

    /** Sign out and purge that account's cached mail + the attachment cache. */
    fun signOut(id: String) {
        store.remove(id)
        refresh()
        mail.resetSyncState()
        viewModelScope.launch {
            mail.disconnectImap(id)
            storage.purgeAccount(id)
        }
    }
}
