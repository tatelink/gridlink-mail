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
 * Build the unsubscribe mail for [mailto].
 *
 * Subject and body are the sender's own when the URI carries them (some lists key the
 * unsubscribe off the subject line, so dropping it would send a mail that does nothing);
 * otherwise [UNSUBSCRIBE_SUBJECT] and an empty body.
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
): UnsubscribeMail = UnsubscribeMail(
    to = listOf(mailto.address),
    subject = mailto.subject ?: UNSUBSCRIBE_SUBJECT,
    body = mailto.body.orEmpty(),
    fromName = identityName,
    fromEmail = identityEmail,
)
