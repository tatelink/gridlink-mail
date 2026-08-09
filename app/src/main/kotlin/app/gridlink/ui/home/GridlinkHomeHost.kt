package app.gridlink.ui.home

import android.app.Application
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gridlink.EmailOpenTarget
import app.gridlink.MailtoDraft
import app.gridlink.container
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.gridlink.GridlinkChromeConfig
import app.gridlink.ui.gridlink.GridlinkComposeDraft
import app.gridlink.ui.gridlink.GridlinkMenuItem
import app.gridlink.ui.gridlink.GridlinkOpenRequest
import app.gridlink.ui.gridlink.GridlinkOutboxSender
import app.gridlink.ui.gridlink.GridlinkRoot
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.gridlink.GridlinkSyncAction
import app.gridlink.ui.gridlink.GridlinkSyncState
import app.gridlink.ui.gridlink.LocalGridlinkChrome
import app.gridlink.ui.gridlink.gridlinkTypedRecipient
import app.gridlink.ui.gridlink.toGridlinkPalette
import app.gridlink.ui.gridlink.toModeOverride
import app.gridlink.ui.theme.gridlinkColorsFor
import app.gridlink.ui.theme.gridlinkModeAt
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

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
 * ## Arriving from outside: `mailto:` links and notification taps
 * Both reach the activity as intents, are parsed there, and are handed down as payloads. This file
 * turns them into a [GridlinkOpenRequest], which is the one live channel [GridlinkRoot] takes for
 * "something outside asked for a screen". Two translations happen here and nowhere else:
 *
 * - **A `mailto:` becomes a draft.** `to` and `cc` both land in the single recipient list, because
 *   that is all the composer and the send path have: [GridlinkComposeDraft] carries one list and
 *   `enqueueSend` takes one list of addresses. A Cc arriving as a To is a loss of nuance the user
 *   can see and fix. 🔴 `bcc` is DROPPED, never folded in, and that asymmetry is the point: folding
 *   it would put an address the link explicitly asked to hide in front of every other recipient,
 *   which is a disclosure this app would be causing rather than a feature it is missing. The drop
 *   is logged, and a missing recipient is visible in the composer before anything is sent.
 * - **A notification names its mailbox**, and a message from a watched folder that is not the inbox
 *   needs that folder opened before it can be shown at all. See [GridlinkOpenRequest.Message].
 *
 * ⚠️ A share (`ACTION_SEND`) carrying files arrives as a subject and a body with the URIs stashed in
 * the container. The Gridlink composer has no attachment picker and its sender refuses a draft with
 * attachments outright, so the files are not attached; the text is. The activity drops the stash
 * when the payload is consumed, so nothing inherits it later.
 */
@Composable
fun GridlinkHomeHost(
    accountId: String,
    accounts: List<StoredAccount>,
    /** Hands off to upstream's settings, which is where identities, PGP and filters still live. */
    onOpenSettings: () -> Unit,
    /** A `mailto:` link or a share, parsed by the activity. Null when nothing is waiting. */
    pendingMailto: MailtoDraft? = null,
    onMailtoConsumed: () -> Unit = {},
    /**
     * The message a tapped notification wants open. 🔴 Already resolved to THIS account by the
     * caller: it withholds the payload until the switch it may need has happened, so anything
     * arriving here belongs to [accountId].
     */
    pendingEmailOpen: EmailOpenTarget? = null,
    onEmailOpenConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: GridlinkMailViewModel = viewModel(),
    davViewModel: GridlinkDavViewModel = viewModel(),
) {
    val application = LocalContext.current.applicationContext as Application
    val settings = application.container.settingsRepository
    // Before anything renders, so the first composition is already pointed at the right mailbox
    // rather than at a null one. Re-runs on an account switch; [GridlinkMailViewModel.bind] ignores
    // the repeats a configuration change causes.
    LaunchedEffect(accountId) {
        viewModel.bind(accountId)
        davViewModel.bind(accountId)
    }
    val mail by viewModel.mail.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val scheduled by viewModel.scheduled.collectAsStateWithLifecycle()
    val menuCounts by viewModel.menuCounts.collectAsStateWithLifecycle()
    val calendar by davViewModel.calendar.collectAsStateWithLifecycle()
    val contacts by davViewModel.contacts.collectAsStateWithLifecycle()

    // 🔴 Initial value is null meaning "not read yet", NOT AUTO meaning "follows the sun". The two
    // are the same picture and a completely different fact, and the difference is the whole reason
    // this gate exists: see the wait below, and [GridlinkApp]'s `initialModeOverride`.
    val storedPalette by settings.gridlinkPalette.collectAsStateWithLifecycle(initialValue = null)

    val account = accounts.firstOrNull { it.id == accountId }

    // The address the menu sheet states. The username IS the address for every account this app can
    // create; `accountName` is the human label and is frequently empty, which would leave the one
    // line a user checks when mail stops arriving blank.
    val address = account?.username.orEmpty()

    // The one-shot handed to [GridlinkRoot]. Remembered on the payloads themselves, so an unrelated
    // recomposition cannot mint a second request out of the same tap, and so the identity the
    // scaffold keys its effect on is stable until one of them actually changes.
    //
    // A notification outranks a `mailto:` when both are somehow waiting. Neither is lost: the
    // scaffold acknowledges one, that payload clears, and this recomputes to the other.
    val openRequest = remember(pendingEmailOpen, pendingMailto, account?.inboxId) {
        when {
            pendingEmailOpen != null -> GridlinkOpenRequest.Message(
                id = pendingEmailOpen.emailId,
                // Named only when it is NOT the inbox. The inbox is the list the scaffold is already
                // holding, so passing it would spend a folder fetch to be told what is on screen,
                // and would leave the Folders tab parked on a mailbox nobody opened. A notification
                // for a watched folder DOES need it: see [GridlinkOpenRequest.Message].
                folderId = pendingEmailOpen.mailboxId?.takeIf { it != account?.inboxId },
            )
            pendingMailto != null -> {
                // Logged HERE and not inside the conversion, which stays a pure function so the
                // to/cc/bcc rule can be tested without a framework. Not the addresses themselves,
                // only that there were some: a log line is no place for a correspondent, and the
                // user is about to see the draft that is missing them.
                if (pendingMailto.bcc.isNotBlank()) {
                    Log.i(TAG, "mailto: carried bcc; dropped, the composer has no blind row")
                }
                GridlinkOpenRequest.Compose(pendingMailto.toGridlinkDraft())
            }
            else -> null
        }
    }

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
    // 🔴 menuCounts joins the remember keys: the config is rebuilt when a count changes or the
    // drawer keeps saying yesterday's number over today's Drafts.
    val config = remember(address, accounts.size, menuCounts, viewModel, davViewModel) {
        GridlinkChromeConfig(
            account = address,
            accountCount = accounts.size,
            // Live numbers, from the folder table (Drafts) and the scheduled-send table. A zero is
            // omitted by the menu row itself (`takeIf { it > 0 }`), so an empty account still shows
            // an unnumbered row rather than a claimed-empty one.
            menuCounts = menuCounts,
            onSelectMenu = { item ->
                when (item) {
                    // Accounts management lives inside upstream's settings screen, so both rows
                    // land there for now. Marked rather than merged: they are different questions
                    // and Gridlink will eventually answer the first one itself.
                    GridlinkMenuItem.SETTINGS, GridlinkMenuItem.ACCOUNTS -> openSettings()
                    // Routed by the scaffold via [GridlinkChromeState.routeMenu], not from here:
                    // this config lives ABOVE the scaffold and cannot reach its navigation state.
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
            // 🔴 On [appScope], not a composition scope. The palette pill sits in the menu sheet,
            // and the sheet closing is a plausible next frame; a write cancelled with the
            // composition would leave the pill lit and the preference unwritten, which is invisible
            // until the next cold launch. DataStore serialises its own writes, so a burst of taps
            // is safe.
            onSelectMode = { mode ->
                application.container.appScope.launch {
                    settings.setGridlinkPalette(mode.toGridlinkPalette())
                }
            },
        )
    }

    // ⚠️ Held back until DataStore answers, which is why this is not just an argument below.
    // [GridlinkApp]'s holder reads its seed exactly once, so composing it against a null we have not
    // finished reading pins the palette to the wrong value for the life of the process. The wait is
    // a disk read of a file the activity is already reading, so in practice one frame.
    //
    // Painted rather than left empty for that frame: an unpainted Box shows the window background,
    // which is upstream's light theme and would strobe white before an OLED launch. The colour is
    // the sun's answer because that is what an unpinned launch resolves to anyway, and a pinned one
    // is a fraction of a frame away from repainting.
    val palette = storedPalette
    if (palette == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gridlinkColorsFor(gridlinkModeAt(ZonedDateTime.now())).background),
        )
        return
    }

    GridlinkApp(
        initialModeOverride = palette.toModeOverride(),
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
            onSearchQuery = viewModel::search,
            onAllowImages = viewModel::setImagesAllowed,
            onOpenAttachment = viewModel::openAttachment,
            folders = folders,
            onFolderEdit = viewModel::editFolder,
            onOpenFolder = viewModel::openFolder,
            calendar = calendar,
            contacts = contacts,
            ownDomain = ownDomain,
            sender = sender,
            calendarWriter = davViewModel.calendarWriter,
            contactWriter = davViewModel.contactWriter,
            scheduled = scheduled,
            onCancelScheduled = viewModel::cancelScheduled,
            onEditDraft = viewModel::editDraft,
            openRequest = openRequest,
            // Routed back to whichever payload built the request, so consuming one cannot cancel a
            // genuinely pending other. Reading `openRequest` rather than a captured flag keeps the
            // two halves impossible to get out of step.
            onOpenRequestConsumed = {
                when (openRequest) {
                    is GridlinkOpenRequest.Message -> onEmailOpenConsumed()
                    is GridlinkOpenRequest.Compose -> onMailtoConsumed()
                    null -> Unit
                }
            },
        )
    }
}

/**
 * A parsed `mailto:` (or a share, which the activity parses into the same shape) as a draft the
 * composer can open. See this file's KDoc for why `cc` merges into the single recipient list and
 * why `bcc` does not.
 */
internal fun MailtoDraft.toGridlinkDraft(): GridlinkComposeDraft {
    val recipients = (to.split(',') + cc.split(','))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .map { candidate ->
            // The composer's own typed-address builder, so a linked recipient is indistinguishable
            // from one typed just now. The fallback is for addresses its validator would refuse
            // (odd but real ones exist): dropping them here would silently shrink the draft, and a
            // recipient the user cannot see is the one thing this conversion must never produce.
            gridlinkTypedRecipient(candidate) ?: GridlinkContact(
                id = "typed:${candidate.lowercase()}",
                given = "",
                family = candidate,
                role = "",
                email = candidate,
            )
        }
    return GridlinkComposeDraft(
        title = "Compose",
        recipients = recipients,
        // 🔴 Empty, NOT [GridlinkComposeDraft.Fresh]'s seeded query. That seed is a demo prop, and
        // inheriting it would open a link-driven draft with a half-typed search under the chips.
        recipientQuery = "",
        subject = subject,
        body = body,
        quoted = null,
        // A share's files stay unattached: the Gridlink composer has no picker and its sender
        // refuses a draft carrying attachments. Stated in this file's KDoc rather than dropped
        // quietly here.
        attachments = emptyList(),
    )
}

private const val TAG = "GridlinkHomeHost"
