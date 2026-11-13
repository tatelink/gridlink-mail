package app.gridlink.core.jmap

import app.gridlink.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header forms the body fetch asks for must be ones every server accepts.
 *
 * RFC 8621 §4.1.2.3 allows `asText` only on headers not defined in RFC 5322 or RFC 2369, and a
 * forbidden form fails the whole `Email/get`. Cyrus (Fastmail) enforces it, so a wrong form here
 * meant no message could be opened on Fastmail at all.
 */
class HeaderFormTest {

    private val restricted = setOf(
        // RFC 5322 fields other than Subject/Comments/Keywords, which asText allows.
        "date", "from", "sender", "reply-to", "to", "cc", "bcc", "message-id", "in-reply-to",
        "references", "resent-date", "resent-from", "resent-sender", "resent-to", "resent-cc",
        "resent-bcc", "resent-message-id", "return-path", "received",
        // RFC 2369.
        "list-help", "list-unsubscribe", "list-subscribe", "list-post", "list-owner", "list-archive",
    )

    @Test
    fun `no asText form on an RFC 5322 or RFC 2369 header`() {
        val bad = JmapClient.EMAIL_BODY_PROPERTIES
            .filter { it.startsWith("header:") && it.endsWith(":asText", ignoreCase = true) }
            .filter { it.split(':')[1].lowercase() in restricted }
        assertTrue("forbidden asText forms: $bad", bad.isEmpty())
    }

    @Test
    fun `raw List-Unsubscribe decodes into the model`() {
        val json = """{"id":"e1","header:List-Unsubscribe":" <https://example.com/u>, <mailto:u@example.com>"}"""
        val email = JmapClient.DefaultJson.decodeFromString(Email.serializer(), json)
        assertEquals(" <https://example.com/u>, <mailto:u@example.com>", email.listUnsubscribe)
    }
}
