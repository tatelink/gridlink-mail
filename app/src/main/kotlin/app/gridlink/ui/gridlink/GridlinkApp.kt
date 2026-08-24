package app.gridlink.ui.gridlink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.gridlink.core.data.settings.GridlinkPalette
import app.gridlink.ui.theme.GridlinkMode
import app.gridlink.ui.theme.ProvideGridlinkTokens
import app.gridlink.ui.theme.gridlinkModeAt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/**
 * The facts that belong to the app rather than to any one screen, and the host that provides them.
 *
 * ## Why these three live together
 * The palette mode, the connection state and the time of the last sync look unrelated until you try
 * to build the menu sheet, at which point they are all one thing: the sheet states all three, the
 * pull gesture on the mail list writes two of them, and the chrome row above every screen reads one.
 * Split across three holders they would need three providers and one of them would end up seeded
 * from a different place than the other two.
 *
 * ## Why the mode moved out of the gallery
 * 🔴 Day / Night / OLED used to be `var override` inside the debug gallery, which meant the shipping
 * app had no theme control at all and the only way to change palette was a long-press gesture that
 * exists in `src/debug`. Tate asked for the control to be in the hamburger menu, so the state has
 * to be somewhere production can reach: here. The gallery now seeds this holder rather than owning a
 * private copy of it.
 */

/**
 * ⚠️ Sample data. Seeds "last synced" far enough back that the menu sheet says something specific
 * ("Synced 4 min ago") rather than "just now" in every screenshot, and near enough that it is
 * obviously not stale. A real build stamps this from the JMAP session's last successful response.
 */
private const val GRIDLINK_SAMPLE_SYNC_AGE_MS = 4L * 60L * 1000L

/**
 * ⚠️ How long the mock sync pretends to take. Long enough that the chrome row visibly flips to
 * Syncing and back, which is the whole point of having the gesture in the prototype at all.
 */
private const val GRIDLINK_MOCK_SYNC_MS = 1_400L

/**
 * App-level chrome state: which palette, how the connection is doing, when it last worked, and
 * whether the menu sheet starts open.
 *
 * 🔴 Reached through a CompositionLocal and not through parameters, because the alternative is four
 * screens accepting arguments they never read and forwarding them to [GridlinkScaffold] verbatim.
 * That kind of pass-through survives exactly until someone adds a fifth screen, and what it fails at
 * is silent: the new screen renders a hard-coded "Synced" and nobody notices until the day the
 * server is down.
 *
 * 🔴 A `@Stable` class with observable properties, not the `@Immutable data class` this used to be.
 * The pull-to-refresh gesture has to WRITE [sync] and [lastSyncedAt], and the menu sheet has to
 * write [modeOverride]. A data class would have meant handing every writer a callback and holding
 * the real state one level further up, which is the same object with more wiring.
 *
 * [autoMode] is fixed for the lifetime of the holder rather than re-read from the clock on every
 * frame. The ladder's boundaries are hours apart, and a palette that changes underneath a user
 * mid-session because a clock ticked past 20:00 is a worse surprise than one that waits for the next
 * launch. [menuOpenAtStart] is a harness affordance, so a launch can screenshot the sheet without a
 * tap; it seeds the state and does not pin it, so dismissing the sheet actually dismisses it.
 */
@Stable
class GridlinkChromeState(
    val autoMode: GridlinkMode = GridlinkMode.NIGHT,
    initialSync: GridlinkSyncState = GridlinkSyncState.SYNCED,
    initialModeOverride: GridlinkMode? = null,
    initialLastSyncedAt: Long? = null,
    val menuOpenAtStart: Boolean = false,
    initialConfig: GridlinkChromeConfig = GridlinkChromeConfig(),
) {
    /**
     * The facts the app around this holder supplies: who is signed in, and what the menu does.
     *
     * 🔴 A `var`, and written from [GridlinkApp] on every composition, because the account can
     * CHANGE while this holder lives. Captured once at construction, switching account would leave
     * the menu sheet stating the address of the mailbox you just left, which is the single line a
     * user reads to check they are looking at the right mailbox.
     */
    var config by mutableStateOf(initialConfig)
        internal set

    /**
     * A menu row the drawer wants the scaffold to navigate to, or null when there is nothing
     * pending.
     *
     * ## Why this channel exists
     * The drawer is rendered by the chrome shell, ABOVE [GridlinkRoot] in the tree, and its rows
     * dispatch to [GridlinkChromeConfig.onSelectMenu], which the HOST supplies. Drafts and
     * Scheduled are not host destinations though: Drafts is a folder the scaffold can already
     * open, and Scheduled is a scaffold overlay. The host cannot reach that state, and threading
     * a callback down through it would mean the host forwarding an argument it never reads. So
     * the shell posts the wish here and whichever scaffold is composed consumes it.
     *
     * 🔴 Deliberately NOT in [saver]: a navigation wish is an event, not a fact about the app,
     * and replaying it after an unfold would re-open Drafts over whatever the user had moved on
     * to. The nonce makes tapping the same row twice distinguishable, or the second tap would
     * equal the first and never be observed.
     */
    var menuRoute by mutableStateOf<Pair<GridlinkMenuItem, Int>?>(null)
        private set

    private var menuRouteNonce = 0

    /** Post a navigation wish for the scaffold. Called by the drawer's dispatch, on the main thread. */
    fun routeMenu(item: GridlinkMenuItem) {
        menuRouteNonce += 1
        menuRoute = item to menuRouteNonce
    }

    /** Mark the pending wish handled. Idempotent. */
    fun consumeMenuRoute() {
        menuRoute = null
    }

    /**
     * The account's mailboxes, as the drawer lists them.
     *
     * 🔴 Published UP from [GridlinkRoot] rather than passed DOWN from the host, which is backwards
     * from everything else on this holder and is the only shape available. The tree is resolved
     * inside the root (real mailboxes when there are any, the sample tree otherwise), the drawer is
     * composed by the scaffold, and the scaffold is composed by the four screens *below* the root.
     * There is no common parent holding the tree, so the root states it here and the drawer reads it.
     *
     * Empty is the honest default: before a root has composed, nobody has said what the mailboxes
     * are, and the drawer draws no folder group at all rather than an empty one.
     */
    var folders by mutableStateOf<List<GridlinkFolder>>(emptyList())
        internal set

    /**
     * A mailbox the drawer wants opened, or null. [GridlinkFolder.id] of null means "the folder
     * screen itself" — the drawer's Manage folders row, which is the only route to it now that the
     * nav pill has no Folders seat.
     *
     * Same event-not-fact rules as [menuRoute], including the nonce: tapping the same mailbox twice
     * has to be two events, or the second tap is equal to the first and is never observed.
     */
    var folderRoute by mutableStateOf<Pair<String?, Int>?>(null)
        private set

    private var folderRouteNonce = 0

    /** Post a mailbox to open, or null for the folder screen. Called by the drawer, on the main thread. */
    fun routeFolder(id: String?) {
        folderRouteNonce += 1
        folderRoute = id to folderRouteNonce
    }

    /** Mark the pending mailbox wish handled. Idempotent. */
    fun consumeFolderRoute() {
        folderRoute = null
    }

    /**
     * A request to go to the mail LIST, posted by the drawer's merged-inbox pair.
     *
     * 🔴 Not [folderRoute] with the inbox id. That channel lands on the folder screen, which is a
     * different surface from the list the pair is switching: tapping "All inboxes" and arriving on
     * the folder screen showing one mailbox would be the opposite of merging. Same event-not-fact
     * rules and the same nonce as the two channels above.
     */
    var inboxRoute by mutableStateOf<Int?>(null)
        private set

    private var inboxRouteNonce = 0

    /** Ask the scaffold for the mail list. Called by the drawer, on the main thread. */
    fun routeInbox() {
        inboxRouteNonce += 1
        inboxRoute = inboxRouteNonce
    }

    /** Mark the pending list wish handled. Idempotent. */
    fun consumeInboxRoute() {
        inboxRoute = null
    }

    /** What the chrome row's chip says, and the dot beside the address in the menu sheet. */
    var sync by mutableStateOf(initialSync)
        private set

    /** Epoch millis of the last sync that actually completed, or null if none ever has. */
    var lastSyncedAt by mutableStateOf(initialLastSyncedAt)
        private set

    /**
     * Whether a sync is genuinely in flight, as opposed to whether the chip merely SAYS so.
     *
     * 🔴 These are not the same fact, and conflating them deadlocked the app. [sync] is a display
     * state that a caller is allowed to seed: the signed-in host opens on SYNCING deliberately, so
     * the chip does not spend its first second claiming a freshness it has not earned. The
     * re-entrancy guard in [syncAllAccounts] used to read that same field, so the launch sync looked
     * like a second overlapping sync, returned immediately, and left the chip spinning forever over
     * a mailbox that never refreshed through this path. Not a state a test caught, because both
     * halves are correct alone.
     *
     * Deliberately NOT `by mutableStateOf`: nothing draws it, and making it observable would invite
     * exactly the confusion with [sync] that this field exists to end.
     *
     * 🔴 A [CompletableDeferred] and no longer a Boolean, so a second caller can WAIT on the sync
     * that is already out instead of being turned away. See [syncAllAccounts]: a dropped call used
     * to return instantly, which made the pull gesture do nothing at all for as long as any other
     * sync was running — including the one every cold launch fires — and "I pulled and nothing
     * happened" is indistinguishable from a broken gesture.
     *
     * Completed in a `finally`, so cancelling the coroutine that owns a sync releases everyone
     * waiting on it rather than parking them until the process dies.
     */
    private var syncInFlight: CompletableDeferred<Unit>? = null

    /** null = follow [autoMode]; non-null = the user picked a palette and it wins. */
    var modeOverride by mutableStateOf(initialModeOverride)
        private set

    val mode: GridlinkMode get() = modeOverride ?: autoMode

    val followingClock: Boolean get() = modeOverride == null

    /**
     * Pass null to hand the palette back to the clock.
     *
     * 🔴 Reports the choice to [GridlinkChromeConfig.onSelectMode] as well as applying it, which is
     * what makes the pill survive a cold launch. The state is written FIRST and unconditionally:
     * persistence is somebody else's slow business, and a palette that waited for a disk write
     * before repainting would make a tap on the pill feel broken. The gallery leaves the callback
     * at its default and its palette is correctly a per-launch thing.
     */
    fun selectMode(mode: GridlinkMode?) {
        modeOverride = mode
        config.onSelectMode(mode)
    }

    /**
     * Sync every account, as the mail list's pull gesture asks for.
     *
     * With a [GridlinkChromeConfig.sync] wired this fans out one refresh per account and does not
     * return until the slowest of them has, which is why it is a suspend function rather than
     * fire-and-forget.
     *
     * ⚠️ Without one it is a mock that waits and leaves the state exactly where it found it. That
     * asymmetry is deliberate and is why the mock cannot simply "succeed": a gallery seeded OFFLINE
     * is a screenshot of the offline chip, and a mock that flipped it to SYNCED would destroy the
     * one state it was launched to photograph. A real sync has a real answer, so it gets to change
     * the state; a fake one has nothing to report.
     *
     * 🔴 A sync that did not succeed does NOT restamp [lastSyncedAt]. Writing the clock on a sync
     * that failed is how "Synced just now" ends up on a mailbox that has not spoken to a server in
     * a day, and that single line is the first thing a user checks when mail stops arriving.
     *
     * 🔴 Re-entrant calls WAIT for the sync already in flight and then return, rather than starting
     * a second one (two would race on the timestamp) or returning immediately (which is what this
     * used to do, and it is Tate's "release to refresh not working": every cold launch fires a
     * sync from [GridlinkHomeHost], so a pull in the first seconds of the app was answered by an
     * instant no-op, and the indicator snapped back before it had finished appearing). Waiting is
     * the honest answer to "refresh": the caller is released when fresh mail has actually landed.
     */
    suspend fun syncAllAccounts() {
        // Guarded on [syncInFlight], NOT on the chip reading SYNCING. See that field: a caller may
        // legitimately open on SYNCING before any sync has started, and reading the display state
        // here made the very first sync mistake itself for a duplicate and do nothing.
        syncInFlight?.let { running ->
            // `runCatching`, because this deferred is only ever completed and never failed — but a
            // waiter must not inherit an exception from a sync it did not start either way.
            runCatching { running.await() }
            return
        }
        val wasOffline = sync == GridlinkSyncState.OFFLINE
        val action = config.sync
        val gate = CompletableDeferred<Unit>()
        syncInFlight = gate
        sync = GridlinkSyncState.SYNCING
        val succeeded = try {
            if (action == null) {
                delay(GRIDLINK_MOCK_SYNC_MS)
                !wasOffline
            } else {
                action.sync()
            }
        } finally {
            // In a `finally` so a cancelled pull gesture (the composition leaving mid-sync) does not
            // leave the flag stuck and every later sync silently dropped. The chip is left alone on
            // that path on purpose: a cancelled sync has no result to report, and painting OFFLINE
            // over a healthy mailbox is the failure this whole class is careful about.
            syncInFlight = null
            gate.complete(Unit)
        }
        if (succeeded) {
            sync = GridlinkSyncState.SYNCED
            lastSyncedAt = System.currentTimeMillis()
        } else {
            sync = GridlinkSyncState.OFFLINE
        }
    }

    companion object {
        /**
         * Survives a configuration change, and on a Fold the configuration change that matters is
         * unfolding. Losing the chosen palette every time the hinge opens would make the mode
         * control useless on the one device this app is designed around.
         *
         * Nullables are encoded rather than stored: an empty string for "no override" and -1 for
         * "never synced", so every element of the saved list is a type a Bundle takes without
         * question.
         *
         * 🔴 A function rather than a value, because [GridlinkChromeConfig] holds lambdas, which
         * cannot go in a Bundle and must not be dropped on the way through one. A saver that
         * restored without it would hand back a chrome whose pull-to-refresh had quietly reverted
         * to the mock: the gesture would still animate, the chip would still say "Synced", and no
         * mail would be fetched, from the moment the user unfolded the phone. The config is
         * supplied fresh by the caller on restore, which is correct anyway, since it is live
         * objects and not saved facts.
         */
        fun saver(config: GridlinkChromeConfig): Saver<GridlinkChromeState, Any> = listSaver(
            save = {
                listOf(
                    it.autoMode.name,
                    it.sync.name,
                    it.modeOverride?.name.orEmpty(),
                    it.lastSyncedAt ?: -1L,
                    it.menuOpenAtStart,
                )
            },
            restore = { saved ->
                GridlinkChromeState(
                    autoMode = GridlinkMode.valueOf(saved[0] as String),
                    initialSync = GridlinkSyncState.valueOf(saved[1] as String),
                    initialModeOverride = (saved[2] as String)
                        .takeIf { it.isNotEmpty() }
                        ?.let(GridlinkMode::valueOf),
                    initialLastSyncedAt = (saved[3] as Long).takeIf { it >= 0L },
                    menuOpenAtStart = saved[4] as Boolean,
                    initialConfig = config,
                )
            },
        )
    }
}

/**
 * What the pull-to-refresh gesture and the empty state's "tap to check" actually do.
 *
 * Returns whether the sync succeeded, and nothing else. The chrome does not want the mail, the
 * error, or the counts: it owns one three-state chip and a timestamp, and both are decided by that
 * single boolean. Anything richer belongs to whoever is holding the mailbox.
 */
fun interface GridlinkSyncAction {
    suspend fun sync(): Boolean
}

/**
 * What the app around the Gridlink screens supplies to their chrome: who is signed in, what the
 * menu's rows lead to, and what a sync does.
 *
 * ## Why one object rather than five parameters
 * Every one of these is a live thing (a lambda, an address that changes with the account) that the
 * [GridlinkChromeState.saver] has to be handed again on restore rather than reading out of a Bundle.
 * As separate parameters that is a saver signature that grows by one argument every time the app
 * learns to do something else, and each new one is a fresh chance to forget it in the restore path,
 * where forgetting it looks like nothing at all until the phone is unfolded.
 *
 * ## 🔴 The defaults are EMPTY, and that is the whole point
 * These used to default to the sample identity so the debug gallery worked with no arguments, which
 * meant every caller that forgot to pass a config silently inherited `tate@gridlink.me` in the
 * menu of somebody else's mailbox, with "4 unsent" under a Drafts folder nobody had looked at. The
 * sample data has to be ASKED for now: [gridlinkSampleChromeConfig] is what the gallery passes, and
 * a build that forgets says nothing rather than saying something false. Nothing here may ever be
 * defaulted to a value that reads as a fact about a real account.
 */
@Stable
class GridlinkChromeConfig(
    /**
     * The signed-in address, stated in the menu sheet. Empty means "nobody has said", and the row
     * omits the line entirely rather than printing a blank one.
     */
    val account: String = "",
    /** How many accounts exist, for the Accounts row's subtitle. */
    val accountCount: Int = 1,
    /**
     * Counts for the menu's mailbox rows. Empty means "no number", which is the honest answer
     * before anything counts them, and is why this is not defaulted to zero: a Drafts row reading
     * "0 unsent" is a claim, and an absent count is not.
     */
    val menuCounts: Map<GridlinkMenuItem, Int> = emptyMap(),
    /**
     * What a menu row does. The sheet closes either way, so a row wired to nothing is a row that
     * dismisses rather than one that navigates somewhere empty.
     */
    val onSelectMenu: (GridlinkMenuItem) -> Unit = {},
    /**
     * What a sync actually does, or null when nothing is wired and the mock stands in.
     *
     * 🔴 Null is the sample's answer, not a disabled feature: the mock waits and leaves the state
     * where it found it, which is what a screenshot of a syncing chip needs and what an account
     * must never get. See [GridlinkChromeState.syncAllAccounts].
     */
    val sync: GridlinkSyncAction? = null,
    /**
     * Where a palette choice goes to be remembered. Null means "the clock", matching
     * [GridlinkChromeState.modeOverride].
     *
     * ⚠️ Called on the main thread from a tap, so it must not block: the signed-in host launches a
     * DataStore write and returns. Defaulting to a no-op is right for the debug gallery, whose
     * whole job is to sit in one named palette for one screenshot and forget it afterwards.
     */
    val onSelectMode: (GridlinkMode?) -> Unit = {},
    /**
     * The merged-inbox pair for the drawer, or null to draw neither row.
     *
     * 🔴 Null is the correct default under this class's own rule: a drawer row claiming "All
     * inboxes · 12 unread" in a build that has not counted anything is a fact about mail that may
     * not exist. One account is also null, which is the placement Tate settled on ("appearing
     * only with more than one account"). See [GridlinkUnifiedInbox].
     */
    val unifiedInbox: GridlinkUnifiedInbox? = null,
    /**
     * True merges every account's inbox, false returns to the bound account's.
     *
     * ⚠️ Called on the main thread from a tap. The host writes the preference and lets the list
     * re-subscribe; nothing here waits for the disk.
     */
    val onSelectUnified: (Boolean) -> Unit = {},
)

/**
 * The chrome the debug gallery and the screenshots run on: the sample identity and its counts.
 *
 * 🔴 This exists so the sample data has exactly one door and it has to be opened deliberately.
 * [GridlinkChromeConfig]'s own defaults are empty on purpose; anything that wants an address in the
 * menu says so here, and `grep gridlinkSampleChromeConfig` is then the complete list of places the
 * prototype claims to be somebody.
 */
fun gridlinkSampleChromeConfig(): GridlinkChromeConfig = GridlinkChromeConfig(
    account = GRIDLINK_SAMPLE_ACCOUNT,
    menuCounts = GRIDLINK_SAMPLE_MENU_COUNTS,
)

/**
 * A plausible "synced a few minutes ago", for the gallery.
 *
 * A function and not a constant: it is relative to now, and frozen at class-init it would drift
 * into "synced 3 hours ago" over a long screenshot session.
 */
fun gridlinkSampleLastSyncedAt(): Long = System.currentTimeMillis() - GRIDLINK_SAMPLE_SYNC_AGE_MS

/**
 * The persisted palette as the chrome's override, with [GridlinkPalette.AUTO] becoming null.
 *
 * 🔴 Two enums rather than one on purpose, and this is the seam between them. `GridlinkMode?` is a
 * palette that may be absent, which is what the theme needs; [GridlinkPalette] is a stored choice
 * with a name for "no choice", which is what a preferences file needs, since null is not a value
 * DataStore can hold and "key missing" already means "never set". Collapsing them would make the
 * default indistinguishable from a user who deliberately picked the clock.
 */
fun GridlinkPalette.toModeOverride(): GridlinkMode? = when (this) {
    GridlinkPalette.AUTO -> null
    GridlinkPalette.DAY -> GridlinkMode.DAY
    GridlinkPalette.NIGHT -> GridlinkMode.NIGHT
    GridlinkPalette.OLED -> GridlinkMode.OLED
}

/**
 * The palette a subtree should paint in, for the subtrees that are NOT under the chrome.
 *
 * 🔴 There is exactly one rule for turning a stored [GridlinkPalette] into a real
 * [GridlinkMode], and this is it: an explicit pin wins, and AUTO asks the sun. Every second
 * resolution in the app goes through here rather than restating the two lines, because the failure
 * mode of restating them is silent — two subtrees each look internally consistent and disagree with
 * each other, and nothing type-checks that away. [rememberGridlinkIntroMode] and the settings
 * subtree in `AppNavHost` are the two callers.
 *
 * ⚠️ This is NOT how the mail UI gets its palette. That comes from
 * [GridlinkChromeState.mode], which resolves the same way but is held in a `rememberSaveable`
 * holder so the drawer's pill can move it without a round trip through DataStore. Callers of this
 * function are the screens that sit outside that holder's reach.
 *
 * ⚠️ Keyed on [palette] only, so an AUTO subtree that outlives dusk keeps the rung it
 * opened in rather than changing colour under the reader. That matches the chrome, which freezes
 * its `autoMode` when the holder is born, and it is the reason the two agree in practice.
 */
@Composable
fun rememberGridlinkMode(palette: GridlinkPalette): GridlinkMode = remember(palette) {
    palette.toModeOverride() ?: gridlinkModeAt(ZonedDateTime.now())
}

/** The inverse of [toModeOverride]: what to write down when the user taps a pill. */
fun GridlinkMode?.toGridlinkPalette(): GridlinkPalette = when (this) {
    null -> GridlinkPalette.AUTO
    GridlinkMode.DAY -> GridlinkPalette.DAY
    GridlinkMode.NIGHT -> GridlinkPalette.NIGHT
    GridlinkMode.OLED -> GridlinkPalette.OLED
}

val LocalGridlinkChrome = staticCompositionLocalOf { GridlinkChromeState() }

/**
 * Everything Gridlink needs above the first screen: the state holder, and the palette it resolves to.
 *
 * One host rather than two nested providers, because the mode is not independent of the state — it
 * IS [GridlinkChromeState.mode], and a caller that provided the chrome local and then separately
 * chose a palette could disagree with itself. Here that is not expressible.
 *
 * The seeds exist for the debug gallery, which opens straight into a named palette and a named
 * sync state so a screenshot does not depend on the time of day or on the server being down.
 */
@Composable
fun GridlinkApp(
    initialSync: GridlinkSyncState = GridlinkSyncState.SYNCED,
    /**
     * The palette to open pinned to, or null to follow the sun.
     *
     * 🔴 Read ONCE, when the holder is first created, and never again: that is what remembering it
     * means. So a caller reading this out of DataStore must not compose [GridlinkApp] until the
     * first value has arrived, or the holder is born with the null it saw at frame one and the
     * pinned palette silently never applies on a cold launch. See `GridlinkHomeHost`, which holds
     * the whole subtree back for the one frame that takes.
     */
    initialModeOverride: GridlinkMode? = null,
    menuOpenAtStart: Boolean = false,
    /**
     * Who is signed in and what the chrome's actions do. Defaults to the sample; a real build
     * always passes its own. See [GridlinkChromeConfig].
     */
    config: GridlinkChromeConfig = GridlinkChromeConfig(),
    /**
     * When the last successful sync was, at launch. Null means nothing has synced yet this launch,
     * which is the truth for every build until something actually reaches a server.
     *
     * 🔴 This defaulted to a plausible few minutes ago for the gallery's benefit, so a caller that
     * said nothing got "Synced 4 minutes ago" on an app that had never once spoken to a server.
     * That is the exact false reassurance [GridlinkChromeState.syncAllAccounts] refuses to write,
     * arriving by a different door. The gallery asks for it by name now:
     * [gridlinkSampleLastSyncedAt].
     */
    initialLastSyncedAt: Long? = null,
    content: @Composable () -> Unit,
) {
    val chrome = rememberSaveable(saver = GridlinkChromeState.saver(config)) {
        GridlinkChromeState(
            // 🔴 The real sun, not a fixed hour. See [gridlinkModeAt]: 20:00 is an hour and a half
            // after December dusk here and half an hour before June dusk, so the fixed ladder was
            // bright while it was dark out for a third of the year.
            autoMode = gridlinkModeAt(ZonedDateTime.now()),
            initialSync = initialSync,
            initialModeOverride = initialModeOverride,
            initialLastSyncedAt = initialLastSyncedAt,
            menuOpenAtStart = menuOpenAtStart,
            initialConfig = config,
        )
    }
    // 🔴 The holder outlives the call that created it (that is what remembering it means), so the
    // config it was BORN with goes stale the moment the account changes. Re-stated here every
    // composition instead. Cheap, because the caller is expected to remember the config object
    // itself: an equal-but-new instance every frame would recompose the menu sheet every frame.
    SideEffect { chrome.config = config }
    CompositionLocalProvider(LocalGridlinkChrome provides chrome) {
        ProvideGridlinkTokens(mode = chrome.mode, content = content)
    }
}
