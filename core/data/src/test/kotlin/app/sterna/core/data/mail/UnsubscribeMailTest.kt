package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the `mailto:` unsubscribe actually puts in the outbox.
 *
 * The mapping is *executed*, not read: [unsubscribeMail] returns the very arguments
 * `MailRepository.sendUnsubscribeMail` hands to `enqueueSend`, so a recipient, a subject or an
 * identity that goes missing on the way fails HERE rather than on a list that never removes the
 * address.
 */
class UnsubscribeMailTest {

    private val identityName = "Alex Doe"
    private val identityEmail = "alex@masto.top"

    @Test fun `the mail goes to the address the header named`() {
        val mail = unsubscribeMail(
            MailtoUnsubscribe("leave@list.example.com"),
            identityName,
            identityEmail,
            "Unsubscribe",
        )

        assertEquals(listOf("leave@list.example.com"), mail.to)
    }

    /**
     * Some lists key the unsubscribe off the subject line, so the sender's own subject is used
     * when there is one — a mail with our default subject would be silently ignored by them.
     */
    @Test fun `the subject and body come from the uri when it carries them`() {
        val mail = unsubscribeMail(
            MailtoUnsubscribe("leave@list.example.com", subject = "unsubscribe abc", body = "token=xyz"),
            identityName,
            identityEmail,
            "Unsubscribe",
        )

        assertEquals("unsubscribe abc", mail.subject)
        assertEquals("token=xyz", mail.body)
    }

    /** The witness: with nothing in the URI, the caller's own default subject and an empty body. */
    @Test fun `a bare mailto falls back to the default subject and an empty body`() {
        val mail = unsubscribeMail(
            MailtoUnsubscribe("leave@list.example.com"),
            identityName,
            identityEmail,
            "Unsubscribe",
        )

        assertEquals("Unsubscribe", mail.subject)
        assertEquals("", mail.body)
    }

    /**
     * The account's own identity travels with the mail (issue #31): a delegated sub-account is
     * submitted through its LOGIN, so without this the list receives an unsubscribe from an
     * address that never subscribed — and correctly ignores it.
     */
    @Test fun `the account identity is carried, so a delegated sub-account unsubscribes itself`() {
        val mail = unsubscribeMail(
            MailtoUnsubscribe("leave@list.example.com"),
            identityName,
            identityEmail,
            "Unsubscribe",
        )

        assertEquals("Alex Doe", mail.fromName)
        assertEquals("alex@masto.top", mail.fromEmail)
    }

    /** No identity known: nothing is invented — the send path falls back to the account's own. */
    @Test fun `no identity leaves the from fields alone`() {
        val mail = unsubscribeMail(MailtoUnsubscribe("leave@list.example.com"), null, null, "Unsubscribe")

        assertNull(mail.fromName)
        assertNull(mail.fromEmail)
    }
}
