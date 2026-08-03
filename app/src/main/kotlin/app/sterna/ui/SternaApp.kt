package app.sterna.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.EmailOpenTarget
import app.sterna.MailtoDraft
import app.sterna.container
import app.sterna.core.data.account.StoredAccount
import app.sterna.push.PushController
import app.sterna.security.LockScreen
import app.sterna.ui.compose.ComposeScreen
import app.sterna.ui.connect.ConnectScreen
import app.sterna.core.jmap.model.Email
import app.sterna.ui.inbox.InboxScreen
import app.sterna.ui.inbox.InboxViewModel
import app.sterna.ui.inbox.ThreadKey
import app.sterna.ui.message.LocalNavTransitionActive
import app.sterna.ui.message.MessageScreen
import app.sterna.ui.message.NavFadeGuard
import app.sterna.ui.outbox.OutboxScreen
import app.sterna.ui.scheduled.ScheduledSendsScreen
import app.sterna.ui.sender.MailBySenderScreen
import app.sterna.ui.snoozed.SnoozedScreen
import app.sterna.ui.search.SearchScreen
import app.sterna.ui.onboarding.WelcomeScreen
import app.sterna.ui.settings.SettingsScreen
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * TEST BUILD ONLY. Set back to false before integrating. When true, the privacy welcome
 * and the contacts priming appear on every launch/compose regardless of their real gating,
 * so they can be seen on a device that already has an account (without uninstalling). The
 * real gating logic below is untouched; this flag only bypasses it for the preview build.
 */
const val FORCE_ONBOARDING_PREVIEW = false

/** Top-level route: no account, or signed in to a specific account. */
sealed interface RootState {
    data object Loading : RootState
    data object NeedAccount : RootState
    data class Authenticated(val accountId: String) : RootState
}

class RootViewModel(application: Application) : AndroidViewModel(application) {
    private val accountStore = application.container.accountStore
    private val settings = application.container.settingsRepository

    private val _state = MutableStateFlow<RootState>(RootState.Loading)
    val state = _state.asStateFlow()

    // null while the flag is still loading from DataStore, so first launch shows the welcome
    // (not a flash of the connect screen) and a returning user never flashes the welcome.
    val hasSeenWelcome: StateFlow<Boolean?> =
        settings.hasSeenWelcome.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markWelcomeSeen() {
        viewModelScope.launch { settings.setHasSeenWelcome(true) }
    }

    init {
        refresh()
    }

    /**
     * The accounts to list, live from the store rather than read once per composition: a mailbox
     * shared with the login shows up as soon as discovery persists it, and one whose share was
     * revoked leaves the selector on the same pass — no account switch, no restart (issue #31).
     */
    val accounts: StateFlow<List<StoredAccount>> = accountStore.accountsFlow

    fun refresh() {
        // Only an account with a stored credential counts as authenticated. Freshly imported,
        // password-less accounts don't: the user must still sign into them, so we route to the
        // connect flow (which resumes the per-account password prompts) instead of a broken inbox.
        val current = accountStore.currentId()?.takeIf { accountStore.credentials(it) != null }
            ?: accountStore.accounts().firstOrNull { accountStore.credentials(it.id) != null }?.id
        if (current != null) {
            if (accountStore.currentId() != current) accountStore.setCurrent(current)
            PushController.apply(getApplication(), userInitiated = true)
            _state.value = RootState.Authenticated(current)
        } else {
            PushController.apply(getApplication(), userInitiated = true)
            _state.value = RootState.NeedAccount
        }
    }

    fun switchAccount(id: String) {
        accountStore.setCurrent(id)
        // Switching account always lands the list on a single folder, so the unified view is over
        // before the new account is armed. Say so HERE rather than letting the list say it: the
        // arm below runs synchronously inside refresh(), while the list only reselects on the
        // recomposition that follows, so the mirror would still read "unified" at the one moment
        // it is consulted — and every switch out of the unified inbox would silently reseed every
        // watched account instead of only the one being switched to.
        PushController.unifiedInboxVisible = false
        refresh()
    }
}

@Composable
fun SternaApp(
    pendingMailto: MailtoDraft? = null,
    onMailtoConsumed: () -> Unit = {},
    pendingEmailOpen: EmailOpenTarget? = null,
    onEmailOpenConsumed: () -> Unit = {},
    viewModel: RootViewModel = viewModel(),
) {
    RequestNotificationPermission()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val hasSeenWelcome by viewModel.hasSeenWelcome.collectAsStateWithLifecycle()
    val appLock = (LocalContext.current.applicationContext as Application).container.appLock
    val locked by appLock.locked.collectAsStateWithLifecycle()

    // Preview-only gate: force the welcome at startup regardless of RootState/hasSeenWelcome,
    // then fall through to the NORMAL routing (an authenticated user proceeds to their inbox,
    // they are NOT dropped into the connect screen). Flip FORCE_ONBOARDING_PREVIEW to false to
    // fully restore the real first-launch gating below.
    var previewWelcomeDone by rememberSaveable { mutableStateOf(false) }
    if (FORCE_ONBOARDING_PREVIEW && !previewWelcomeDone) {
        WelcomeScreen(onDone = { previewWelcomeDone = true })
        return
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            RootState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            // First genuine launch (no account yet): show the privacy welcome once, then the
            // connect flow. While the flag is still loading (null) show a brief spinner so we
            // neither flash the connect screen on first launch nor the welcome for a returning
            // (signed-out) user who has already seen it.
            RootState.NeedAccount -> when (hasSeenWelcome) {
                null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                false -> WelcomeScreen(onDone = viewModel::markWelcomeSeen)
                true -> ConnectScreen(onConnected = viewModel::refresh, firstRun = true)
            }
            // No key(accountId) here: switching account updates currentAccountId in place so
            // the inbox re-points (InboxScreen reacts via onAccountChanged) WITHOUT recreating
            // the screen — which lets the drawer's account carousel stay open across a switch.
            is RootState.Authenticated -> MainNavHost(
                accounts = accounts,
                currentAccountId = s.accountId,
                onSwitchAccount = viewModel::switchAccount,
                onAccountsChanged = viewModel::refresh,
                pendingMailto = pendingMailto,
                onMailtoConsumed = onMailtoConsumed,
                pendingEmailOpen = pendingEmailOpen,
                onEmailOpenConsumed = onEmailOpenConsumed,
            )
        }
        if (locked) LockScreen(onUnlocked = appLock::unlock)
        DistributorPickerDialog()
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun MainNavHost(
    accounts: List<StoredAccount>,
    currentAccountId: String,
    onSwitchAccount: (String) -> Unit,
    onAccountsChanged: () -> Unit,
    pendingMailto: MailtoDraft? = null,
    onMailtoConsumed: () -> Unit = {},
    pendingEmailOpen: EmailOpenTarget? = null,
    onEmailOpenConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    // A tapped mailto: link opens the compose screen prefilled with the parsed fields
    // (Codeberg #15). Consumed immediately so recompositions can't re-navigate.
    LaunchedEffect(pendingMailto) {
        val m = pendingMailto ?: return@LaunchedEffect
        onMailtoConsumed()
        // unguarded: a single consumption, not a repeatable tap — dropping it would lose the
        // user's action rather than de-duplicate it. See navigateOnce's KDoc.
        nav.navigate(
            "compose?to=${Uri.encode(m.to)}&cc=${Uri.encode(m.cc)}&bcc=${Uri.encode(m.bcc)}" +
                "&subject=${Uri.encode(m.subject)}&body=${Uri.encode(m.body)}",
        )
    }
    // The inbox's own ViewModel — the very instance the inbox screen and the reader share — so
    // the notification path can ask what the list behind is currently showing. Deliberately NOT
    // remembered and NOT collected as state: it is looked up afresh on each recomposition (a
    // notification arriving recomposes this, since it flows in as [pendingEmailOpen]) and read
    // imperatively inside the effect, so it neither subscribes this host to every list update
    // nor caches a stale answer. Null only until the NavHost has composed its start destination,
    // i.e. the first composition of a cold start — where the list is a plain folder anyway (the
    // unified selection is not restored across a process death).
    val listHostEntry = runCatching { nav.getBackStackEntry("inbox") }.getOrNull()
    val listViewModel: InboxViewModel? = listHostEntry?.let { viewModel(it) }
    // The folder half of the same rule (issue #91), handed to the list below rather than applied
    // here: the verdict needs the account's folder list, which the list itself owns and which is
    // not loaded at this instant when the account has just been switched. Held as its own
    // one-shot because on a COLD start opened from a notification [listViewModel] is still null
    // in the composition that consumes the tap (the NavHost has not composed its start
    // destination yet) — this waits for the composition where it is, instead of being dropped.
    var pendingFolderOpen by remember { mutableStateOf<EmailOpenTarget?>(null) }
    // A tapped new-mail notification opens that specific message, standalone (no list paging),
    // even when the app was already running (Codeberg #17 follow-up). Same consume-once pattern.
    // Opening it also makes the message's own account current (issue #31 follow-up), so Back
    // returns to the mailbox that was just read instead of another account's — see
    // [NotificationAccountSwitch] for when that switch is deliberately skipped.
    LaunchedEffect(pendingEmailOpen) {
        val target = pendingEmailOpen ?: return@LaunchedEffect
        onEmailOpenConsumed()
        NotificationAccountSwitch.resolve(
            notificationAccountId = target.accountId,
            currentAccountId = currentAccountId,
            knownAccountIds = accounts.map { it.id },
            unifiedView = listViewModel?.state?.value?.unified == true,
        )?.let(onSwitchAccount)
        // Both ids or nothing: the folder is judged inside its own account (two accounts on the
        // same server routinely share mailbox ids), and a notification old enough to carry
        // neither predates both extras.
        pendingFolderOpen = target.takeIf { it.accountId != null && it.mailboxId != null }
        // unguarded: a single consumption, and it can arrive mid-transition — adding the guard
        // here could lose the opening of a notification the user just tapped.
        nav.navigate(
            "message/${Uri.encode(target.emailId)}?accountId=${Uri.encode(target.accountId.orEmpty())}",
        )
    }
    // Keyed on [listViewModel] as well, so the request is handed over in the first composition
    // where the list exists rather than lost on a cold start.
    LaunchedEffect(pendingFolderOpen, listViewModel) {
        val target = pendingFolderOpen ?: return@LaunchedEffect
        val list = listViewModel ?: return@LaunchedEffect
        pendingFolderOpen = null
        list.showFolderFromNotification(target.accountId.orEmpty(), target.mailboxId.orEmpty())
    }
    // Devices that SIGSEGV'd inside a message fade (the #10 GL-functor bug) have the fade
    // latched off by the crash sentinel — they navigate instantly instead of crashing.
    // Read once per process: the latch only changes via a process death.
    val navContext = LocalContext.current
    val messageFadeDisabled = remember { NavFadeGuard.fadeDisabled(navContext) }
    // Honour the system "Remove animations" accessibility setting on the one animated route too:
    // rememberMotionEnabled() is @Composable, so its value is captured here at the NavHost level
    // and read inside the transition lambdas below (which run in AnimatedContentTransitionScope,
    // not a composable context) — the same capture pattern used for [messageFadeDisabled].
    val motionEnabled = rememberMotionEnabled()
    // Instant transitions between menus/screens by default: navigation-compose's default
    // animated cross-fade gets stuck (showing the bare window background) on rapid back/forth
    // navigation. The message route opts back into the soft fade below — the one place an
    // animation is wanted and rapid open/close is unlikely.
    NavHost(
        navController = nav,
        startDestination = "inbox",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
    ) {
        composable("inbox") { entry ->
            InboxScreen(
                onOpenEmail = { id, accountId, index, fromSearch ->
                    // Carry the tapped entry's position and whether it came from the inline
                    // search results, so the reading view can page between the same entries.
                    entry.navigateOnce {
                        val src = if (fromSearch) "search" else "list"
                        nav.navigate("message/${Uri.encode(id)}?accountId=${Uri.encode(accountId.orEmpty())}&index=$index&src=$src")
                    }
                },
                // A message tapped inside an inline-expanded conversation reads in the context
                // of THAT conversation: the thread key + the tapped message's position travel
                // along, so the reader swipes between the conversation's messages and stops at
                // its ends instead of spilling into the list (Codeberg #13).
                onOpenThreadMessage = { id, accountId, threadKey, index ->
                    entry.navigateOnce {
                        // The key travels ACCOUNT-QUALIFIED (ThreadKey.encode): a route carrying a
                        // bare thread id let the reader page over the sibling account's
                        // homonymous conversation in the unified inbox (#92).
                        nav.navigate(
                            "message/${Uri.encode(id)}?accountId=${Uri.encode(accountId.orEmpty())}" +
                                "&index=$index&src=thread&thread=${Uri.encode(threadKey.encode())}",
                        )
                    }
                },
                onCompose = { entry.navigateOnce { nav.navigate("compose") } },
                // unguarded: reopening an undone send is a deliberate one-shot driven off the
                // restored-draft flow, not a stale list tap, so it must not be gated by the
                // resumed-entry guard (which was dropping it and leaving the message parked with
                // no compose to return to).
                onReopenDraft = { nav.navigate("compose?restore=true") },
                // A message tapped in the Drafts folder opens in compose for editing (#63),
                // not in the reader — sending or re-saving then replaces the stored draft.
                onEditDraft = { id, accountId ->
                    entry.navigateOnce {
                        nav.navigate("compose?draftId=${Uri.encode(id)}&accountId=${Uri.encode(accountId.orEmpty())}")
                    }
                },
                onOpenSettings = { entry.navigateOnce { nav.navigate("settings") } },
                // The words already typed in the search bar travel with the navigation: opening
                // the advanced filters used to clear them and ask for them again.
                onOpenSearch = { q -> entry.navigateOnce { nav.navigate("search?q=${Uri.encode(q)}") } },
                onOpenScheduled = { entry.navigateOnce { nav.navigate("scheduled") } },
                onOpenSnoozed = { entry.navigateOnce { nav.navigate("snoozed") } },
                onOpenOutbox = { entry.navigateOnce { nav.navigate("outbox") } },
                onOpenMailBySender = { entry.navigateOnce { nav.navigate("bysender") } },
                accounts = accounts,
                currentAccountId = currentAccountId,
                onSwitchAccount = onSwitchAccount,
                onOpenAccountSettings = { id -> entry.navigateOnce { nav.navigate("settings?accountId=$id") } },
            )
        }
        composable(
            route = "message/{emailId}?accountId={accountId}&index={index}&src={src}&thread={thread}",
            arguments = listOf(
                navArgument("emailId") { type = NavType.StringType },
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
                // Position of the tapped entry in the originating context, and which context it
                // was ("list" = paged browse, "search" = inline search, "thread" = an unfolded
                // conversation; absent = no context, a lone message).
                navArgument("index") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("src") { type = NavType.StringType; nullable = true; defaultValue = null },
                // Which conversation, when src=thread.
                navArgument("thread") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
            // Opening and closing a message keep a short soft cross-fade (Material's full-screen
            // ~200 ms), while the rest of the app navigates instantly — except on devices the
            // [NavFadeGuard] sentinel has latched after a fade-window crash (Codeberg #10), or
            // when the system reduced-motion setting is on: both fall back to instant, and
            // symmetrically on enter and pop-exit so there is no half-animated open/close (#100).
            enterTransition = {
                if (messageFadeDisabled || !motionEnabled) EnterTransition.None else fadeIn(tween(200))
            },
            popExitTransition = {
                if (messageFadeDisabled || !motionEnabled) ExitTransition.None else fadeOut(tween(200))
            },
        ) { entry ->
            val emailId = Uri.decode(entry.arguments?.getString("emailId").orEmpty())
            val accountId = entry.arguments?.getString("accountId")?.let { Uri.decode(it) }?.ifBlank { null }
            val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
            val src = entry.arguments?.getString("src")
            // Share the inbox's own ViewModel (same backstack entry) so the reading view pages
            // over the exact list the user was looking at — reusing its paging, not a copy.
            val inboxEntry = remember(entry) { nav.getBackStackEntry("inbox") }
            val inboxViewModel: InboxViewModel = viewModel(inboxEntry)
            val listSource = if (src == "list") inboxViewModel.pagedEmails else null
            val searchResults = if (src == "search") {
                remember(inboxViewModel) { inboxViewModel.state.value.searchResults }
            } else {
                null
            }
            // Opened from an unfolded conversation: page over that conversation's messages,
            // taken from the expansion the list already holds in memory (no new query).
            // Snapshotted once for the reading session, like the search results above, so a
            // background thread completion can't shift the pages under the reader's fingers.
            val threadKey = entry.arguments?.getString("thread")
                ?.let { Uri.decode(it) }?.ifBlank { null }
                ?.let { ThreadKey.decode(it) }
            val threadEntries = if (src == "thread" && threadKey != null) {
                remember(inboxViewModel, threadKey) { inboxViewModel.threadEntries(threadKey) }
            } else {
                null
            }
            // The fades above composite this whole destination through an offscreen graphics
            // layer while they run; the reader's body WebView must not draw its hardware GL
            // functor into that layer (null-SkSurface SIGSEGV on many HWUI builds — Codeberg
            // #10), so it is told when a transition is running and parks on a software layer
            // for the duration. True during the enter fade, the pop-exit fade, and the
            // pop-return fade from compose.
            val navTransitionActive = transition.currentState != EnterExitState.Visible ||
                transition.targetState != EnterExitState.Visible
            CompositionLocalProvider(LocalNavTransitionActive provides navTransitionActive) {
                MessageScreen(
                    anchorEmailId = emailId,
                    anchorAccountId = accountId,
                    initialIndex = index,
                    listSource = listSource,
                    searchResults = searchResults,
                    threadEntries = threadEntries,
                    // Guard both actions on the message entry being resumed: during its fade-out
                    // pop the screen is still composed and its Back arrow still tappable, so an
                    // unguarded onBack would popBackStack a second time and empty the stack
                    // (the white-screen freeze). A reply navigate is gated for the same reason.
                    onBack = { entry.navigateOnce { nav.popBackStack() } },
                    // Delete via the shared inbox VM so the reader reuses the same held-back
                    // destroy + Undo as swipe/bulk (the snackbar shows on the list). Same
                    // instance the inbox screen observes (both viewModel(inboxEntry)) — #23.
                    onDelete = { email ->
                        entry.navigateOnce {
                            inboxViewModel.delete(email)
                            nav.popBackStack()
                        }
                    },
                    // Archive via the same shared inbox VM, so the reader gets the count nudge +
                    // Undo (snackbar on the list) exactly like delete (RC-6).
                    onArchive = { email ->
                        entry.navigateOnce {
                            inboxViewModel.archive(email)
                            nav.popBackStack()
                        }
                    },
                    // Move to folder, through the same shared inbox VM as archive/delete (#73):
                    // the reader's move is the list's move, Undo snackbar included, and the
                    // screen returns to the list because the message just left the folder
                    // being read — exactly what archiving it does.
                    onMove = { email, targetMailboxId ->
                        entry.navigateOnce {
                            inboxViewModel.moveTo(email, targetMailboxId)
                            nav.popBackStack()
                        }
                    },
                    onReply = { mode, replyToId, replyAccountId ->
                        entry.navigateOnce {
                            val accountArg = replyAccountId?.let { "&accountId=${Uri.encode(it)}" }.orEmpty()
                            nav.navigate("compose?replyTo=${Uri.encode(replyToId)}&mode=$mode$accountArg")
                        }
                    },
                    onComposeTo = { address ->
                        entry.navigateOnce {
                            nav.navigate("compose?to=${Uri.encode(address)}")
                        }
                    },
                )
            }
        }
        composable(
            route = "compose?replyTo={replyTo}&mode={mode}&accountId={accountId}&restore={restore}" +
                "&to={to}&cc={cc}&bcc={bcc}&subject={subject}&body={body}&draftId={draftId}",
            arguments = listOf(
                navArgument("replyTo") { type = NavType.StringType; nullable = true; defaultValue = null },
                // A saved draft reopened for editing (#63).
                navArgument("draftId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("restore") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("to") { type = NavType.StringType; nullable = true; defaultValue = null },
                // mailto: prefills (Codeberg #15).
                navArgument("cc") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("bcc") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("subject") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("body") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            // The inbox's own ViewModel (same backstack entry as the list and the reader), so
            // deleting the draft being edited is the SAME gesture the Drafts list offers: a move to
            // the Trash, with the held-back destroy and the Undo snackbar — which then shows on the
            // list this pops back to. The reader's delete is wired exactly this way (#23, #127).
            val inboxEntry = remember(entry) { nav.getBackStackEntry("inbox") }
            val inboxViewModel: InboxViewModel = viewModel(inboxEntry)
            ComposeScreen(
                // After a send (new mail, reply or forward) return to the inbox rather than
                // the message that was open underneath compose.
                onDone = { entry.navigateOnce { nav.popBackStack("inbox", inclusive = false) } },
                onCancel = { entry.navigateOnce { nav.popBackStack() } },
                // A single pop, like onCancel and unlike onDone: the composer was opened from the
                // Drafts list, and that list is where the "Message deleted / Undo" snackbar has to
                // appear. Deliberately NOT through ComposeState.Done, which flies the tern away —
                // that animation belongs to a message that went out.
                // The draft is TAKEN inside the guard, not before it (#127): takeEditingDraft()
                // hands the row over exactly once, so consuming it outside meant a tap the guard
                // drops — two fingers, the X and the trash in one frame — had thrown the draft
                // away without deleting anything. Same shape as onDelete above: the mutation and
                // the pop live inside navigateOnce together.
                onDeleteDraft = { take ->
                    entry.navigateOnce {
                        val email = take() ?: return@navigateOnce
                        inboxViewModel.delete(email)
                        nav.popBackStack()
                    }
                },
                replyTo = entry.arguments?.getString("replyTo")?.let { Uri.decode(it) },
                mode = entry.arguments?.getString("mode"),
                accountId = entry.arguments?.getString("accountId")?.let { Uri.decode(it) }?.ifBlank { null },
                restore = entry.arguments?.getString("restore") == "true",
                to = entry.arguments?.getString("to")?.let { Uri.decode(it) }?.ifBlank { null },
                cc = entry.arguments?.getString("cc")?.let { Uri.decode(it) }?.ifBlank { null },
                bcc = entry.arguments?.getString("bcc")?.let { Uri.decode(it) }?.ifBlank { null },
                subject = entry.arguments?.getString("subject")?.let { Uri.decode(it) }?.ifBlank { null },
                body = entry.arguments?.getString("body")?.let { Uri.decode(it) }?.ifBlank { null },
                draftId = entry.arguments?.getString("draftId")?.let { Uri.decode(it) }?.ifBlank { null },
            )
        }
        composable(
            route = "search?q={q}&from={from}",
            // The initial term is read straight from the entry's SavedStateHandle by
            // SearchViewModel, so it survives a process death like the rest of the criteria.
            // `from` arrives the same way, from "see these messages" on the per-sender screen,
            // and does exactly as much as `q`: it fills its field, it does not run the search.
            arguments = listOf(
                navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            SearchScreen(
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                onOpenEmail = { id, accountId ->
                    entry.navigateOnce {
                        nav.navigate("message/${Uri.encode(id)}?accountId=${Uri.encode(accountId.orEmpty())}")
                    }
                },
            )
        }
        composable(
            route = "settings?accountId={accountId}",
            arguments = listOf(
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
            // Settings slides in from the right over the inbox, and back out to the right on Back.
            // A slide (opaque) rather than the default cross-fade avoids the bare-window-background
            // flash that made animations unusable elsewhere in this NavHost: the inbox underneath
            // does not move and stays composed until the transition ends, so the frame is fully
            // painted at every instant. Now within DESIGN.md's ≤ 250 ms, and off when the system
            // reduced-motion setting is on — the promise Motion.kt makes for EVERY motion (#100).
            enterTransition = {
                if (!motionEnabled) EnterTransition.None else slideInHorizontally(tween(SCREEN_SLIDE_MS)) { it }
            },
            popExitTransition = {
                if (!motionEnabled) ExitTransition.None else slideOutHorizontally(tween(SCREEN_SLIDE_MS)) { it }
            },
        ) { entry ->
            SettingsScreen(
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                onAccountsChanged = onAccountsChanged,
                initialAccountId = entry.arguments?.getString("accountId")?.ifBlank { null },
            )
        }
        composable("scheduled") { entry ->
            ScheduledSendsScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("snoozed") { entry ->
            SnoozedScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("outbox") { entry ->
            OutboxScreen(
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                onEditDraft = { entry.navigateOnce { nav.navigate("compose?restore=true") } },
            )
        }
        composable("bysender") { entry ->
            MailBySenderScreen(
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                // Look before destroying: the address travels as the search's `from` criterion,
                // the same way the inbox's bar hands its words over as `q`.
                onOpenSearch = { from ->
                    entry.navigateOnce { nav.navigate("search?from=${Uri.encode(from)}") }
                },
            )
        }
    }
}

/**
 * UnifiedPush distributor picker (issue #17): shown ONLY when several distributors
 * are installed and none has been chosen yet (UX rule — one installed is used
 * silently, none means nothing happens; no settings entry, no transport wording).
 */
@Composable
private fun DistributorPickerDialog() {
    val context = LocalContext.current
    val manager = (context.applicationContext as Application).container.unifiedPushManager
    val needsChoice by manager.needsDistributorChoice.collectAsStateWithLifecycle()
    if (!needsChoice) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { manager.dismissDistributorChoice() },
        title = { androidx.compose.material3.Text(stringResource(R.string.up_picker_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                val distributors = manager.distributors()
                // When two distributors share an app label (e.g. a system "ntfy" and the user's
                // own "ntfy" install — Codeberg #17), the plain label is ambiguous. Append the
                // package name only for the colliding ones, so a single distributor stays clean.
                val labelCounts = distributors.groupingBy { appLabelOf(context, it) }.eachCount()
                distributors.forEach { pkg ->
                    val label = appLabelOf(context, pkg)
                    val shown = if ((labelCounts[label] ?: 0) > 1) "$label ($pkg)" else label
                    androidx.compose.material3.TextButton(onClick = { manager.distributorChosen(pkg) }) {
                        androidx.compose.material3.Text(shown)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { manager.dismissDistributorChoice() }) {
                androidx.compose.material3.Text(stringResource(R.string.inbox_cancel))
            }
        },
    )
}

/** Best-effort human app label for a package (falls back to the package name). */
internal fun appLabelOf(context: android.content.Context, packageName: String?): String {
    packageName ?: return "UnifiedPush"
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
