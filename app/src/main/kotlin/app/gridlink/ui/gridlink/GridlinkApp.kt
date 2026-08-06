package app.gridlink.ui.gridlink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.gridlink.ui.theme.GridlinkMode
import app.gridlink.ui.theme.ProvideGridlinkTokens
import app.gridlink.ui.theme.gridlinkModeForHour
import kotlinx.coroutines.delay
import java.time.LocalTime

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

    /** What the chrome row's chip says, and the dot beside the address in the menu sheet. */
    var sync by mutableStateOf(initialSync)
        private set

    /** Epoch millis of the last sync that actually completed, or null if none ever has. */
    var lastSyncedAt by mutableStateOf(initialLastSyncedAt)
        private set

    /** null = follow [autoMode]; non-null = the user picked a palette and it wins. */
    var modeOverride by mutableStateOf(initialModeOverride)
        private set

    val mode: GridlinkMode get() = modeOverride ?: autoMode

    val followingClock: Boolean get() = modeOverride == null

    /** Pass null to hand the palette back to the clock. */
    fun selectMode(mode: GridlinkMode?) {
        modeOverride = mode
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
     * Re-entrant calls are dropped rather than queued: two overlapping syncs would race on the
     * timestamp, and the second one has nothing to add.
     */
    suspend fun syncAllAccounts() {
        if (sync == GridlinkSyncState.SYNCING) return
        val wasOffline = sync == GridlinkSyncState.OFFLINE
        val action = config.sync
        sync = GridlinkSyncState.SYNCING
        val succeeded = if (action == null) {
            delay(GRIDLINK_MOCK_SYNC_MS)
            !wasOffline
        } else {
            action.sync()
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
 * 🔴 **Every default here is sample data**, which is what makes the debug gallery work without an
 * account and is exactly what a real build must not accept. A signed-in build passes its own
 * address, its own counts and its own actions; leaving these at their defaults would put
 * `tate@gridlink.me` in the menu of somebody else's mailbox with "4 unsent" under a Drafts folder
 * nobody has looked at.
 */
@Stable
class GridlinkChromeConfig(
    /** The signed-in address, stated in the menu sheet. */
    val account: String = GRIDLINK_SAMPLE_ACCOUNT,
    /** How many accounts exist, for the Accounts row's subtitle. */
    val accountCount: Int = 1,
    /**
     * Counts for the menu's mailbox rows. Empty means "no number", which is the honest answer
     * before anything counts them, and is why this is not defaulted to zero: a Drafts row reading
     * "0 unsent" is a claim, and an absent count is not.
     */
    val menuCounts: Map<GridlinkMenuItem, Int> = GRIDLINK_SAMPLE_MENU_COUNTS,
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
)

val LocalGridlinkChrome = staticCompositionLocalOf { GridlinkChromeState() }

/**
 * Everything Gridlink needs above the first screen: the state holder, and the palette it resolves to.
 *
 * One host rather than two nested providers, because the mode is not independent of the state — it
 * IS [GridlinkChromeState.mode], and a caller that provided the chrome local and then separately
 * chose a palette could disagree with itself. Here that is not expressible.
 *
 * The three seeds exist for the debug gallery, which opens straight into a named palette and a named
 * sync state so a screenshot does not depend on the time of day or on the server being down. A real
 * build calls this with no arguments.
 */
@Composable
fun GridlinkApp(
    initialSync: GridlinkSyncState = GridlinkSyncState.SYNCED,
    initialModeOverride: GridlinkMode? = null,
    menuOpenAtStart: Boolean = false,
    /**
     * Who is signed in and what the chrome's actions do. Defaults to the sample; a real build
     * always passes its own. See [GridlinkChromeConfig].
     */
    config: GridlinkChromeConfig = GridlinkChromeConfig(),
    /**
     * When the last successful sync was, at launch.
     *
     * 🔴 Defaults to a plausible few minutes ago, which is sample data and is why a real build has
     * to pass null. "Synced 12 minutes ago" on an account that has never once reached a server is
     * the exact false reassurance [GridlinkChromeState.syncAllAccounts] refuses to write, arriving
     * by a different door.
     */
    initialLastSyncedAt: Long? = System.currentTimeMillis() - GRIDLINK_SAMPLE_SYNC_AGE_MS,
    content: @Composable () -> Unit,
) {
    val chrome = rememberSaveable(saver = GridlinkChromeState.saver(config)) {
        GridlinkChromeState(
            autoMode = gridlinkModeForHour(LocalTime.now().hour),
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
