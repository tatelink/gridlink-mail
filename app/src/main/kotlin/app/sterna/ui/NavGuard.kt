package app.sterna.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry

/**
 * True only while this back-stack entry is the resumed (settled, on-top) destination.
 *
 * A destination that is mid enter/exit transition is at most STARTED, and a destination that
 * has just been navigated away from drops out of RESUMED synchronously inside
 * `NavController.navigate()`/`popBackStack()` — both of them end in
 * `updateBackStackLifecycle()`, which pushes the new state straight into the entry's
 * LifecycleRegistry. So the whole re-entrancy question reduces to this one read.
 */
private fun NavBackStackEntry.isSettled(): Boolean =
    lifecycle.currentState == Lifecycle.State.RESUMED

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
 */
@Composable
internal fun rememberLeaveOnce(entry: NavBackStackEntry): (() -> Boolean) -> Unit {
    val left = remember { mutableStateOf(false) }
    DisposableEffect(entry) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) left.value = false
        }
        entry.lifecycle.addObserver(observer)
        onDispose { entry.lifecycle.removeObserver(observer) }
    }
    return { action ->
        if (!left.value && entry.isSettled()) left.value = action()
    }
}
