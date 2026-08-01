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
 * Build the unsubscribe mail for [mailto].
 *
 * Subject and body are the sender's own when the URI carries them (some lists key the
 * unsubscribe off the subject line, so dropping it would send a mail that does nothing);
 * otherwise [defaultSubject] and an empty body.
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
    defaultSubject: String,
): UnsubscribeMail = UnsubscribeMail(
    to = listOf(mailto.address),
    subject = mailto.subject ?: defaultSubject,
    body = mailto.body.orEmpty(),
    fromName = identityName,
    fromEmail = identityEmail,
)
