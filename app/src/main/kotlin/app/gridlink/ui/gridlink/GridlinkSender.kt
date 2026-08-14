package app.gridlink.ui.gridlink

import android.content.Context
import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.db.ScheduledSendEntity
import app.gridlink.core.data.mail.MailRepository
import app.gridlink.send.Outbox
import app.gridlink.send.ScheduledSends
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the composer's send button actually does.
 *
 * ## Why this is an interface and not a call into [MailRepository]
 * Every other screen in this fork runs against [GridlinkSample] data, and until now so did send: the
 * button closed the composer, showed the undo ring, and the message evaporated. Making the button
 * real means the Gridlink UI has to reach the engine, and the engine needs an account, a database
 * and a WorkManager job — none of which a screenshot harness has or wants. Splitting the decision
 * out means the screens stay renderable with nothing behind them, and the thing that can genuinely
 * put mail on the wire is assembled once, by whoever knows there is an account.
 *
 * ## The two halves, and why they are separate calls
 * 🔴 [check] is synchronous and pure, [enqueue] is not. The composer has to decide **before it
 * closes** whether this send is going to happen, because a refusal that arrived a moment later
 * would have nowhere to put itself: the draft would already be gone, and the only surface left
 * would be the undo bar, which is the one control on screen that means the opposite. So refusals
 * are decided on the tap, in the composer, over the draft that caused them.
 */
interface GridlinkSender {
    /**
     * Why this send cannot happen, in a sentence to show the user, or null to go ahead.
     *
     * Pure and fast: it runs on the send tap, on the main thread, before anything closes.
     */
    fun check(request: GridlinkComposeRequest): String?

    /**
     * Hand the message to the outbox, held for the undo window.
     *
     * Returns the action that un-sends it, to be run if the user taps Undo before the ring drains.
     * Only ever called after [check] has returned null.
     */
    suspend fun enqueue(request: GridlinkComposeRequest): suspend () -> Unit

    /**
     * Keep the message as a draft, because the composer closed without sending it.
     *
     * Called with whatever [GridlinkComposeScreen]'s close handed back: an edited draft to save, or
     * an emptied one whose server copy (if any) should go away. NOT gated by [check] — a draft with
     * no recipients, sample recipients, or attachments is a fine draft even though it would be a
     * refused send. Default is to do nothing, which is the correct behaviour for a build with no
     * server behind it: the gallery's composer closes and the text simply ceases, as it always has.
     */
    suspend fun keep(request: GridlinkComposeRequest) {}

    /**
     * Queue the message to be sent at [sendAtMillis] (epoch millis), not now.
     *
     * Only ever called after [check] has returned null: a scheduled send is still a send, and every
     * refusal that applies on the tap applies at four tomorrow morning, when nobody will be looking
     * at the error. Default is a no-op for engine-less builds, same reasoning as [keep].
     */
    suspend fun schedule(request: GridlinkComposeRequest, sendAtMillis: Long) {}
}

/**
 * The real one: Gridlink's persistent outbox.
 *
 * ## Why the undo ring stops being theatre here
 * [MailRepository.enqueueSend] already has exactly the mechanism §6c's ring was drawing. With
 * `holdMs` set, the row is inserted HELD, its delivery worker is armed for that far in the future,
 * and only then does it become QUEUED and go out. So the ten seconds the ring counts are the ten
 * seconds the message is genuinely still recallable, and undo is a row delete rather than a promise.
 * Nothing here re-implements sending, retry, or offline queueing: all three already work, and a
 * second copy of them living in the UI layer is how they drift apart.
 */
class GridlinkOutboxSender(
    private val context: Context,
    private val repository: MailRepository,
    private val accounts: AccountStore,
    /**
     * Where a draft's attachments come from, or null in a build that cannot attach.
     *
     * 🔴 The SAME instance the composer stages into. It holds the parts behind the chips, so a
     * second one here would find every id unknown and refuse every attached send — the bug this
     * whole change removes, wearing a different hat.
     */
    private val attacher: GridlinkAttacher? = null,
) : GridlinkSender {

    override fun check(request: GridlinkComposeRequest): String? {
        val draft = request.draft
        if (accounts.load() == null) {
            // ⚠️ Reached from the debug GALLERY, which is its own launcher icon beside the app and
            // shares its account store. The app itself cannot get here: it routes to
            // [GridlinkSetupHost] the moment no account exists, so its composer is unreachable
            // without one. Naming the app rather than "the Gridlink icon" is the point — since the
            // rename BOTH icons say Gridlink, and the old wording pointed at whichever one the
            // reader happened to be looking at.
            return "No account yet. Set one up in the Gridlink app first, then send from here."
        }
        if (draft.recipients.isEmpty()) return "Add someone to send this to."

        // 🔴 The sample address book is invented people at REAL company domains
        // (dalton-energy.example, sanivex.example, tallyman.example, riverbendwater.example). It exists to make the
        // screens legible, and it was never addressable. With a live outbox behind the button,
        // tapping send on the demo reply would put mail from Tate's own server into a stranger's
        // inbox at a real company, or at best generate a bounce he has to explain. So the demo data
        // is refused by name rather than filtered silently: a recipient that vanished on send would
        // be a worse bug than the one this prevents.
        val sample = draft.recipients.filter { GridlinkSampleContacts.isSample(it) }
        if (sample.isNotEmpty()) {
            val who = sample.joinToString(", ") { it.displayName }
            return "$who ${if (sample.size == 1) "is" else "are"} sample data, not a real address. " +
                "Type an address to send for real."
        }

        // Attachments go out for real now, but only the ones this app staged. The demo reply's is a
        // name and a size with no bytes anywhere behind it, and #70's rule is that a send never goes
        // out short of what was attached — so a chip with no file is a refusal, by name, rather than
        // a message that quietly leaves it off.
        val unstaged = draft.attachments.filter { attacher == null || !attacher.holds(it) }
        if (unstaged.isNotEmpty()) {
            val what = unstaged.joinToString(", ") { it.name }
            return "$what ${if (unstaged.size == 1) "has" else "have"} no file behind " +
                "${if (unstaged.size == 1) "it" else "them"}. Remove and attach again to send."
        }
        return null
    }

    override suspend fun enqueue(request: GridlinkComposeRequest): suspend () -> Unit {
        val draft = request.draft
        // Re-read rather than closing over what [check] saw. The two calls are a user-visible moment
        // apart, and an account removed in between must fail loudly here rather than send as whoever
        // happens to be current now.
        val credentials = accounts.load() ?: error("No account to send from.")
        val formatted = draft.formattedBody()
        val id = repository.enqueueSend(
            credentials = credentials,
            to = draft.recipients.map { it.email },
            subject = draft.subject,
            // 🔴 The quote is appended HERE, at the write, and not folded into the body when the
            // composer opens. The body the user edits stays the words they typed, which is what lets
            // the block on screen be un-editable furniture, and it is what keeps "did they touch
            // anything" (see [GridlinkComposeState.closeRequest]) answerable.
            body = gridlinkQuotedPlain(formattedPlain(formatted), draft.quoted),
            // See [GridlinkComposeDraft.formattedBody]: the HTML goes out on every send, formatted
            // or not, because it is also what protects a plain message from format=flowed reflow.
            // It is also the part that carries the original's own formatting into the quote.
            htmlBody = gridlinkQuotedHtml(formattedHtml(formatted), draft.quoted),
            // The staged parts, not the chips. [check] has already refused any chip with no file
            // behind it, so this cannot silently shorten the message.
            attachments = attacher?.parts(draft.attachments).orEmpty(),
            // 🔴 The hold is the ring. If these two ever disagree the bar is lying in one direction
            // or the other: a shorter hold sends while the ring still offers to stop it, a longer
            // one leaves the message recallable after the offer is gone.
            holdMs = GRIDLINK_UNDO_WINDOW_MS,
            // Sending a message that began life as a saved draft retires the draft: the engine
            // deletes the server copy once the send is accepted, so Drafts does not keep a stale
            // twin of something that is now in Sent.
            draftEmailId = draft.draftEmailId,
        )
        // 🔴 What makes "people you've recently emailed" true. The store and the query behind it
        // existed and nothing ever wrote to it, so the recents half of the recipient suggestions was
        // permanently empty and the Settings switch that promises it ("When off, it still suggests
        // people you've recently emailed") was promising an empty list.
        //
        // ⚠️ Recorded on ENQUEUE rather than after the hold expires. The undo window is about the
        // message, not about the address: someone recalled and retyped is still someone this user
        // was writing to, and the alternative is that the address they most recently used is the one
        // the field is slowest to learn.
        runCatching { repository.rememberRecipients(draft.recipients.map { it.email }) }
        return {
            // Row first, worker second, matching the order the stock composer uses: the row is the
            // user-visible artifact, and if the cancel turns out to be a no-op a later worker run
            // finds no row and exits.
            repository.deleteOutbox(id)
            Outbox.cancel(context, id)
        }
    }

    override suspend fun keep(request: GridlinkComposeRequest) {
        val draft = request.draft
        val credentials = accounts.load() ?: error("No account to keep a draft on.")
        val emptied = draft.recipients.isEmpty() && draft.subject.isBlank() && draft.body.isBlank() &&
            draft.attachments.isEmpty()
        if (emptied) {
            // The composer only reports an empty draft when there IS a server copy to retire
            // (an emptied fresh compose reports null and never reaches here): the user opened a
            // saved draft, deleted everything, and closed. That is a discard, said with the
            // backspace key instead of a button.
            draft.draftEmailId?.let { repository.discardDraft(credentials, it) }
            return
        }
        val formatted = draft.formattedBody()
        repository.saveDraft(
            credentials = credentials,
            to = draft.recipients.map { it.email },
            subject = draft.subject,
            // ⚠️ The quote is written into the SAVED draft too, and it has to be: the stored draft
            // is the whole message, and a reply saved and reopened tomorrow has no [GridlinkQuote]
            // any more (see [GridlinkComposeDraft.quoted]). Left out here, closing a reply would
            // silently drop the original, and the reopened draft would send without it.
            body = gridlinkQuotedPlain(formattedPlain(formatted), draft.quoted),
            // 🔴 The marks live in this part and nowhere else, and reopening the draft reads them
            // back out of it with [parseFormattedHtml]. Without it a draft closed with a bolded
            // word reopens plain, and the user's next act is to send it.
            htmlBody = gridlinkQuotedHtml(formattedHtml(formatted), draft.quoted),
            // A draft keeps its files, so closing the composer on an attached message and reopening
            // it from Drafts finds the same message. Chips with nothing behind them are dropped
            // rather than refused: [check] does not run on a close, and a draft is allowed to be
            // less than a sendable message — which is the whole point of a draft.
            attachments = attacher?.parts(draft.attachments.filter { attacher.holds(it) }).orEmpty(),
            // Editing a saved draft replaces it in place rather than piling up a copy per close.
            replacesEmailId = draft.draftEmailId,
        )
    }

    override suspend fun schedule(request: GridlinkComposeRequest, sendAtMillis: Long) {
        val draft = request.draft
        // 🔴 The scheduled-send table has no attachment column: its row IS the message, and a file
        // has nowhere to live in it. Upstream draws the same line ([scheduleSendAllowed]). Refused
        // here rather than in [check] because that method cannot tell a send from a schedule, and
        // an attached message sent NOW is perfectly fine — the scaffold reopens the composer with
        // this sentence, so the draft and its file survive the refusal.
        if (draft.attachments.isNotEmpty()) {
            error("Send Later can't carry a file yet. Send it now, or remove the attachment.")
        }
        val credentials = accounts.load() ?: error("No account to send from.")
        // Same shape [ComposeViewModel] writes: the entity is the message, held locally until its
        // worker fires. Nothing goes to the server until then, which is why cancelling a scheduled
        // send is a local row delete rather than a recall.
        val id = repository.insertScheduledSend(
            ScheduledSendEntity(
                accountId = credentials.id,
                recipients = draft.recipients.joinToString(",") { it.email },
                subject = draft.subject,
                textBody = gridlinkQuotedPlain(formattedPlain(draft.formattedBody()), draft.quoted),
                htmlBody = gridlinkQuotedHtml(formattedHtml(draft.formattedBody()), draft.quoted),
                fromName = null,
                fromEmail = null,
                inReplyTo = null,
                references = null,
                sendAtMillis = sendAtMillis,
                // So the worker retires the server draft when the send finally goes out.
                draftEmailId = draft.draftEmailId,
            )
        )
        ScheduledSends.enqueue(context, id, sendAtMillis)
    }
}

/**
 * The sender for a build with no engine behind it: refuses everything, and says so.
 *
 * ⚠️ Deliberately NOT a silent success. A no-op that showed the undo ring anyway is precisely the
 * behaviour this whole change removes — a button that performs sending convincingly and delivers
 * nothing. Screenshot frames of the undo bar come from `--es undo full|half|nearly`, which does not
 * go through send at all, so nothing in the harness needs a pretend one.
 */
object GridlinkNullSender : GridlinkSender {
    override fun check(request: GridlinkComposeRequest): String? =
        "This build can't send mail."

    override suspend fun enqueue(request: GridlinkComposeRequest): suspend () -> Unit =
        error("GridlinkNullSender never passes check().")
}

/**
 * The undo half of one in-flight send, held across the gap between the tap and the row existing.
 *
 * ## Why this is a class and not a nullable lambda
 * The undo bar has to be on screen on the send frame — that is the whole point of the pattern, the
 * composer closing and the ring appearing are one motion. [GridlinkSender.enqueue] is a database
 * write and a WorkManager schedule, so for the first instants of a ten second window there is
 * genuinely no row to delete and nothing to cancel. A plain `var cancel: (() -> Unit)?` loses an
 * undo that lands in that gap: the tap finds null, does nothing, and the write completes a moment
 * later into a state where nobody is going to stop it. So the undo is recorded as well as
 * performed, and the write checks on arrival whether it was already overtaken.
 *
 * ⚠️ Not `mutableStateOf` and not thread-safe by design: every touch happens on the main thread
 * (Compose event callbacks and a main-dispatched coroutine), and nothing on screen reads it. Making
 * it observable would recompose the scaffold for a value no pixel depends on.
 */
class GridlinkPendingSend {

    /** The un-send action, once [GridlinkSender.enqueue] has returned one. */
    var cancel: (suspend () -> Unit)? = null

    /** Whether Undo was tapped, including before there was anything to cancel. */
    var undone: Boolean = false
        private set

    /** Arm for a fresh send. Called on the send tap, before the enqueue is launched. */
    fun reset() {
        cancel = null
        undone = false
    }

    /**
     * Take the message back, now if possible and retroactively if not.
     *
     * Idempotent: [cancel] is cleared as it runs, so a second call (bar dismissed twice, expiry
     * racing a tap) cannot delete an outbox row belonging to a later send.
     */
    fun undo(scope: CoroutineScope) {
        undone = true
        val action = cancel ?: return
        cancel = null
        scope.launch { action() }
    }
}
