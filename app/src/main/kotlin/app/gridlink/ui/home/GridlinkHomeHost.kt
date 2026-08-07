package app.gridlink.ui.home

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gridlink.container
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.gridlink.GridlinkChromeConfig
import app.gridlink.ui.gridlink.GridlinkMenuItem
import app.gridlink.ui.gridlink.GridlinkOutboxSender
import app.gridlink.ui.gridlink.GridlinkRoot
import app.gridlink.ui.gridlink.GridlinkSyncAction
import app.gridlink.ui.gridlink.GridlinkSyncState
import app.gridlink.ui.gridlink.LocalGridlinkChrome

/**
 * The signed-in app: Gridlink's four tabs, over the account's real mail.
 *
 * ## What this file is for
 * The same job [app.gridlink.ui.connect.GridlinkSetupHost] does for the setup screen, one screen
 * later. [GridlinkRoot] renders values and reports gestures; [GridlinkMailViewModel] holds the
 * mailbox and performs the writes; this is the only place that knows both exist.
 *
 * ## 🔴 What is deliberately NOT sample data here
 * Three of [GridlinkApp]'s defaults are sample values that a real account must not inherit, and all
 * three are overridden below: the menu's account address, the mailbox counts beside its rows, and
 * `initialLastSyncedAt`. The last one is the quiet one — left alone it seeds "Synced 4 minutes ago"
 * onto an app that has not yet spoken to a server this launch, which is the exact false reassurance
 * the sync state refuses to write anywhere else.
 *
 * ## ⚠️ What is not wired yet, and where it went
 * A tapped `mailto:` link and a tapped new-mail notification are consumed one level up, by
 * `AppNavHost`, and land nowhere: they used to drive upstream's NavHost, which no longer composes
 * for a signed-in user. Routing them here needs [GridlinkRoot] to accept a LIVE open request rather
 * than only the `initialOpenId` it takes at construction, since both can arrive while the app is
 * already on screen. That is its own change and it is stated here rather than half-done.
 */
@Composable
fun GridlinkHomeHost(
    accountId: String,
    accounts: List<StoredAccount>,
    /** Hands off to upstream's settings, which is where identities, PGP and filters still live. */
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GridlinkMailViewModel = viewModel(),
    davViewModel: GridlinkDavViewModel = viewModel(),
) {
    val application = LocalContext.current.applicationContext as Application
    // Before anything renders, so the first composition is already pointed at the right mailbox
    // rather than at a null one. Re-runs on an account switch; [GridlinkMailViewModel.bind] ignores
    // the repeats a configuration change causes.
    LaunchedEffect(accountId) {
        viewModel.bind(accountId)
        davViewModel.bind(accountId)
    }
    val mail by viewModel.mail.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val calendar by davViewModel.calendar.collectAsStateWithLifecycle()
    val contacts by davViewModel.contacts.collectAsStateWithLifecycle()

    // The address the menu sheet states. The username IS the address for every account this app can
    // create; `accountName` is the human label and is frequently empty, which would leave the one
    // line a user checks when mail stops arriving blank.
    val address = accounts.firstOrNull { it.id == accountId }?.username.orEmpty()

    // 🔴 Derived the same way [GridlinkDavViewModel.ownDomain] derives it, and the two MUST agree:
    // an event with no organiser is stamped with the view model's answer and the event card compares
    // it against this one. Empty is a working value, not a broken one; see that property.
    val ownDomain = address.substringAfter('@', "")

    val sender = remember(application) {
        GridlinkOutboxSender(
            context = application,
            repository = application.container.mailRepository,
            accounts = application.container.accountStore,
        )
    }

    // 🔴 Read through [rememberUpdatedState] rather than captured, because the config below is
    // remembered across recompositions: a directly captured lambda would go stale and the Settings
    // row would keep calling back into a composition that had moved on.
    val openSettings by rememberUpdatedState(onOpenSettings)
    val config = remember(address, accounts.size, viewModel, davViewModel) {
        GridlinkChromeConfig(
            account = address,
            accountCount = accounts.size,
            // Empty, not the sample's "2 waiting / 4 unsent". Nothing counts scheduled sends or
            // drafts for this UI yet, and an absent number says that where a zero would claim the
            // folders had been looked at and found empty.
            menuCounts = emptyMap(),
            onSelectMenu = { item ->
                when (item) {
                    // Accounts management lives inside upstream's settings screen, so both rows
                    // land there for now. Marked rather than merged: they are different questions
                    // and Gridlink will eventually answer the first one itself.
                    GridlinkMenuItem.SETTINGS, GridlinkMenuItem.ACCOUNTS -> openSettings()
                    // Scheduled and Drafts have upstream screens and no Gridlink route to them yet.
                    // The sheet closes and nothing happens, which is what an unwired row should do.
                    GridlinkMenuItem.SCHEDULED, GridlinkMenuItem.DRAFTS -> Unit
                }
            },
            // 🔴 The boolean is the MAIL result and only the mail result. The calendar and address
            // book sync alongside it and report into the log, because a user who never turned them
            // on, or who signs in with OAuth (no password, so no Basic auth for DAV), would
            // otherwise sit under a permanent amber chip telling them their mail was broken.
            sync = GridlinkSyncAction {
                val mailSynced = viewModel.sync()
                davViewModel.sync()
                mailSynced
            },
        )
    }

    GridlinkApp(
        // 🔴 Starts SYNCING, not SYNCED. The launch sync below is already running by the time the
        // chrome row draws, and opening on a green "Synced" chip that has not synced anything is
        // the same lie as a fake timestamp, told a second earlier.
        initialSync = GridlinkSyncState.SYNCING,
        config = config,
        // Never synced in this process. See the note on the class.
        initialLastSyncedAt = null,
    ) {
        val chrome = LocalGridlinkChrome.current
        // One sync per account, at launch. Keyed on the account so switching fetches the new
        // mailbox, and NOT on anything that changes with a rotation, which would re-sync on every
        // hinge movement. The chip and the timestamp are written by syncAllAccounts itself, so the
        // pull gesture and this share one code path and cannot disagree about what happened.
        LaunchedEffect(accountId) { chrome.syncAllAccounts() }
        GridlinkRoot(
            modifier = modifier,
            mail = mail,
            onMailAction = viewModel::act,
            onOpenMail = viewModel::open,
            onAllowImages = viewModel::setImagesAllowed,
            folders = folders,
            onFolderEdit = viewModel::editFolder,
            onOpenFolder = viewModel::openFolder,
            calendar = calendar,
            contacts = contacts,
            ownDomain = ownDomain,
            sender = sender,
        )
    }
}
