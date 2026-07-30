package app.sterna.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry

/**
 * True only while this lifecycle is the resumed (settled, on-top) one.
 *
 * A destination that is mid enter/exit transition is at most STARTED, and a destination that
 * has just been navigated away from drops out of RESUMED synchronously inside
 * `NavController.navigate()`/`popBackStack()` — both of them end in
 * `updateBackStackLifecycle()`, which pushes the new state straight into the entry's
 * LifecycleRegistry. So the whole re-entrancy question reduces to this one read.
 */
private fun Lifecycle.isSettled(): Boolean = currentState == Lifecycle.State.RESUMED

private fun NavBackStackEntry.isSettled(): Boolean = lifecycle.isSettled()

/**
 * THE single decision point for every navigation action in the app — the outer graph
 * (MainNavHost) and the settings graph alike. Every `nav.navigate(...)` / `nav.popBackStack()`
 * driven by a UI event goes through here; a new NavHost that forgets it is the defect this
 * exists to prevent (Codeberg #106: the settings graph was written without the guard the outer
 * graph already had, and rapid or multi-touch taps stacked several copies of the same page).
 *
 * [action] runs only if this entry is still the settled destination. That kills three things at
 * once, with no timer to tune:
 *  - a second tap landing on a screen that is already animating away (the extra back-stack
 *    entries of #106);
 *  - two fingers landing on two rows in the same frame — the first navigate demotes this entry
 *    below RESUMED before the second one is dispatched, so the second is dropped;
 *  - a tap on a Back arrow still visible during a pop, which used to pop a second time and empty
 *    the stack (the white-screen freeze).
 *
 * When the action is a pop whose *failure* means something — the account detail deep-linked from
 * the drawer, where `popBackStack()` returning false is the cue to fall through to the caller's
 * own Back (#34) — put the whole `if (!nav.popBackStack()) onBack()` inside [action]. Suppressing
 * a re-entrant tap then suppresses the fall-through too, which is what we want: an ignored tap
 * must not close settings.
 *
 * Deliberately NOT used for single-consumption navigations (a tapped `mailto:` link, a tapped
 * notification): those arrive once, possibly while a transition is running, and dropping one
 * loses the user's action rather than de-duplicating it.
 */
internal fun NavBackStackEntry.navigateOnce(action: () -> Unit) {
    if (isSettled()) action()
}

/**
 * The same guarantee as [navigateOnce] for an action that LEAVES the app — the About rows that
 * hand a URL to a browser (#106 follow-up: they were left out of the navigation pass because they
 * do not navigate, and a double tap opened the browser twice).
 *
 * [navigateOnce] alone cannot cover them. It works because `navigate()` demotes the entry below
 * RESUMED *synchronously*, before the second tap is dispatched. `startActivity()` does not: the
 * browser takes hundreds of milliseconds to come up and our activity stays resumed meanwhile, so a
 * second tap arrives with the entry still settled and starts a second one. The lifecycle read
 * therefore has to be paired with a latch that remembers we already left.
 *
 * The latch is released by the entry returning to RESUMED, i.e. by the user actually coming back to
 * this screen — not by a timer. There is no delay to tune and nothing to get wrong on a slow
 * device: while the browser is up, the app owes the user nothing, and the first tap that lands
 * after they return is honoured. The latch is shared by every caller holding the same opener, so
 * two fingers on two different rows in one frame also open one page, which is the behaviour
 * [navigateOnce] gives the rows just above them.
 *
 * [action] reports whether it really did leave, and only then is the latch armed: on a device with
 * no browser at all the launch throws, nothing happened on screen, and the row must stay live
 * rather than go quietly dead until the user walks out of this screen and back.
 *
 * Returns the opener rather than gating inside the row component on purpose: SettingsCategoryRow
 * has thirteen call sites, nine of which navigate and are already guarded — a guard in there would
 * be a tap-debounce hidden in a shared widget.
 *
 * Each call of this function owns ONE latch, shared by every hand-off that holds the opener it
 * returned. Ask for it once per screen (as the settings hub does) and two rows tapped together open
 * one page; ask for it inside a single control and the latch is that control's alone, which is what
 * a control whose neighbours are unrelated actions wants — two participants added to Contacts one
 * after the other are two intentions, not a stutter.
 */
@Composable
internal fun rememberLeaveOnce(entry: NavBackStackEntry): (() -> Boolean) -> Unit =
    rememberLeaveOnce(entry.lifecycle)

/**
 * [rememberLeaveOnce] for a hand-off written deep inside a screen, where the destination's
 * [NavBackStackEntry] is many composables above and threading it down would mean a parameter on
 * every one of them — the message reader's link, Add to contacts and Add to calendar are eight,
 * four and five levels below their destination respectively.
 *
 * It reads the same object by another route: inside a NavHost destination `LocalLifecycleOwner` IS
 * that destination's back-stack entry (navigation-compose provides it alongside the ViewModel and
 * saved-state owners), which is why `collectAsStateWithLifecycle` stops collecting when you
 * navigate away, and why the inbox can hang its return-highlight off ON_START. So this is the
 * entry-based guard, obtained without carrying the entry.
 *
 * On a screen that is NOT a nav destination — the connect screen at first run, which is composed
 * above the graph — the owner is the activity instead, and the guarantee degrades to exactly what
 * such a screen can promise: RESUMED means the app is in front, and the latch is released when the
 * user comes back from the browser. That is the whole of what the guard is for there.
 */
@Composable
internal fun rememberLeaveOnce(): (() -> Boolean) -> Unit =
    rememberLeaveOnce(LocalLifecycleOwner.current.lifecycle)

@Composable
private fun rememberLeaveOnce(lifecycle: Lifecycle): (() -> Boolean) -> Unit {
    val latch = remember(lifecycle) { LeaveLatch() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latch.release()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return { action -> latch.leave(lifecycle.isSettled(), action) }
}

/**
 * The decision [rememberLeaveOnce] makes, on its own so it can be stated in one place and tested
 * without a composition, a lifecycle or a device: we left already, or the screen is not settled, or
 * the launch reported nothing happened — three ways for a tap to change nothing, and the reason the
 * caller's `startActivity` has to report back rather than swallow its exception.
 *
 * Plain fields, not Compose state: nothing reads the latch while composing (only tap handlers do),
 * so making it observable would only invalidate the screen every time the user leaves it.
 */
internal class LeaveLatch {
    private var left = false

    /** Run [action] and remember we left, unless we already did or [settled] says we should not. */
    fun leave(settled: Boolean, action: () -> Boolean) {
        if (!left && settled) left = action()
    }

    /** The user came back (ON_RESUME): the next tap is theirs again. */
    fun release() {
        left = false
    }
}
