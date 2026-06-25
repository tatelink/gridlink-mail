package app.sterna.ui.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OAuthProvider
import app.sterna.core.jmap.DeviceAuthorization
import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.OAuthMetadata
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** Device flow started: show the user code and wait for browser approval. */
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
    ) : ConnectState
    data class Error(val message: String) : ConnectState
}

class ConnectViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private var oauthJob: Job? = null

    init {
        observeOutlookSignIn()
    }

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

    /**
     * OAuth device flow (RFC 8628): discover the OAuth server for the email's
     * domain, start a device authorization, show the user code, then poll until
     * the user approves in a browser. The password field is ignored.
     */
    fun connectOAuth(email: String, server: String, accountName: String) {
        if (busy()) return
        val emailTrim = email.trim()
        // If the user entered a server (advanced), discover OAuth there; otherwise
        // derive candidate hosts from the email domain.
        val candidates = if (server.isNotBlank()) listOf(server.trim()) else Jmap.autodiscoverHosts(emailTrim)
        _state.value = ConnectState.Discovering
        oauthJob = viewModelScope.launch {
            var metadata: OAuthMetadata? = null
            var host: String? = null
            for (h in candidates) {
                metadata = runCatching { container.mailRepository.discoverOAuth(h) }.getOrNull()
                if (metadata != null) { host = h; break }
            }
            if (metadata == null || host == null) {
                _state.value = ConnectState.Error(string(R.string.connect_oauth_unsupported))
                return@launch
            }
            val device = runCatching { container.mailRepository.startDeviceAuthorization(metadata) }.getOrNull()
            if (device == null) {
                _state.value = ConnectState.Error(string(R.string.connect_oauth_failed))
                return@launch
            }
            _state.value = ConnectState.AwaitingApproval(
                device.userCode, device.verificationUri, device.verificationUriComplete,
            )
            pollForToken(host, metadata, device, emailTrim, accountName)
        }
    }

    private suspend fun pollForToken(
        host: String,
        metadata: OAuthMetadata,
        device: DeviceAuthorization,
        email: String,
        accountName: String,
    ) {
        var interval = device.interval.coerceAtLeast(1).toLong()
        val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            when (val result = container.mailRepository.pollDeviceToken(metadata, device.deviceCode)) {
                is DeviceTokenResult.Success -> {
                    _state.value = ConnectState.Connecting
                    val outcome = runCatching {
                        container.mailRepository.addOAuthAccount(host, email, metadata, result.tokens, accountName)
                    }
                    _state.value = outcome.fold(
                        onSuccess = { ConnectState.Connected },
                        onFailure = { ConnectState.Error(it.message ?: it.javaClass.simpleName) },
                    )
                    return
                }
                DeviceTokenResult.Pending -> Unit
                DeviceTokenResult.SlowDown -> interval += 5
                is DeviceTokenResult.Failed -> {
                    _state.value = ConnectState.Error(string(R.string.connect_oauth_denied))
                    return
                }
            }
        }
        _state.value = ConnectState.Error(string(R.string.connect_oauth_expired))
    }

    /**
     * OAuth device flow for Outlook/Microsoft over IMAP+SMTP with XOAUTH2. Delegates to the
     * app-scoped [OutlookSignIn] so the token poll survives the round-trip to the browser
     * (backgrounding / app lock / this screen being popped) — see [OutlookSignIn]. The
     * email labels the account; the password field is ignored.
     */
    fun connectOutlookOAuth(email: String, accountName: String) {
        if (busy()) return
        if (!OAuthProvider.MICROSOFT.isConfigured) {
            _state.value = ConnectState.Error(string(R.string.connect_oauth_provider_unconfigured))
            return
        }
        val emailTrim = email.trim()
        if (emailTrim.isBlank()) {
            _state.value = ConnectState.Error(string(R.string.connect_oauth_need_email))
            return
        }
        container.outlookSignIn.start(emailTrim, accountName)
    }

    /** Mirror the app-scoped Outlook sign-in into this screen's state. */
    private fun observeOutlookSignIn() {
        viewModelScope.launch {
            container.outlookSignIn.progress.collect { p ->
                _state.value = when (p) {
                    OutlookProgress.Idle -> return@collect
                    OutlookProgress.Starting -> ConnectState.Discovering
                    is OutlookProgress.AwaitingApproval ->
                        ConnectState.AwaitingApproval(p.userCode, p.verificationUri, p.verificationUriComplete)
                    OutlookProgress.Connecting -> ConnectState.Connecting
                }
            }
        }
        viewModelScope.launch {
            container.outlookSignIn.outcomes.collect { o ->
                _state.value = when (o) {
                    OutlookOutcome.Success -> ConnectState.Connected
                    is OutlookOutcome.Error -> ConnectState.Error(o.message)
                }
            }
        }
    }

    /** Cancel an in-progress device flow and return to the form. */
    fun cancelOAuth() {
        oauthJob?.cancel()
        oauthJob = null
        container.outlookSignIn.cancel()
        _state.value = ConnectState.Idle
    }

    private fun busy() = _state.value is ConnectState.Connecting ||
        _state.value is ConnectState.Discovering ||
        _state.value is ConnectState.AwaitingApproval

    private fun string(resId: Int) = getApplication<Application>().getString(resId)

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
                // A rejected IMAP login surfaces as "LOGIN … failed" / "AUTHENTICATE …"
                // (the command verb is redacted, so no password leaks). On those, point
                // the user at app passwords — most big providers refuse the normal one.
                val msg = t.message.orEmpty()
                val authRejected = msg.contains("LOGIN", ignoreCase = true) ||
                    msg.contains("AUTHENTICATE", ignoreCase = true)
                _state.value = ConnectState.Error(
                    if (authRejected) {
                        string(R.string.connect_bad_credentials) + " " +
                            string(R.string.connect_provider_app_password_note)
                    } else {
                        t.message ?: t.javaClass.simpleName
                    },
                )
            }
        }
    }
}
