package app.sterna.core.data.mail

/**
 * The outbox row a `mailto:` unsubscribe becomes: exactly the arguments
 * [MailRepository.enqueueSend] is handed, and nothing else.
 *
 * A data class rather than five positional parameters at the call site so the mapping is
 * *executed* by its test — the rule being pinned is not "a mail is sent" but "this recipient,
 * this subject, under this identity".
 */
internal data class UnsubscribeMail(
    val to: List<String>,
    val subject: String,
    val body: String,
    val fromName: String?,
    val fromEmail: String?,
)

/**
 * The subject of an unsubscribe mail when the `mailto:` URI names none — a fixed English word,
 * NOT the reader's interface label.
 *
 * It used to be `getString(R.string.message_unsubscribe)`, which reads well until you remember
 * who opens this mail: a robot at the other end, matching on a subject line. A French account
 * sent "Se désabonner", a Russian one "Отписаться", and the same list would answer differently
 * depending on the phone's language. What the reader sees is translated; what leaves the device
 * and has to be understood by a stranger's software is not.
 */
internal const val UNSUBSCRIBE_SUBJECT = "Unsubscribe"

/**
 * The text an unsubscribe mail will carry: the [subject] on the line and the [body] in the mail,
 * exactly as they will leave the device.
 *
 * It exists so the confirmation dialog can show them. Not a second wording of the same idea — the
 * very strings [unsubscribeMail] puts in the outbox row, because a dialog that names one text
 * while another leaves is worse than a dialog that names nothing.
 */
data class UnsubscribeMailPreview(
    val subject: String,
    val body: String,
)

/**
 * The subject and body a `mailto:` unsubscribe sends — ONE executed decision, read both by
 * [unsubscribeMail] (which sends them) and by the reader's confirmation (which shows them).
 *
 * Both come from the sender of the received message, verbatim: `List-Unsubscribe:
 * <mailto:hr@example.org?subject=I%20resign&body=Effective%20today.>` is a legal header, it is
 * preferred over a page link by [UnsubscribeOptions.preferredAction], and the mail then goes out
 * under the account's own identity with a copy in Sent. Keeping them is deliberate (some lists
 * key the unsubscribe off the subject line, so dropping it sends a mail that does nothing); what
 * was missing is that the reader was never shown what they were agreeing to send.
 *
 * The fallbacks are part of the promise too: with nothing in the URI this returns
 * [UNSUBSCRIBE_SUBJECT] and an empty body, which is what will be sent, so that is what is shown.
 */
fun unsubscribePreview(mailto: MailtoUnsubscribe): UnsubscribeMailPreview = UnsubscribeMailPreview(
    subject = mailto.subject ?: UNSUBSCRIBE_SUBJECT,
    body = mailto.body.orEmpty(),
)

/**
 * Build the unsubscribe mail for [mailto].
 *
 * Subject and body are [unsubscribePreview]'s — the same function the confirmation dialog reads,
 * so what the reader was shown and what leaves cannot come apart.
 *
 * [identityName]/[identityEmail] are the ACCOUNT's own identity, carried explicitly for the same
 * reason `sendCalendarReply` carries it: a delegated sub-account is submitted through its login
 * (issue #31), and without this the list would receive an unsubscribe from the login address —
 * which is not the address that is subscribed, so it would be ignored.
 */
internal fun unsubscribeMail(
    mailto: MailtoUnsubscribe,
    identityName: String?,
    identityEmail: String?,
): UnsubscribeMail {
    val preview = unsubscribePreview(mailto)
    return UnsubscribeMail(
        to = listOf(mailto.address),
        subject = preview.subject,
        body = preview.body,
        fromName = identityName,
        fromEmail = identityEmail,
    )
}
