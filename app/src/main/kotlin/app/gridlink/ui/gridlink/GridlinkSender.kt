package app.gridlink.ui.gridlink

import android.content.Context
import app.gridlink.R
import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.core.data.account.StoredIdentity
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

    /**
     * Who a draft goes out as, resolved against the store at the moment of the write.
     *
     * [credentials] are the account the composer's From line chose (the active account when it chose
     * nothing), and [fromName]/[fromEmail] the identity on it, ALWAYS explicit: left blank, the JMAP
     * engine falls back to the server's first identity rather than the default the user picked in
     * Settings, so a message could leave from an address the composer never showed.
     *
     * [draftEmailId] is the stored draft to replace or retire, but only when it lives on that same
     * account; [strayDraft] is the copy on another account when the user moved the message to a
     * different From, which the caller retires best-effort so Drafts does not keep a twin.
     */
    private class Origin(
        val credentials: AccountCredentials,
        val fromName: String?,
        val fromEmail: String?,
        val draftEmailId: String?,
        val strayDraft: Pair<AccountCredentials, String>?,
    )

    private fun origin(draft: GridlinkComposeDraft): Origin? {
        val wanted = draft.from
        val credentials = (if (wanted != null) accounts.credentials(wanted.accountId) else accounts.load())
            ?: return null
        val identity = gridlinkSendingIdentity(accounts.account(credentials.id), wanted?.email)
        val (kept, stray) = gridlinkDraftHome(draft.draftEmailId, draft.draftAccountId, credentials.id)
        return Origin(
            credentials = credentials,
            fromName = identity?.name?.takeIf { it.isNotBlank() },
            fromEmail = identity?.email?.takeIf { it.isNotBlank() },
            draftEmailId = kept,
            strayDraft = stray?.let { (home, id) -> accounts.credentials(home)?.let { it to id } },
        )
    }

    /** Retire a draft left behind on another account. Best effort: the message itself is what matters. */
    private suspend fun retireStray(origin: Origin) {
        val (home, id) = origin.strayDraft ?: return
        runCatching { repository.discardDraft(home, id) }
    }

    override fun check(request: GridlinkComposeRequest): String? {
        val draft = request.draft
        if (draft.from != null && accounts.credentials(draft.from.accountId) == null) {
            // Picked on the From line, then signed out before the tap. Refused rather than quietly
            // sent as the active account: the address on screen is the one the user chose.
            return context.getString(R.string.gl_thread_send_account_signed_out)
        }
        if (accounts.load() == null) {
            // ⚠️ Reached from the debug GALLERY, which is its own launcher icon beside the app and
            // shares its account store. The app itself cannot get here: it routes to
            // [GridlinkSetupHost] the moment no account exists, so its composer is unreachable
            // without one. Naming the app rather than "the Gridlink icon" is the point — since the
            // rename BOTH icons say Gridlink, and the old wording pointed at whichever one the
            // reader happened to be looking at.
            return context.getString(R.string.gl_thread_send_no_account_yet)
        }
        if (draft.recipients.isEmpty()) return context.getString(R.string.gl_thread_send_no_recipients)

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
            return context.resources.getQuantityString(R.plurals.gl_thread_send_sample_recipients, sample.size, who)
        }

        // Attachments go out for real now, but only the ones this app staged. The demo reply's is a
        // name and a size with no bytes anywhere behind it, and #70's rule is that a send never goes
        // out short of what was attached — so a chip with no file is a refusal, by name, rather than
        // a message that quietly leaves it off.
        val unstaged = draft.attachments.filter { attacher == null || !attacher.holds(it) }
        if (unstaged.isNotEmpty()) {
            val what = unstaged.joinToString(", ") { it.name }
            return context.resources.getQuantityString(
                R.plurals.gl_thread_send_unstaged_attachments,
                unstaged.size,
                what,
            )
        }
        return null
    }

    override suspend fun enqueue(request: GridlinkComposeRequest): suspend () -> Unit {
        val draft = request.draft
        // Re-read rather than closing over what [check] saw. The two calls are a user-visible moment
        // apart, and an account removed in between must fail loudly here rather than send as whoever
        // happens to be current now.
        val origin = origin(draft) ?: error(context.getString(R.string.gl_thread_send_no_account_send))
        val formatted = draft.formattedBody()
        val id = repository.enqueueSend(
            credentials = origin.credentials,
            fromName = origin.fromName,
            fromEmail = origin.fromEmail,
            to = draft.recipients.map { it.email },
            cc = draft.cc.map { it.email },
            // Threads the answer under the original on both engines (JMAP sets the Email's
            // inReplyTo/references, SMTP writes the headers), and a reply closed to Drafts and sent
            // later still threads.
            inReplyTo = draft.inReplyTo,
            references = draft.references,
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
            draftEmailId = origin.draftEmailId,
        )
        retireStray(origin)
        // 🔴 What makes "people you've recently emailed" true. The store and the query behind it
        // existed and nothing ever wrote to it, so the recents half of the recipient suggestions was
        // permanently empty and the Settings switch that promises it ("When off, it still suggests
        // people you've recently emailed") was promising an empty list.
        //
        // ⚠️ Recorded on ENQUEUE rather than after the hold expires. The undo window is about the
        // message, not about the address: someone recalled and retyped is still someone this user
        // was writing to, and the alternative is that the address they most recently used is the one
        // the field is slowest to learn.
        runCatching { repository.rememberRecipients((draft.recipients + draft.cc).map { it.email }) }
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
        val origin = origin(draft) ?: error(context.getString(R.string.gl_thread_send_no_account_draft))
        val credentials = origin.credentials
        val emptied = draft.recipients.isEmpty() && draft.cc.isEmpty() && draft.subject.isBlank() &&
            draft.body.isBlank() && draft.attachments.isEmpty()
        if (emptied) {
            // The composer only reports an empty draft when there IS a server copy to retire
            // (an emptied fresh compose reports null and never reaches here): the user opened a
            // saved draft, deleted everything, and closed. That is a discard, said with the
            // backspace key instead of a button.
            origin.draftEmailId?.let { repository.discardDraft(credentials, it) }
            retireStray(origin)
            return
        }
        val formatted = draft.formattedBody()
        repository.saveDraft(
            credentials = credentials,
            fromName = origin.fromName,
            fromEmail = origin.fromEmail,
            to = draft.recipients.map { it.email },
            cc = draft.cc.map { it.email },
            // Threads the answer under the original on both engines (JMAP sets the Email's
            // inReplyTo/references, SMTP writes the headers), and a reply closed to Drafts and sent
            // later still threads.
            inReplyTo = draft.inReplyTo,
            references = draft.references,
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
            replacesEmailId = origin.draftEmailId,
        )
        retireStray(origin)
    }

    override suspend fun schedule(request: GridlinkComposeRequest, sendAtMillis: Long) {
        val draft = request.draft
        // 🔴 The scheduled-send table has no attachment column: its row IS the message, and a file
        // has nowhere to live in it. Upstream draws the same line ([scheduleSendAllowed]). Refused
        // here rather than in [check] because that method cannot tell a send from a schedule, and
        // an attached message sent NOW is perfectly fine — the scaffold reopens the composer with
        // this sentence, so the draft and its file survive the refusal.
        if (draft.attachments.isNotEmpty()) {
            error(context.getString(R.string.gl_thread_send_later_no_attachments))
        }
        val origin = origin(draft) ?: error(context.getString(R.string.gl_thread_send_no_account_send))
        // Same shape [ComposeViewModel] writes: the entity is the message, held locally until its
        // worker fires. Nothing goes to the server until then, which is why cancelling a scheduled
        // send is a local row delete rather than a recall.
        val id = repository.insertScheduledSend(
            ScheduledSendEntity(
                accountId = origin.credentials.id,
                recipients = draft.recipients.joinToString(",") { it.email },
                cc = draft.cc.joinToString(",") { it.email }.ifBlank { null },
                subject = draft.subject,
                textBody = gridlinkQuotedPlain(formattedPlain(draft.formattedBody()), draft.quoted),
                htmlBody = gridlinkQuotedHtml(formattedHtml(draft.formattedBody()), draft.quoted),
                // The From line's choice, carried to the worker, which hands it to the engine
                // exactly as a send-now would. Null here used to mean "whatever the engine picks
                // at send time", which on JMAP is not the identity the composer showed.
                fromName = origin.fromName,
                fromEmail = origin.fromEmail,
                inReplyTo = draft.inReplyTo.joinToString(" ").ifBlank { null },
                references = draft.references.joinToString(" ").ifBlank { null },
                sendAtMillis = sendAtMillis,
                // So the worker retires the server draft when the send finally goes out.
                draftEmailId = origin.draftEmailId,
            )
        )
        retireStray(origin)
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
/**
 * The identity [account] sends as for an address the composer chose: the identity with that
 * address, else the account's default ([StoredAccount.defaultIdentity]), so a stale address
 * degrades to the default rather than to the engine's guess.
 */
internal fun gridlinkSendingIdentity(account: StoredAccount?, email: String?): StoredIdentity? {
    account ?: return null
    return account.resolvedIdentities().firstOrNull { it.email.equals(email, ignoreCase = true) }
        ?: account.defaultIdentity()
}

/**
 * Where a stored draft goes when its message is written from [sendingAccountId].
 *
 * First: the draft id to replace or retire on the sending account, or null. Second: the
 * (account id, draft id) left behind on ANOTHER account, when the From line moved the message off
 * the account the draft lives on. A draft id means nothing on a different mailbox, so it is never
 * handed to the sending account in that case.
 */
internal fun gridlinkDraftHome(
    draftEmailId: String?,
    draftAccountId: String?,
    sendingAccountId: String,
): Pair<String?, Pair<String, String>?> {
    draftEmailId ?: return null to null
    val home = draftAccountId ?: sendingAccountId
    return if (home == sendingAccountId) draftEmailId to null else null to (home to draftEmailId)
}

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
