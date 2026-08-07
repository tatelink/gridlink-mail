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
}
