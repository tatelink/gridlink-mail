package app.gridlink.core.data.account

import kotlinx.serialization.Serializable

/**
 * What the user asked this account to sync, chosen during setup and editable afterwards.
 *
 * ## 🔴 Two of these three do nothing yet, and that is recorded here rather than hidden
 * [mail] is real: it drives the JMAP client that has existed since upstream. [calendar] and
 * [contacts] have no engine behind them at all — `core/jmap` speaks Email, Mailbox, Identity, Sieve,
 * Vacation, Quota and Push, and there is no CalDAV or CardDAV client anywhere in the tree. Storing
 * the answer before the engine exists is deliberate: the setup screen has to ask the question once,
 * at the only moment the user is thinking about it, and the alternative is asking again later in a
 * settings screen nobody opens.
 *
 * ⚠️ So the honesty burden sits on the UI, not on this type. Whatever screen offers these toggles
 * must say that two of them are a stored preference and not a running sync. A toggle that flips,
 * persists, and quietly fetches nothing is worse than no toggle: it teaches the user that the app
 * lies, and they then distrust the one toggle that is telling the truth.
 *
 * Defaults are mail-on, everything-else-off, which is both the honest default (only mail works) and
 * the conservative one (nothing is enabled on the user's behalf).
 */
@Serializable
data class SyncSelection(
    /** Mail. Live: this is the JMAP sync the rest of the app is built on. */
    val mail: Boolean = true,
    /** ⚠️ Recorded only. No CalDAV client exists yet, so nothing is fetched. */
    val calendar: Boolean = false,
    /** ⚠️ Recorded only. No CardDAV client exists yet, so nothing is fetched. */
    val contacts: Boolean = false,
) {
    /**
     * True when the selection asks for something this build cannot deliver, which is what a UI
     * checks before deciding whether to explain itself. Kept here rather than in the screen so the
     * day CalDAV lands there is one place to change, and the caption stops appearing on its own.
     */
    val hasUnbuiltSelection: Boolean get() = calendar || contacts
}
