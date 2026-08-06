package app.gridlink.ui.gridlink

import androidx.compose.runtime.Immutable
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import java.time.LocalDate

/**
 * The appointments a Gridlink screen is showing, when something behind it has real ones.
 *
 * The Calendar tab's counterpart to [GridlinkMailContent] and [GridlinkFolderContent], and
 * deliberately the same shape.
 *
 * 🔴 The null rule from [GridlinkMailContent] applies word for word. **Null is not "no events"** —
 * null is "nobody is supplying a calendar", and the screens fall back to [GridlinkSampleTree]. An
 * account with a genuinely empty calendar supplies a [GridlinkCalendarContent] with an empty
 * [events]. Collapsing the two would paint a signed-in user's real month with invented appointments.
 */
@Immutable
data class GridlinkCalendarContent(
    /** Every occurrence inside [window], already expanded from its recurrence rules. */
    val events: List<GridlinkEvent>,
    /**
     * The real date, so the month grid can ring the real today.
     *
     * 🔴 Carried on the content rather than read from the clock inside the screen, because the sample
     * calendar is built around a fixed [GridlinkSampleTree.TODAY] and a `@Preview` that suddenly
     * highlighted the actual date would sit in a month with no sample events in it at all.
     */
    val today: LocalDate,
    /**
     * True before the event cache has answered once: the header says nothing rather than "0 events".
     *
     * Only the FIRST read, for [GridlinkMailContent.loading]'s reason. A refresh over a month already
     * on screen is a sync, and the chrome row's chip owns saying so.
     */
    val loading: Boolean = false,
    /**
     * The span [events] was expanded over, inclusive.
     *
     * ⚠️ Load-bearing for honesty rather than for drawing. The window is a fixed generous span around
     * [today], NOT the month the user is looking at, so paging far enough runs off the end of it. An
     * empty December three years out means "not fetched", not "nothing on". Without this the screen
     * would state that as a confident zero.
     */
    val window: ClosedRange<LocalDate>? = null,
) {
    /** Whether [events] can be trusted to be complete for [date]. */
    fun covers(date: LocalDate): Boolean = window?.contains(date) ?: true
}

/**
 * The address book a Gridlink screen is showing, when something behind it has a real one.
 *
 * Same null rule as [GridlinkCalendarContent]: null falls back to [GridlinkSampleContacts], an empty
 * [contacts] is a real answer.
 */
@Immutable
data class GridlinkContactContent(
    /**
     * The account's cards in phonebook order, already sorted by the database.
     *
     * 🔴 Every entry is guaranteed a non-blank [GridlinkContact.family]. [GridlinkContact.letter]
     * calls `family.first()`, so one surname-less card takes the whole A-Z list down with it, and a
     * real address book is full of them (79 of the 113 cards in the mailbox this was built against
     * have no email either). [GridlinkDavMapping] is what makes the guarantee.
     */
    val contacts: List<GridlinkContact>,
    /** True before the contact cache has answered once. See [GridlinkCalendarContent.loading]. */
    val loading: Boolean = false,
)
