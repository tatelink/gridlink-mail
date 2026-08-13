package app.gridlink.ui.gridlink

/**
 * What the new-event form's Save button actually does.
 *
 * ## Why this is an interface, like [GridlinkSender]
 * Same reason: everything under `ui.gridlink` renders against [GridlinkSample] data with no account,
 * no database and no network behind it, and saving an event for real needs all three. The screens
 * stay renderable with nothing behind them, and the thing that can genuinely write to a server is
 * assembled once, by whoever knows there is an account.
 *
 * ## ⚠️ Unlike [GridlinkSender] there is no `check`
 * That split exists because the composer **closes on the send tap**, so a refusal arriving a moment
 * later would have nowhere to put itself. This form does the opposite: it stays open, disabled,
 * until the save either lands or fails, and a failure goes into the form's own hint line over the
 * event that caused it. With somewhere to report to, a second synchronous pre-check would be
 * ceremony, and a rule enforced in two places is a rule that eventually disagrees with itself.
 */
interface GridlinkCalendarWriter {

    /**
     * 🔴 Whether a successful [save] comes back through the calendar content on its own.
     *
     * This decides whether the caller ALSO keeps a local copy of the event, and getting it wrong is
     * visible immediately in one of two ways. A real writer stores the event in Room, which the
     * calendar is already observing, so the event arrives by itself: keeping a second copy shows it
     * TWICE, on the same day, an hour apart in nothing. A writer with no store behind it never
     * re-emits anything, so dropping the local copy means Save closes the form and the event is
     * simply not there.
     *
     * It is a property of the writer rather than a guess at the call site because only the writer
     * knows whether anything is listening downstream of it.
     */
    val echoesIntoContent: Boolean

    /**
     * Save the event, returning why it could not be saved, or null when it was.
     *
     * The id on [event] is empty: a writer that has somewhere to put it assigns the real identity.
     */
    suspend fun save(event: GridlinkEvent): String?

    /**
     * 🔴 Whether [update] works for THIS event, which decides whether its card SHOWS an Edit
     * button at all.
     *
     * Per event, not per writer, because editability is a property of the event: the mapping layer
     * says which events it can find again through [GridlinkEvent.handle], and a row whose stored
     * text no longer reads is not one of them. An Edit button on an event the engine cannot edit
     * would be a promise it cannot keep, and hiding it is more honest than greying it.
     *
     * ⚠️ A repeating event IS editable. It used to be refused here, because rewriting the master
     * for one day would move the whole series; the answer to that is to ASK which was meant (see
     * [GridlinkEventEditScope]), not to take editing away from the events that repeat, which on a
     * real calendar is most of them.
     */
    fun canUpdate(event: GridlinkEvent): Boolean

    /**
     * Replace [before] with [edited], returning why it could not be done, or null when it was.
     *
     * Both copies arrive because a real writer diffs them: only the fields where the two differ
     * are rewritten on the server, so an untouched form saves as a wire no-op and everything the
     * form does not model survives. [before] is the event that SEEDED the form — diffing against
     * anything else (the server's copy, a re-mapped occurrence) false-marks fields the form
     * materialized, like the end time it invents for an event that had none.
     *
     * [scope] is only consulted for a [GridlinkEvent.repeating] event; for anything else there is
     * one event and both answers mean it.
     *
     * Unreachable from the UI while [canUpdate] says no, but implemented rather than left to
     * throw: a writer that crashes on a path "that cannot happen" is one refactor away from
     * crashing in production.
     */
    suspend fun update(
        before: GridlinkEvent,
        edited: GridlinkEvent,
        scope: GridlinkEventEditScope = GridlinkEventEditScope.ALL_EVENTS,
    ): String?
}

/**
 * Which occurrences an edit to a repeating event is meant for.
 *
 * The question Google Calendar asks and this one now asks too, for the reason it exists at all:
 * both answers are reasonable, neither is safely guessable, and getting it wrong is invisible.
 * "Moved Thursday's stand-up" and "moved every stand-up" look identical on the day you did it.
 *
 * [ALL_EVENTS] is the default at every call site because it is what a one-off event means (there is
 * one occurrence and it is all of them), so a caller that has nothing to ask about does not have to
 * pretend to answer.
 */
enum class GridlinkEventEditScope {
    /** Just the day being looked at. The rest of the series keeps what it had. */
    THIS_EVENT,

    /** The whole series, including days already past. */
    ALL_EVENTS,
}

/**
 * The writer for a build with no engine behind it: the event exists until the app is closed.
 *
 * ⚠️ This is NOT the [GridlinkNullSender] situation and deliberately does not refuse. Send refused
 * because the honest alternative was a button that performed delivery convincingly and delivered
 * nothing to a person who was waiting for it. Nobody is waiting for this. The debug gallery has no
 * account and no server by design, its calendar is sample data, and an event added to it is real
 * within the only world that build has: it appears in the month grid, the day column and the agenda
 * immediately, which is exactly what a screenshot of the form needs to be able to show.
 *
 * [echoesIntoContent] is false, so the caller keeps the copy that makes that true.
 */
object GridlinkMemoryCalendarWriter : GridlinkCalendarWriter {
    override val echoesIntoContent: Boolean get() = false

    override suspend fun save(event: GridlinkEvent): String? = null

    // Editing memory is as safe as writing it: there is no server copy to diverge from, so every
    // event is editable and there is nothing to diff [before] against.
    override fun canUpdate(event: GridlinkEvent): Boolean = true

    override suspend fun update(
        before: GridlinkEvent,
        edited: GridlinkEvent,
        scope: GridlinkEventEditScope,
    ): String? = null
}
