package app.sterna.ui.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OAuthDeniedException
import app.sterna.core.data.mail.OAuthProvider
import app.sterna.core.jmap.BearerAuth
import app.sterna.core.jmap.DeviceAuthorization
import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapException
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

    private val _importSignIn = MutableStateFlow<ImportSignIn>(ImportSignIn.None)
    /** Drives the per-account password prompt shown after a settings import (see [beginImportSignIn]). */
    val importSignIn: StateFlow<ImportSignIn> = _importSignIn.asStateFlow()

    private var oauthJob: Job? = null
    private var importOAuthJob: Job? = null

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
     * API-token sign-in (issue #54): authenticate with a server-generated Bearer
     * token (e.g. a Fastmail API token) instead of a password. A blank [server]
     * autodiscovers from the email domain; otherwise the input is resolved
     * tolerantly (bare host, base URL, or a pasted session URL all work).
     */
    fun connectToken(server: String, email: String, token: String, accountName: String) {
        if (busy()) return
        val emailTrim = email.trim()
        val serverTrim = server.trim()
        if (serverTrim.isNotBlank()) {
            _state.value = ConnectState.Connecting
            viewModelScope.launch { finishTokenConnect(serverTrim, emailTrim, token, accountName) }
            return
        }
        _state.value = ConnectState.Discovering
        viewModelScope.launch {
            val result = runCatching {
                container.mailRepository.discoverJmapServer(emailTrim, password = "", token = token)
            }.getOrElse { MailRepository.DiscoveryResult.NotFound }
            when (result) {
                is MailRepository.DiscoveryResult.Found -> {
                    _state.value = ConnectState.Connecting
                    finishTokenConnect(result.server, emailTrim, token, accountName)
                }
                MailRepository.DiscoveryResult.BadCredentials ->
                    _state.value = ConnectState.Error(string(R.string.connect_token_rejected))
                MailRepository.DiscoveryResult.NotFound ->
                    _state.value = ConnectState.NeedsServer
            }
        }
    }

    /** Resolve the server input with the token, validate, persist. Mirrors [finishJmapConnect]. */
    private suspend fun finishTokenConnect(server: String, email: String, token: String, accountName: String) {
        try {
            val resolved = container.mailRepository.resolveJmapServerInput(server, BearerAuth(token))
            val credentials = AccountCredentials(resolved, email, token, authType = AuthType.API_TOKEN)
            val meta = container.mailRepository.refresh(credentials)
            container.accountStore.add(resolved, email, token, accountName.trim(), authType = AuthType.API_TOKEN)
            container.accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
            _state.value = ConnectState.Connected
        } catch (t: Throwable) {
            // A 401/403 with a token means the token itself was rejected — say so.
            val code = (t as? JmapException)?.httpCode
            _state.value = ConnectState.Error(
                if (code == 401 || code == 403) string(R.string.connect_token_rejected)
                else t.message ?: t.javaClass.simpleName,
            )
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

    // ---- post-import per-account sign-in ----

    /** One imported account still awaiting sign-in. [provider] is set only for OAUTH accounts on a
     *  known provider (Microsoft); null means basic-auth or an unknown XOAUTH2 server. */
    data class PendingAccount(
        val id: String,
        val label: String,
        val email: String,
        val authType: AuthType,
        val provider: OAuthProvider?,
    )

    /**
     * State of the "sign in to your imported accounts" step. After an import the user sees a
     * [Listing] of every pending account and drives it: tapping a row selects it ([SignInTarget]);
     * signing one in drops it off the list; swiping a row dismisses it. When the list empties we
     * either enter the app ([Done], at least one account is signed in) or fall back to the
     * add-account form ([None]).
     */
    sealed interface ImportSignIn {
        data object None : ImportSignIn
        data class Listing(val pending: List<PendingAccount>, val selected: SignInTarget? = null) : ImportSignIn
        data object Done : ImportSignIn
        data class Approval(val userCode: String, val verificationUri: String, val verificationUriComplete: String?)
    }

    /** The one account the user tapped to sign in, plus that account's in-progress sign-in state. */
    data class SignInTarget(
        val account: PendingAccount,
        val verifying: Boolean = false,
        val error: String? = null,
        /** Non-null while this OAuth account is awaiting browser approval (device flow). */
        val approval: ImportSignIn.Approval? = null,
        /** After a failed/declined OAuth sign-in, offer switching this account to an app password. */
        val offerAppPasswordFallback: Boolean = false,
        /** Render a password field even though the account imported as OAuth (fallback chosen). */
        val forcePassword: Boolean = false,
    )

    /** The inert imported accounts still awaiting sign-in, as [StoredAccount]s for the list UI.
     *  Re-read on each access so it reflects sign-ins/dismissals; the [importSignIn] flow drives
     *  the recomposition that re-reads it. */
    val pendingStoredAccounts: List<StoredAccount>
        get() = container.accountStore.pendingImportAccounts()

    /** Imported accounts still awaiting sign-in: inert (no stored secret), any auth type. */
    private fun pendingAccounts(): List<PendingAccount> =
        container.accountStore.pendingImportAccounts().map { a ->
            PendingAccount(
                id = a.id, label = a.label(), email = a.username,
                authType = a.authType,
                provider = if (a.authType == AuthType.OAUTH) OAuthProvider.forImapHost(a.imapHost) else null,
            )
        }

    /**
     * After an import, show the user-driven list of accounts to sign in. When nothing is pending we
     * either enter the app (something is already signed in) or fall back to the add-account form.
     */
    fun beginImportSignIn() {
        val p = pendingAccounts()
        _importSignIn.value = if (p.isEmpty()) finishOrIdle() else ImportSignIn.Listing(p)
    }

    /**
     * Re-enter the sign-in list for any still-unauthenticated imported account — e.g. the app was
     * killed mid-sign-in and relaunched. Does nothing when there are none (so a genuine first run
     * just shows the add-account form). Never overrides a list already on screen.
     */
    fun resumeImportSignIn() {
        if (_importSignIn.value is ImportSignIn.Listing) return
        val p = pendingAccounts()
        if (p.isNotEmpty()) _importSignIn.value = ImportSignIn.Listing(p)
    }

    private fun currentListing() = _importSignIn.value as? ImportSignIn.Listing

    /** Recompute the pending list, keeping [keepSelected] only if that account is still pending.
     *  When nothing is left, finish (enter the app) or fall back to the add-account form. */
    private fun refreshListing(keepSelected: SignInTarget? = currentListing()?.selected) {
        val p = pendingAccounts()
        _importSignIn.value =
            if (p.isEmpty()) finishOrIdle()
            else ImportSignIn.Listing(p, keepSelected?.takeIf { s -> p.any { it.id == s.account.id } })
    }

    private fun finishOrIdle(): ImportSignIn =
        if (container.accountStore.accounts().any { container.accountStore.credentials(it.id) != null }) ImportSignIn.Done
        else ImportSignIn.None

    /** The user tapped a row: open that account's inline sign-in. */
    fun selectImportAccount(id: String) {
        val listing = currentListing() ?: return
        val account = listing.pending.firstOrNull { it.id == id } ?: return
        _importSignIn.value = listing.copy(selected = SignInTarget(account))
    }

    /** Back to the plain list (cancel any in-progress device flow). */
    fun closeImportAccount() {
        importOAuthJob?.cancel(); importOAuthJob = null
        refreshListing(keepSelected = null)
    }

    /** Swipe-dismiss: disconnect and REMOVE the imported account entirely (it never appears in the
     *  account list; the user can re-import it later). Undoable via [restoreImportAccount]. */
    fun dismissImportAccount(id: String) {
        container.accountStore.remove(id)
        if (currentListing()?.selected?.account?.id == id) closeImportAccount() else refreshListing()
    }

    /** Undo a swipe-dismiss: re-add the removed account, back on the "to sign in" list. */
    fun restoreImportAccount(account: StoredAccount) {
        container.accountStore.readdImportedAccount(account)
        refreshListing()
    }

    /** Mutate the currently-selected [SignInTarget] in place, if any. */
    private fun updateSelected(block: (SignInTarget) -> SignInTarget) {
        val l = currentListing() ?: return
        val s = l.selected ?: return
        _importSignIn.value = l.copy(selected = block(s))
    }

    /** A sign-in succeeded: return to the list (the account drops off; an empty list enters the app). */
    private fun closeImportAccountThenAdvance() = refreshListing(keepSelected = null)

    /** Verify the selected imported account with [password]; on success save it and return to the list. */
    fun submitImportPassword(password: String) {
        val target = currentListing()?.selected ?: return
        if (target.verifying || password.isBlank()) return
        val account = container.accountStore.account(target.account.id) ?: return
        updateSelected { it.copy(verifying = true, error = null) }
        viewModelScope.launch {
            container.mailRepository.testConnection(credentialsFor(account, password)).fold(
                onSuccess = {
                    container.accountStore.updatePassword(target.account.id, password)
                    container.accountStore.setImportPending(target.account.id, false)
                    closeImportAccountThenAdvance()
                },
                onFailure = { e ->
                    val msg = e.message ?: string(R.string.connect_bad_credentials)
                    val authRejected = msg.contains("LOGIN", true) || msg.contains("AUTHENTICATE", true) || msg.contains("credential", true)
                    updateSelected {
                        it.copy(
                            verifying = false,
                            error = if (authRejected) msg + " " + string(R.string.connect_provider_app_password_note) else msg,
                            offerAppPasswordFallback = authRejected,
                        )
                    }
                },
            )
        }
    }

    /** Launch the Microsoft device flow for the selected OAuth account and drive it to completion. */
    fun startImportOAuth() {
        val target = currentListing()?.selected ?: return
        val provider = target.account.provider ?: return
        if (target.verifying || target.approval != null) return
        updateSelected { it.copy(verifying = true, error = null, offerAppPasswordFallback = false) }
        importOAuthJob = viewModelScope.launch {
            val result = container.mailRepository.runProviderDeviceFlow(provider) { device ->
                updateSelected {
                    it.copy(
                        verifying = false,
                        approval = ImportSignIn.Approval(device.userCode, device.verificationUri, device.verificationUriComplete),
                    )
                }
            }
            result.fold(
                onSuccess = { tokens ->
                    updateSelected { it.copy(approval = null, verifying = true, error = null) }
                    runCatching { container.mailRepository.signInImportedOAuth(target.account.id, provider, tokens) }.fold(
                        onSuccess = {
                            container.accountStore.setImportPending(target.account.id, false)
                            refreshListing(keepSelected = null)
                        },
                        onFailure = { e ->
                            updateSelected {
                                it.copy(
                                    verifying = false,
                                    error = e.message ?: string(R.string.connect_oauth_failed),
                                    offerAppPasswordFallback = true,
                                )
                            }
                        },
                    )
                },
                onFailure = { e ->
                    val msg = if (e is OAuthDeniedException) oauthFailureMessage(getApplication<Application>(), e.failure)
                    else (e.message ?: string(R.string.connect_oauth_failed))
                    updateSelected { it.copy(approval = null, verifying = false, error = msg, offerAppPasswordFallback = true) }
                },
            )
        }
    }

    /** Cancel an in-progress import device flow; the account stays selected and retryable. */
    fun cancelImportOAuth() {
        importOAuthJob?.cancel(); importOAuthJob = null
        updateSelected { it.copy(approval = null, verifying = false) }
    }

    /** Fallback: switch the selected account to manual app-password auth and show its password field. */
    fun switchImportToAppPassword() {
        importOAuthJob?.cancel(); importOAuthJob = null
        val target = currentListing()?.selected ?: return
        container.accountStore.convertToBasicAuth(target.account.id)
        updateSelected {
            it.copy(
                account = it.account.copy(authType = AuthType.BASIC, provider = null),
                forcePassword = true, offerAppPasswordFallback = false,
                approval = null, error = null, verifying = false,
            )
        }
    }

    private fun credentialsFor(a: StoredAccount, password: String) = AccountCredentials(
        server = a.server,
        username = a.username,
        password = password,
        id = a.id,
        protocol = a.protocol,
        imap = if (a.protocol == MailProtocol.IMAP) MailEndpoint(a.imapHost, a.imapPort, a.imapSecurity) else null,
        smtp = if (a.protocol == MailProtocol.IMAP) MailEndpoint(a.smtpHost, a.smtpPort, a.smtpSecurity) else null,
    )

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
    private fun string(resId: Int, vararg args: Any) = getApplication<Application>().getString(resId, *args)

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
