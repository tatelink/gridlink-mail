package app.sterna.ui

import androidx.lifecycle.Lifecycle
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
