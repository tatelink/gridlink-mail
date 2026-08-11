package app.gridlink.ui.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gridlink.R
import app.gridlink.container
import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.account.AuthType
import app.gridlink.core.data.account.ConnectionSecurity
import app.gridlink.core.data.account.MailEndpoint
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.core.data.mail.MailRepository
import app.gridlink.core.data.mail.OAuthDeniedException
import app.gridlink.core.data.mail.OAuthProvider
import app.gridlink.core.data.mail.SignInFailure
import app.gridlink.core.data.mail.SignInLog
import app.gridlink.core.data.mail.SignInStep
import app.gridlink.core.data.mail.classifySignInFailure
import app.gridlink.net.hasUsableNetwork
import app.gridlink.core.data.net.ImapEndpoints
import app.gridlink.core.data.net.MailSrv
import app.gridlink.core.jmap.BearerAuth
import app.gridlink.core.jmap.DeviceAuthorization
import app.gridlink.core.jmap.DeviceTokenResult
import app.gridlink.core.jmap.JmapException
import app.gridlink.core.jmap.OAuthMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.UnknownHostException

/** UI state for the connect/account-setup screen. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data object Discovering : ConnectState
    data object Connected : ConnectState
    /**
     * Autodiscovery found no server; the user must enter it manually.
     *
     * 🔴 Reached ONLY when nothing answered at all, which is the one situation where "type your
     * server in" is the right instruction. Every other way discovery can fail — offline, a refused
     * port, a bad certificate, a server that answered too slowly — is an [Error] with its own
     * sentence now. Sending all of them here was how somebody on a dead network got told to go and
     * find a hostname.
     */
    data class NeedsServer(val details: List<SignInStep> = emptyList()) : ConnectState
    /** Device flow started: show the user code and wait for browser approval. */
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
    ) : ConnectState
    /**
     * The attempt failed. [details] is the step-by-step record of what was tried, for the user to
     * copy out: this app collects no telemetry, so a failure on somebody's own server reaches a
     * person who can act on it only if the person holding the phone can paste it into an email.
     * Empty for the paths that have no sweep to describe.
     */
    data class Error(val message: String, val details: List<SignInStep> = emptyList()) : ConnectState
}

/** True when a JMAP password sign-in is aimed at Fastmail — their endpoint only accepts API
 *  tokens (#54): the address is @fastmail.com/.fm, or the server points at api.fastmail.com. */
internal fun isFastmailTarget(email: String, server: String): Boolean {
    val domain = email.trim().substringAfterLast('@', "").lowercase()
    return domain == "fastmail.com" || domain == "fastmail.fm" ||
        server.contains("api.fastmail.com", ignoreCase = true)
}

/** The account an add resolved to: its id, and whether this add is what created it. */
internal data class AddedAccount(val id: String, val created: Boolean)

/**
 * What an add ended on: the account's id, and the priming failure it survived, if any.
 *
 * [primeFailure] is never a reason to undo the add — see [addAccountThenPrime]. It is carried out
 * so the screen can log it (and so a test can read it) instead of it vanishing silently.
 */
internal data class PrimedAccount(val id: String, val primeFailure: Throwable? = null)

/**
 * Add an account in the one order that cannot write mail under an empty account id (#121).
 *
 * Every add path used to build credentials with no id yet (`AccountCredentials.id` defaults to
 * `""`), hand them to the repository to "validate and prime the cache", and only then create the
 * account. The priming write is account-scoped, so a full page of inbox rows landed under
 * `accountId = ""` on every single account added — rows no account owns, that nothing ever
 * refreshes and no label can name.
 *
 * So the steps are separated and ordered here, once, for all three paths:
 *  1. [validate] proves the credentials **while writing nothing** (a session fetch / an IMAP
 *     login), which is what keeps the rule the flow was written around — a mistyped password
 *     must never leave an account behind;
 *  2. [persist] creates the account (or finds the one being re-authenticated) so an id exists;
 *  3. [prime] fills the cache with credentials **this function stamps** with that id — callers
 *     never build them, so no path can pass a blank one, and a fourth path added later inherits
 *     the guarantee by construction.
 *
 * ⛔ A FAILED PRIMING KEEPS THE ACCOUNT. Step 1 is what protects the user from a half-add: nothing
 * is persisted until the credentials are proven, so a mistyped password still leaves nothing
 * behind. Once they ARE proven, taking the account back out on a network hiccup costs more than it
 * saves:
 *  - it throws away credentials the server just accepted, over a transient failure;
 *  - `refresh()` persists the folder list before it rethrows, so removing the account afterwards
 *    leaves mailbox rows under an id no account owns — the very class of orphan #121 closes;
 *  - `AccountStore.remove()` moves the current account to the first remaining one whenever the
 *    removed id is the current one, and an add always makes the new account current. Undoing a
 *    fourth add therefore silently moved the user off the account she was reading;
 *  - the OAuth add path (`MailRepository.addOAuthAccount`) has always kept the account in this
 *    exact situation, and process death produces the same "account added, inbox not loaded yet"
 *    state anyway — which the first refresh repairs on its own, showing nothing.
 *
 * So the account stays and its cache fills on the next refresh; the caller gets the failure back in
 * [PrimedAccount.primeFailure] to log. Cancellation is not a failure: it unwinds untouched, so
 * leaving the screen mid-priming cannot be reported as an error.
 */
internal suspend fun addAccountThenPrime(
    probe: AccountCredentials,
    validate: suspend (AccountCredentials) -> Unit,
    persist: suspend () -> AddedAccount,
    prime: suspend (AccountCredentials) -> Unit,
): PrimedAccount {
    validate(probe)
    val account = persist()
    // The belt inside the flow: an id-less account cannot be primed, it can only be a bug.
    check(account.id.isNotBlank()) { "Refusing to prime the cache under a blank account id." }
    return try {
        prime(probe.copy(id = account.id))
        PrimedAccount(account.id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        PrimedAccount(account.id, primeFailure = t)
    }
}

class ConnectViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private val _importSignIn = MutableStateFlow<ImportSignIn>(ImportSignIn.None)
    /** Drives the per-account password prompt shown after a settings import (see [beginImportSignIn]). */
    val importSignIn: StateFlow<ImportSignIn> = _importSignIn.asStateFlow()

    private val _imapSuggestion = MutableStateFlow<ImapSuggestion?>(null)

    /**
     * The IMAP and submission servers the typed address' domain publishes over SRV, for the manual
     * setup form to fill itself in with. Null until a domain answers, which is most of them.
     */
    val imapSuggestion: StateFlow<ImapSuggestion?> = _imapSuggestion.asStateFlow()

    /** [ImapEndpoints] plus the domain that published them, so the form can say where they came from. */
    data class ImapSuggestion(val domain: String, val endpoints: ImapEndpoints)

    private var suggestionJob: Job? = null
    private var suggestedDomain: String? = null

    private var oauthJob: Job? = null
    private var importOAuthJob: Job? = null

    init {
        observeOutlookSignIn()
    }

    /**
     * Ask [email]'s domain where its IMAP and submission servers are (RFC 6186), if it has not been
     * asked already.
     *
     * ⚠️ This runs while the user is still typing, so it is keyed on the domain rather than the
     * address: everything after the `@` settles long before the local part does, and re-querying on
     * each keystroke would be a DNS lookup per character. A domain that answers nothing is
     * remembered as answered, so it is asked once and not again.
     *
     * Nothing here can fail loudly. The lookup already returns an empty result for a timeout, a
     * refusal or a malformed packet, and the runCatching is for the rest: the form is unchanged and
     * the user fills it in by hand, exactly as before this existed.
     */
    fun suggestImapEndpoints(email: String) {
        val domain = MailSrv.domainOf(email)
        if (domain == suggestedDomain) return
        suggestedDomain = domain
        suggestionJob?.cancel()
        _imapSuggestion.value = null
        if (domain == null) return
        suggestionJob = viewModelScope.launch {
            val endpoints = runCatching {
                container.mailRepository.discoverImapEndpoints(email.trim())
            }.getOrNull() ?: return@launch
            _imapSuggestion.value = ImapSuggestion(domain, endpoints)
        }
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
        val log = SignInLog()
        // Asked before anything is attempted, because a device with no route out produces a DNS
        // failure per candidate and a 20-second sweep whose honest summary is "you are offline".
        // Cheap, synchronous, and the same reading the send path uses for queued-vs-sent (#70).
        if (!hasUsableNetwork(getApplication())) {
            log.add("Checking this device's network", "no connection")
            _state.value = ConnectState.Error(string(R.string.connect_offline), log.steps())
            return
        }
        _state.value = ConnectState.Discovering
        viewModelScope.launch {
            val result = try {
                container.mailRepository.discoverJmapServer(email.trim(), password, log = log)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log.add("Looking for a server", t)
                MailRepository.DiscoveryResult.NotFound(classifySignInFailure(t), log.steps())
            }
            when (result) {
                is MailRepository.DiscoveryResult.Found -> {
                    _state.value = ConnectState.Connecting
                    finishJmapConnect(result.server, email, password, accountName, log)
                }
                is MailRepository.DiscoveryResult.BadCredentials ->
                    _state.value = ConnectState.Error(string(R.string.connect_bad_credentials), result.steps)
                is MailRepository.DiscoveryResult.NotFound -> {
                    val explained = discoveryFailureMessage(result.failure)
                    _state.value =
                        if (explained == null) ConnectState.NeedsServer(result.steps)
                        else ConnectState.Error(explained, result.steps)
                }
            }
        }
    }

    /**
     * The sentence for a discovery that found nothing, or null when the honest answer is "we simply
     * could not find it" and the user should be asked for the server ([ConnectState.NeedsServer]).
     *
     * ⚠️ Only the failures a user can act on get their own message. A guessed subdomain that does
     * not resolve is not a fault, it is the expected answer for most domains, so it stays null and
     * the flow ends where it always did: at the server field, which is already on screen.
     */
    private fun discoveryFailureMessage(failure: SignInFailure): String? = when (failure) {
        SignInFailure.OFFLINE -> string(R.string.connect_offline)
        SignInFailure.TLS -> string(R.string.connect_tls_failed)
        SignInFailure.REFUSED -> string(R.string.connect_refused)
        SignInFailure.TIMEOUT -> string(R.string.connect_timed_out)
        SignInFailure.REJECTED -> string(R.string.connect_credentials_rejected)
        SignInFailure.NOT_A_SERVER, SignInFailure.DNS, SignInFailure.OTHER -> null
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
        val log = SignInLog()
        viewModelScope.launch {
            val result = try {
                container.mailRepository.discoverJmapServer(emailTrim, password = "", token = token, log = log)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                log.add("Looking for a server", t)
                MailRepository.DiscoveryResult.NotFound(classifySignInFailure(t), log.steps())
            }
            when (result) {
                is MailRepository.DiscoveryResult.Found -> {
                    _state.value = ConnectState.Connecting
                    finishTokenConnect(result.server, emailTrim, token, accountName)
                }
                is MailRepository.DiscoveryResult.BadCredentials ->
                    _state.value = ConnectState.Error(string(R.string.connect_token_rejected), result.steps)
                is MailRepository.DiscoveryResult.NotFound -> {
                    val explained = discoveryFailureMessage(result.failure)
                    _state.value =
                        if (explained == null) ConnectState.NeedsServer(result.steps)
                        else ConnectState.Error(explained, result.steps)
                }
            }
        }
    }

    /** Resolve the server input with the token, validate, persist. Mirrors [finishJmapConnect]. */
    private suspend fun finishTokenConnect(server: String, email: String, token: String, accountName: String) {
        try {
            val resolved = container.mailRepository.resolveJmapServerInput(server, BearerAuth(token))
            // The token alone identifies the account server-side — the typed address is never
            // validated by Bearer auth, so a mistyped email would silently become the account's
            // identity (From default, notifications, display). Adopt the session's own address
            // (RFC 8620 username / mail account name) when it declares one; the typed value
            // only stands when the server exposes no usable address.
            val address = runCatching {
                container.mailRepository.sessionIdentity(resolved, BearerAuth(token))
            }.getOrNull() ?: email
            // Re-adding the same token (whatever email was typed) resolves to the same
            // server + adopted address: refresh that account in place, never a duplicate.
            val existing = container.accountStore.accounts().firstOrNull {
                it.protocol == MailProtocol.JMAP && it.authType == AuthType.API_TOKEN &&
                    it.server.equals(resolved, ignoreCase = true) && it.username.equals(address, ignoreCase = true)
            }
            // Blank id on purpose, even when re-adding: the real id comes back from the persist
            // step below and [addAccountThenPrime] stamps it onto the credentials it primes with,
            // so a brand-new token account can no longer cache its inbox under no account (#121).
            val probe = AccountCredentials(resolved, address, token, authType = AuthType.API_TOKEN)
            val added = addAccountThenPrime(
                probe = probe,
                validate = { container.mailRepository.testConnection(it).getOrThrow() },
                persist = {
                    if (existing != null) {
                        container.accountStore.updatePassword(existing.id, token)
                        container.accountStore.setCurrent(existing.id)
                        AddedAccount(existing.id, created = false)
                    } else {
                        AddedAccount(
                            container.accountStore.add(resolved, address, token, accountName.trim(), authType = AuthType.API_TOKEN),
                            created = true,
                        )
                    }
                },
                prime = { primeInbox(it) },
            )
            warnIfNotPrimed(added)
            // Surface linked sub-accounts before navigating, like the password path (#31):
            // a token login's sub-accounts resolve Bearer auth via the login.
            container.mailRepository.reconcileLinkedAccountsAfterAdd(added.id)
            _state.value = ConnectState.Connected
        } catch (cancelled: CancellationException) {
            // Leaving the screen cancels this coroutine: not something to show an error for.
            throw cancelled
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
        _state.value = ConnectState.Discovering
        oauthJob = viewModelScope.launch {
            // If the user entered a server (advanced), discover OAuth there; otherwise ask the
            // domain (DNS SRV) and fall back to the conventional hostnames. 🔴 The candidate list is
            // built in here rather than above because it now does DNS, and the main thread is not
            // the place for that.
            val candidates = if (server.isNotBlank()) {
                listOf(server.trim())
            } else {
                container.mailRepository.autodiscoverHosts(emailTrim)
            }
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
     * "Add account" escape from the pending-imports list: leave the listing for the normal
     * add-account form. With every imported account still deferred there is no signed-in account,
     * so without this exit the listing is a dead end (fresh install → import → skip → stranded;
     * only uninstalling recovered). The pending accounts are untouched — the form offers the way
     * back ([resumeImportSignIn]) while any remain.
     */
    fun leaveImportListing() {
        if (currentListing()?.selected == null) _importSignIn.value = ImportSignIn.None
    }

    /**
     * Re-enter the sign-in list for any still-unauthenticated imported account — e.g. the app was
     * killed mid-sign-in and relaunched, or the user left it via [leaveImportListing] and wants
     * back. Does nothing when there are none (so a genuine first run just shows the add-account
     * form). Never overrides a list already on screen.
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

    /**
     * Load an existing account's inbox into the cache and record its meta. The single entry
     * point every add path primes through — and the only place this screen calls `refresh()`,
     * so the credentials it writes under always carry the id [addAccountThenPrime] stamped.
     */
    /**
     * An add whose first inbox load failed still added the account, and the screen goes on to
     * [ConnectState.Connected] like any other add: the account is there, its cache fills on the
     * first refresh — the same state a process death mid-add leaves, which the app already repairs
     * without saying anything. Nothing to show the user, but the cause is worth a log line.
     */
    private fun warnIfNotPrimed(added: PrimedAccount) {
        added.primeFailure?.let {
            android.util.Log.w(
                "GridlinkConnect",
                "account ${added.id} was added; loading its inbox failed and will be retried on the next refresh",
                it,
            )
        }
    }

    private suspend fun primeInbox(credentials: AccountCredentials) {
        val meta = container.mailRepository.refresh(credentials)
        container.accountStore.saveInboxMetaFor(
            credentials.id, meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount,
        )
    }

    /** Validate against [server], persist on success. Runs in the caller's coroutine. */
    private suspend fun finishJmapConnect(
        server: String,
        username: String,
        password: String,
        accountName: String,
        log: SignInLog = SignInLog(),
    ) {
        try {
            // Blank id on purpose: this copy only ever proves the credentials. The cache is
            // primed from the id-stamped copy [addAccountThenPrime] builds (#121).
            val probe = AccountCredentials(server, username.trim(), password)
            val added = addAccountThenPrime(
                probe = probe,
                // Writes nothing — only persist once we know they work.
                validate = { container.mailRepository.testConnection(it).getOrThrow() },
                // A blank name falls back to the address.
                persist = {
                    AddedAccount(
                        container.accountStore.add(server, username, password, accountName.trim()),
                        created = true,
                    )
                },
                prime = { primeInbox(it) },
            )
            warnIfNotPrimed(added)
            log.add("Signing in to $server", "signed in")
            // Surface linked sub-accounts before navigating, so the accounts list the flow
            // lands on is already complete (#31).
            container.mailRepository.reconcileLinkedAccountsAfterAdd(added.id)
            _state.value = ConnectState.Connected
        } catch (cancelled: CancellationException) {
            // Leaving the screen cancels this coroutine: not something to show an error for.
            throw cancelled
        } catch (t: Throwable) {
            val steps = log.also { it.add("Signing in to $server", t) }.steps()
            // A typo'd server is the most common way this screen fails, and DNS reports it as
            // "Unable to resolve host …: No address associated with hostname" — accurate, and
            // written for whoever wrote the socket library. Named here rather than left raw,
            // because it is the one failure whose fix is a character in a field on screen.
            if (t is UnknownHostException) {
                _state.value = ConnectState.Error(string(R.string.connect_host_unresolved, server), steps)
                return
            }
            // Fastmail's endpoint refuses password auth outright (API tokens only, #54):
            // a 401 from it gets the same steer as the inline hint, not just a bare error.
            val msg = t.message ?: t.javaClass.simpleName
            val code = (t as? JmapException)?.httpCode
            if (code == 401 && isFastmailTarget(username, server)) {
                _state.value = ConnectState.Error(msg + " " + string(R.string.connect_fastmail_token_hint), steps)
                return
            }
            // Any other 401/403 means the server was reached, spoke JMAP, and turned the
            // credentials down. Left raw that reads "Session request failed: HTTP 401", which
            // names the transport and hides the one thing the user can act on. It is deliberately
            // vague about WHICH of the two fields is wrong, because the server does not say, and
            // guessing "wrong password" sends someone re-typing a password when the account name
            // is the part their server wants in a different form.
            if (code == 401 || code == 403) {
                _state.value = ConnectState.Error(string(R.string.connect_credentials_rejected), steps)
                return
            }
            // A bounded failure now has a name worth using: "took too long" and "nothing was
            // listening" are different problems with different fixes, and both used to arrive as
            // whatever the socket library called them.
            val named = when (classifySignInFailure(t)) {
                SignInFailure.TIMEOUT -> string(R.string.connect_timed_out)
                SignInFailure.REFUSED -> string(R.string.connect_refused)
                SignInFailure.TLS -> string(R.string.connect_tls_failed)
                else -> msg
            }
            _state.value = ConnectState.Error(named, steps)
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
        val log = SignInLog()
        if (!hasUsableNetwork(getApplication())) {
            log.add("Checking this device's network", "no connection")
            _state.value = ConnectState.Error(string(R.string.connect_offline), log.steps())
            return
        }
        _state.value = ConnectState.Connecting
        viewModelScope.launch {
            try {
                // Blank id on purpose: proving credentials only. See [addAccountThenPrime] (#121).
                val probe = AccountCredentials(
                    server = "",
                    username = username.trim(),
                    password = password,
                    protocol = MailProtocol.IMAP,
                    imap = MailEndpoint(imapHost.trim(), imapPort, imapSecurity),
                    smtp = MailEndpoint(smtpHost.trim(), smtpPort, smtpSecurity),
                )
                val added = addAccountThenPrime(
                    probe = probe,
                    // Connects + authenticates + lists folders, without caching any of it.
                    validate = { container.mailRepository.testConnection(it).getOrThrow() },
                    persist = {
                        AddedAccount(
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
                            ),
                            created = true,
                        )
                    },
                    prime = { primeInbox(it) },
                )
                warnIfNotPrimed(added)
                _state.value = ConnectState.Connected
            } catch (cancelled: CancellationException) {
                // Leaving the screen cancels this coroutine: not something to show an error for.
                throw cancelled
            } catch (t: Throwable) {
                // A rejected IMAP login surfaces as "LOGIN … failed" / "AUTHENTICATE …"
                // (the command verb is redacted, so no password leaks). On those, point
                // the user at app passwords — most big providers refuse the normal one.
                val msg = t.message.orEmpty()
                val authRejected = msg.contains("LOGIN", ignoreCase = true) ||
                    msg.contains("AUTHENTICATE", ignoreCase = true)
                val steps = log.also { it.add("Connecting to $imapHost:$imapPort", t) }.steps()
                _state.value = ConnectState.Error(
                    if (authRejected) {
                        string(R.string.connect_bad_credentials) + " " +
                            string(R.string.connect_provider_app_password_note)
                    } else {
                        // 🔴 The IMAP connect is time-bounded now (ImapMailService.SIGN_IN_BUDGET_MS),
                        // so a wrong port arrives here as a socket timeout in seconds instead of
                        // spinning for minutes. It is worth saying which of the two it was: the fix
                        // for "nothing was listening" is the port field, and the fix for "took too
                        // long" is usually the host or the network.
                        when (classifySignInFailure(t)) {
                            SignInFailure.TIMEOUT -> string(R.string.connect_timed_out)
                            SignInFailure.REFUSED -> string(R.string.connect_refused)
                            SignInFailure.TLS -> string(R.string.connect_tls_failed)
                            SignInFailure.DNS -> string(R.string.connect_host_unresolved, imapHost)
                            else -> t.message ?: t.javaClass.simpleName
                        }
                    },
                    steps,
                )
            }
        }
    }
}
