package app.gridlink.ui.connect

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gridlink.container
import app.gridlink.core.data.account.SyncSelection
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.gridlink.GridlinkSetupRequest
import app.gridlink.ui.gridlink.GridlinkSetupScreen

/**
 * Encodes the pending [SyncSelection] for savedInstanceState. Empty list = nothing pending, which is
 * distinct from a selection with everything off.
 */
private val SyncSelectionStateSaver: Saver<SyncSelection?, Any> = listSaver<SyncSelection?, Boolean>(
    save = { s -> if (s == null) emptyList() else listOf(s.mail, s.calendar, s.contacts) },
    restore = { saved -> saved.takeIf { it.size == 3 }?.let { SyncSelection(it[0], it[1], it[2]) } },
)

/**
 * The first launch, wired: Gridlink's setup screen driven by upstream's [ConnectViewModel].
 *
 * ## Why this bridge exists instead of the screen owning a view model
 * Everything under `ui.gridlink` is pure composition over data handed in — no view models, no
 * container, no Android graph. That is what lets the debug gallery render every screen in the app
 * without an account, and it is worth more than the indirection costs. So the screen states what it
 * needs ([GridlinkSetupRequest], a busy flag, an error string) and this file is the only place that
 * knows those come from a view model.
 *
 * ## 🔴 Why the sync choice is written AFTER the connect, and not passed into it
 * [ConnectViewModel.connect] validates the credentials before it persists anything, on purpose: a
 * mistyped password must leave no account behind (see `addAccountThenPrime`). There is therefore no
 * account id to hang a preference on until the connect has succeeded, and inventing a parameter to
 * carry one through that flow would put a Gridlink-only concept into the middle of the one function
 * every add path shares. Writing it on success keeps the rule intact: no account, no preference.
 *
 * The id comes from `currentId()` because an add makes the new account current as its last step. ⚠️
 * If that ever stops being true, this writes the selection onto the wrong account rather than
 * failing loudly, which is why the add path's `setCurrent` is worth leaving alone.
 *
 * ## What happens when autodiscovery comes up empty
 * [ConnectState.NeedsServer] is not an error and is not rendered as one. It means the probe found
 * nothing for that domain, which for a self-hosted server is the ordinary case, so it is turned into
 * the one instruction that resolves it: type the server in the field that is already on screen.
 */
@Composable
fun GridlinkSetupHost(
    onConnected: () -> Unit,
    /** Hands off to upstream's connect screen, which is where IMAP, OAuth and Outlook live. */
    onAdvanced: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val application = LocalContext.current.applicationContext as Application
    val accountStore = remember(application) { application.container.accountStore }

    // Survives the fold, unlike the password field above it: which data types were asked for is not
    // a secret, and losing it mid-connect would silently downgrade the user's answer to the default
    // — silently being the problem, since the account would then be created with a selection nobody
    // chose. Three booleans through a [listSaver] rather than the object: a Bundle takes primitives,
    // and @Serializable is kotlinx's, which savedInstanceState knows nothing about.
    var pending by rememberSaveable(stateSaver = SyncSelectionStateSaver) {
        mutableStateOf<SyncSelection?>(null)
    }

    LaunchedEffect(state) {
        if (state !is ConnectState.Connected) return@LaunchedEffect
        // ⚠️ Consumed rather than merely read, so a second pass through Connected (a recomposition,
        // a process-death restore landing on the same state) cannot rewrite a selection the user
        // has since changed in settings.
        val selection = pending
        pending = null
        val id = accountStore.currentId()
        if (selection != null && id != null) accountStore.setSyncSelection(id, selection)
        onConnected()
    }

    val busy = state is ConnectState.Connecting || state is ConnectState.Discovering

    val error = when (val s = state) {
        is ConnectState.Error -> s.message
        // Not phrased as a failure: nothing is wrong, we just have to be told where the server is.
        is ConnectState.NeedsServer ->
            "No JMAP server was found for that address. Enter the server above and try again."
        else -> null
    }

    // The record of what was tried, for the user to copy out. Carried on both outcomes above,
    // because "we could not find your server" is exactly the message somebody wants the evidence
    // for: it is the one that sounds like the app did nothing.
    val details = when (val s = state) {
        is ConnectState.Error -> s.details
        is ConnectState.NeedsServer -> s.details
        else -> emptyList()
    }

    // Its own token host, because this screen renders before anything else in the app has provided
    // one: [GridlinkApp] is normally entered on the far side of an account existing.
    GridlinkApp {
        GridlinkSetupScreen(
            onSubmit = { request ->
                pending = request.sync
                if (request.server.isBlank()) {
                    // Blank server = the well-known probe against the address's own domain.
                    viewModel.connectAuto(
                        email = request.email,
                        password = request.password,
                        accountName = "",
                        login = request.login,
                    )
                } else {
                    viewModel.connect(
                        server = request.server,
                        username = request.email,
                        password = request.password,
                        accountName = "",
                        login = request.login,
                    )
                }
            },
            onAdvanced = onAdvanced,
            busy = busy,
            error = error,
            details = details,
            modifier = modifier,
        )
    }
}
