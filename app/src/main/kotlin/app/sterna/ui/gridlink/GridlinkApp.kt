package app.sterna.ui.gridlink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.sterna.ui.theme.GridlinkMode
import app.sterna.ui.theme.ProvideGridlinkTokens
import app.sterna.ui.theme.gridlinkModeForHour
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
) {
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
     * ⚠️ A mock: it waits and declares victory. The real one fans out one JMAP `Email/changes` per
     * account and this function does not return until the slowest of them has, which is why it is a
     * suspend function rather than a fire-and-forget.
     *
     * 🔴 A pull while offline stays offline and does NOT restamp [lastSyncedAt]. Writing the clock
     * on a sync that did not happen is how "Synced just now" ends up on a mailbox that has not
     * spoken to a server in a day, and that single line is the one thing a user checks first when
     * mail stops arriving.
     *
     * Re-entrant calls are dropped rather than queued: two overlapping syncs would race on the
     * timestamp, and the second one has nothing to add.
     */
    suspend fun syncAllAccounts() {
        if (sync == GridlinkSyncState.SYNCING) return
        val wasOffline = sync == GridlinkSyncState.OFFLINE
        sync = GridlinkSyncState.SYNCING
        delay(GRIDLINK_MOCK_SYNC_MS)
        if (wasOffline) {
            sync = GridlinkSyncState.OFFLINE
        } else {
            sync = GridlinkSyncState.SYNCED
            lastSyncedAt = System.currentTimeMillis()
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
         */
        val Saver: Saver<GridlinkChromeState, Any> = listSaver(
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
                )
            },
        )
    }
}

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
    content: @Composable () -> Unit,
) {
    val chrome = rememberSaveable(saver = GridlinkChromeState.Saver) {
        GridlinkChromeState(
            autoMode = gridlinkModeForHour(LocalTime.now().hour),
            initialSync = initialSync,
            initialModeOverride = initialModeOverride,
            initialLastSyncedAt = System.currentTimeMillis() - GRIDLINK_SAMPLE_SYNC_AGE_MS,
            menuOpenAtStart = menuOpenAtStart,
        )
    }
    CompositionLocalProvider(LocalGridlinkChrome provides chrome) {
        ProvideGridlinkTokens(mode = chrome.mode, content = content)
    }
}
