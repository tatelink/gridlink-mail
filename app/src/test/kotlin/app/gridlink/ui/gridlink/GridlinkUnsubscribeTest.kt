package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a `List-Unsubscribe` header is allowed to make this app do.
 *
 * 🔴 This is a parser for text written by whoever sent the mail, and the thing it drives is the one
 * request this app makes to a stranger. So the cases below are mostly about REFUSAL: the header that
 * gets an unsubscribe wrong is not the pretty one, it is the truncated one, the `http:` one, and the
 * one whose `+` was quietly turned into a space. Each of those is a real bug with a real consequence,
 * and each has a test here saying which.
 *
 * The parse is pure and lives in `ui.gridlink`, so this needs no Android, no network and no account.
 */
class GridlinkUnsubscribeTest {

    private fun message(id: String = "m1", sender: String = "Duke Energy", domain: String = "duke-energy.com") =
        GridlinkMessage(
            id = id,
            sender = sender,
            domain = domain,
            subject = "Your bill is ready",
            timestamp = "9:00 AM",
        )

    // ---- what counts as a method -------------------------------------------------------------

    @Test
    fun `mail with no header offers nothing`() {
        assertNull(gridlinkUnsubscribeOf(null))
        assertNull(gridlinkUnsubscribeOf(""))
        assertNull(gridlinkUnsubscribeOf("   "))
    }

    @Test
    fun `a mailto on its own is a method`() {
        val method = gridlinkUnsubscribeOf("<mailto:unsub@example.org>")
        assertEquals("mailto:unsub@example.org", method?.mailto)
        assertNull(method?.httpUrl)
        assertFalse(method!!.oneClick)
    }

    @Test
    fun `an https url on its own is a method`() {
        val method = gridlinkUnsubscribeOf("<https://example.org/u/9f2>")
        assertEquals("https://example.org/u/9f2", method?.httpUrl)
        assertNull(method?.mailto)
    }

    @Test
    fun `both kinds survive together and the sender's first of each wins`() {
        val method = gridlinkUnsubscribeOf(
            "<https://a.example.org/first>, <mailto:first@example.org>, " +
                "<https://b.example.org/second>, <mailto:second@example.org>",
        )
        assertEquals("https://a.example.org/first", method?.httpUrl)
        assertEquals("mailto:first@example.org", method?.mailto)
    }

    // ---- what is refused ---------------------------------------------------------------------

    @Test
    fun `plain http is not offered at all`() {
        // The token in the URL IS the address. Sending it in clear hands the mailbox to the path,
        // which is the exact thing being unsubscribed from.
        assertNull(gridlinkUnsubscribeOf("<http://example.org/u/9f2>"))
    }

    @Test
    fun `plain http falls back to the sender's mailto rather than to nothing`() {
        val method = gridlinkUnsubscribeOf("<http://example.org/u/9f2>, <mailto:unsub@example.org>")
        assertNull(method?.httpUrl)
        assertEquals("mailto:unsub@example.org", method?.mailto)
    }

    @Test
    fun `a scheme this app does not understand is not a method`() {
        assertNull(gridlinkUnsubscribeOf("<ftp://example.org/unsub>"))
        assertNull(gridlinkUnsubscribeOf("<javascript:alert(1)>"))
        assertNull(gridlinkUnsubscribeOf("<httpsx://example.org/u>"))
    }

    @Test
    fun `a bare uri with no brackets is not a header`() {
        // Guessing at the boundaries of an unbracketed address risks posting to a fragment of one.
        assertNull(gridlinkUnsubscribeOf("https://example.org/u/9f2"))
    }

    @Test
    fun `an unterminated bracket keeps what came before it and drops the rest`() {
        val method = gridlinkUnsubscribeOf("<mailto:unsub@example.org>, <https://example.org/u/9f")
        assertEquals("mailto:unsub@example.org", method?.mailto)
        assertNull(method?.httpUrl)
    }

    // ---- the awkward shapes real senders produce ----------------------------------------------

    @Test
    fun `folding whitespace inside a long url is removed, not preserved`() {
        val method = gridlinkUnsubscribeOf("<https://example.org/u/\r\n 9f2c-4d>")
        assertEquals("https://example.org/u/9f2c-4d", method?.httpUrl)
    }

    @Test
    fun `a comma inside a url does not cut the address in half`() {
        // Why the scan is over bracket pairs and not over commas.
        val method = gridlinkUnsubscribeOf("<https://example.org/u?ids=9f2,4d3&t=1>")
        assertEquals("https://example.org/u?ids=9f2,4d3&t=1", method?.httpUrl)
    }

    @Test
    fun `an empty pair of brackets is skipped without breaking the rest`() {
        val method = gridlinkUnsubscribeOf("<>, <https://example.org/u/9f2>")
        assertEquals("https://example.org/u/9f2", method?.httpUrl)
    }

    // ---- one-click ----------------------------------------------------------------------------

    @Test
    fun `one-click needs the post header`() {
        val unpromised = gridlinkUnsubscribeOf("<https://example.org/u/9f2>")
        assertFalse(unpromised!!.oneClick)
        val promised = gridlinkUnsubscribeOf("<https://example.org/u/9f2>", "List-Unsubscribe=One-Click")
        assertTrue(promised!!.oneClick)
    }

    @Test
    fun `one-click is case-insensitive and survives surrounding whitespace`() {
        val method = gridlinkUnsubscribeOf(
            "<https://example.org/u/9f2>",
            "  list-unsubscribe=one-click \r\n",
        )
        assertTrue(method!!.oneClick)
    }

    @Test
    fun `a post header saying anything else is not a promise`() {
        // RFC 8058 defines exactly one legal value. Anything else is a sender who has not implemented
        // it, and a POST to them is a request whose meaning nobody has agreed.
        listOf(
            "List-Unsubscribe=One-Click, Other=Thing",
            "One-Click",
            "List-Unsubscribe",
            "",
        ).forEach { post ->
            val method = gridlinkUnsubscribeOf("<https://example.org/u/9f2>", post)
            assertFalse("post header \"$post\" should not promise one-click", method!!.oneClick)
        }
    }

    @Test
    fun `one-click without an https url to post to is not one-click`() {
        // 🔴 The button would be drawn and would do nothing. See GridlinkUnsubscribe.oneClick.
        val method = gridlinkUnsubscribeOf("<mailto:unsub@example.org>", "List-Unsubscribe=One-Click")
        assertFalse(method!!.oneClick)
        val overHttp = gridlinkUnsubscribeOf(
            "<http://example.org/u>, <mailto:unsub@example.org>",
            "List-Unsubscribe=One-Click",
        )
        assertFalse(overHttp!!.oneClick)
    }

    // ---- the draft the mailto method opens -----------------------------------------------------

    @Test
    fun `the draft goes to the address and carries the sender's subject and body`() {
        val request = gridlinkUnsubscribeDraft(
            message(),
            "mailto:unsub@example.org?subject=Unsubscribe%20me&body=list%20id%209f2",
        )
        val draft = request.draft
        assertEquals("unsub@example.org", draft.recipients.single().email)
        assertEquals("Unsubscribe me", draft.subject)
        assertEquals("list id 9f2", draft.body)
        // Nothing to check in the To field and everything already filled in.
        assertEquals(GridlinkComposeField.BODY, request.focus)
    }

    @Test
    fun `a plus in the address stays a plus`() {
        // 🔴 The bug this guards: URLDecoder implements form-urlencoding, where + means space, and a
        // mailto is not that. Plus-addressing is exactly how a sender encodes WHICH subscription is
        // being cancelled, so decoding it to a space sends the mail to an address that does not exist.
        val request = gridlinkUnsubscribeDraft(message(), "mailto:list+unsub-9f2@example.org")
        assertEquals("list+unsub-9f2@example.org", request.draft.recipients.single().email)
    }

    @Test
    fun `a plus in the subject stays a plus too`() {
        val request = gridlinkUnsubscribeDraft(
            message(),
            "mailto:unsub@example.org?subject=unsub+9f2",
        )
        assertEquals("unsub+9f2", request.draft.subject)
    }

    @Test
    fun `only the first of several addresses is written to`() {
        // RFC 6068 allows a comma-separated list. The rest of a list operator's addresses are not
        // this app's to copy in.
        val request = gridlinkUnsubscribeDraft(
            message(),
            "mailto:unsub@example.org,archive@example.org?subject=Stop",
        )
        assertEquals("unsub@example.org", request.draft.recipients.single().email)
        assertEquals("Stop", request.draft.subject)
    }

    @Test
    fun `a mailto with no query leaves the subject and body empty`() {
        val draft = gridlinkUnsubscribeDraft(message(), "mailto:unsub@example.org").draft
        assertEquals("", draft.subject)
        assertEquals("", draft.body)
        assertTrue(draft.attachments.isEmpty())
        assertNull(draft.quoted)
    }

    @Test
    fun `a malformed percent escape is carried through rather than dropped`() {
        // The reader can see what they are about to send either way, and the raw text is closer to
        // the sender's intent than an empty field.
        val draft = gridlinkUnsubscribeDraft(message(), "mailto:unsub@example.org?subject=100%%20off").draft
        assertTrue("kept something: ${draft.subject}", draft.subject.isNotEmpty())
    }

    @Test
    fun `the recipient chip is named for the sender and keyed to the message`() {
        val draft = gridlinkUnsubscribeDraft(message(id = "abc"), "mailto:unsub@example.org").draft
        val contact = draft.recipients.single()
        assertEquals("unsubscribe-abc", contact.id)
        assertEquals("Duke Energy", contact.family)
        assertEquals("duke-energy.com", contact.role)
    }

    // ---- the sentence under the dialog title ---------------------------------------------------

    @Test
    fun `each method gets its own warning, and only two of the three say something is sent`() {
        val oneClick = gridlinkUnsubscribeWarning(
            message().copy(unsubscribe = GridlinkUnsubscribe(httpUrl = "https://x", oneClick = true)),
        )
        val page = gridlinkUnsubscribeWarning(
            message().copy(unsubscribe = GridlinkUnsubscribe(httpUrl = "https://x")),
        )
        val draft = gridlinkUnsubscribeWarning(
            message().copy(unsubscribe = GridlinkUnsubscribe(mailto = "mailto:x@y")),
        )
        assertTrue(oneClick.startsWith("Sends a request"))
        assertTrue(page.startsWith("Opens"))
        // 🔴 The one that used to be a lie: nothing is sent until the reader presses send.
        assertTrue(draft.contains("Nothing is sent until you do"))
        // All three name the sender's domain, which is the fact the decision is made on.
        listOf(oneClick, page, draft).forEach { assertTrue(it, it.contains("duke-energy.com")) }
    }

    @Test
    fun `a message with no method still gets a sentence rather than a blank dialog`() {
        assertEquals("Files this message.", gridlinkUnsubscribeWarning(message()))
    }
}
