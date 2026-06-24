package app.jmail.ui.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.R
import app.jmail.container
import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.data.account.ConnectionSecurity
import app.jmail.core.data.account.MailEndpoint
import app.jmail.core.data.account.MailProtocol
import app.jmail.core.data.mail.MailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the connect/account-setup screen. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data object Discovering : ConnectState
    data object Connected : ConnectState
    /** Autodiscovery found no server; the user must enter it manually. */
    data object NeedsServer : ConnectState
    data class Error(val message: String) : ConnectState
}

class ConnectViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    fun connect(server: String, username: String, password: String, accountName: String) {
        if (_state.value is ConnectState.Connecting || _state.value is ConnectState.Discovering) return
        _state.value = ConnectState.Connecting
        viewModelScope.launch { finishJmapConnect(server.trim(), username, password, accountName) }
    }

    /**
     * Autodiscovery path (RFC 8620 §2.2): the user gives only their email +
     * password; we probe the email domain's `/.well-known/jmap` to find the
     * server. On success we connect; if nothing responds we ask for the server
     * manually ([ConnectState.NeedsServer]); a credential rejection is reported
     * as such.
     */
    fun connectAuto(email: String, password: String, accountName: String) {
        if (_state.value is ConnectState.Connecting || _state.value is ConnectState.Discovering) return
        _state.value = ConnectState.Discovering
        viewModelScope.launch {
            val result = runCatching {
                container.mailRepository.discoverJmapServer(email.trim(), password)
            }.getOrElse { MailRepository.DiscoveryResult.NotFound }
            when (result) {
                is MailRepository.DiscoveryResult.Found -> {
                    _state.value = ConnectState.Connecting
                    finishJmapConnect(result.server, email, password, accountName)
                }
                MailRepository.DiscoveryResult.BadCredentials ->
                    _state.value = ConnectState.Error(getApplication<Application>().getString(R.string.connect_bad_credentials))
                MailRepository.DiscoveryResult.NotFound ->
                    _state.value = ConnectState.NeedsServer
            }
        }
    }

    /** Validate against [server], persist on success. Runs in the caller's coroutine. */
    private suspend fun finishJmapConnect(server: String, username: String, password: String, accountName: String) {
        try {
            // Validate the credentials and prime the cache by loading the inbox.
            val credentials = AccountCredentials(server, username.trim(), password)
            val meta = container.mailRepository.refresh(credentials)
            // Only persist once we know they work. A blank name falls back to the address.
            container.accountStore.add(server, username, password, accountName.trim())
            container.accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
            _state.value = ConnectState.Connected
        } catch (t: Throwable) {
            _state.value = ConnectState.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    fun connectImap(
        username: String,
        password: String,
        accountName: String,
        imapHost: String,
        imapPort: Int,
        imapSecurity: ConnectionSecurity,
        smtpHost: String,
        smtpPort: Int,
        smtpSecurity: ConnectionSecurity,
    ) {
        if (_state.value is ConnectState.Connecting) return
        _state.value = ConnectState.Connecting
        viewModelScope.launch {
            try {
                val credentials = AccountCredentials(
                    server = "",
                    username = username.trim(),
                    password = password,
                    protocol = MailProtocol.IMAP,
                    imap = MailEndpoint(imapHost.trim(), imapPort, imapSecurity),
                    smtp = MailEndpoint(smtpHost.trim(), smtpPort, smtpSecurity),
                )
                // Validate by connecting + loading the inbox before persisting.
                val meta = container.mailRepository.refresh(credentials)
                container.accountStore.add(
                    server = "",
                    username = username,
                    password = password,
                    accountName = accountName.trim(),
                    protocol = MailProtocol.IMAP,
                    imapHost = imapHost,
                    imapPort = imapPort,
                    imapSecurity = imapSecurity,
                    smtpHost = smtpHost,
                    smtpPort = smtpPort,
                    smtpSecurity = smtpSecurity,
                )
                container.accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
                _state.value = ConnectState.Connected
            } catch (t: Throwable) {
                _state.value = ConnectState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
