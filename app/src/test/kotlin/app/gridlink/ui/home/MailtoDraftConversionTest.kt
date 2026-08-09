package app.gridlink.ui.home

import app.gridlink.MailtoDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a `mailto:` link is allowed to put in front of the user, and what it is not.
 *
 * The composer and the send path both carry ONE recipient list, so the conversion has to decide
 * where a link's `cc` and `bcc` go. `cc` merges in, which the user can see and undo. 🔴 `bcc` is
 * dropped, and this is the test that says so: folding a blind recipient into the visible list would
 * disclose an address the link deliberately hid, and it would be this app doing the disclosing. A
 * dropped recipient is a gap the user meets in the composer before anything is sent; a leaked one
 * is only discovered by the person who was supposed to be hidden.
 */
class MailtoDraftConversionTest {

    private fun mailto(
        to: String = "",
        cc: String = "",
        bcc: String = "",
        subject: String = "",
        body: String = "",
    ) = MailtoDraft(to = to, cc = cc, bcc = bcc, subject = subject, body = body).toGridlinkDraft()

    @Test
    fun `a bcc address never reaches the recipient list`() {
        val draft = mailto(to = "ada@gridlink.me", bcc = "hidden@example.com")
        assertEquals(listOf("ada@gridlink.me"), draft.recipients.map { it.email })
        assertTrue(
            "a blind recipient must not appear anywhere in the draft",
            "hidden@example.com" !in draft.recipients.joinToString { it.email + it.family } &&
                "hidden@example.com" !in draft.body &&
                "hidden@example.com" !in draft.subject &&
                "hidden@example.com" !in draft.recipientQuery,
        )
    }

    @Test
    fun `a bcc-only link produces a draft with no recipients at all`() {
        val draft = mailto(bcc = "hidden@example.com", subject = "Notes")
        assertTrue("nothing to send to is correct here", draft.recipients.isEmpty())
        assertEquals("Notes", draft.subject)
    }

    @Test
    fun `to and cc both become recipients, in that order`() {
        val draft = mailto(to = "ada@gridlink.me,  grace@gridlink.me", cc = "kay@example.com")
        assertEquals(
            listOf("ada@gridlink.me", "grace@gridlink.me", "kay@example.com"),
            draft.recipients.map { it.email },
        )
    }

    /** An address listed twice (a `cc` repeating a `to`) must not become two chips. */
    @Test
    fun `a repeated address collapses, case-insensitively`() {
        val draft = mailto(to = "ada@gridlink.me", cc = "ADA@gridlink.me")
        assertEquals(listOf("ada@gridlink.me"), draft.recipients.map { it.email })
    }

    /**
     * The composer's own address validator is deliberately loose, and it still refuses things that
     * are deliverable. Such an address has to survive the conversion: silently shrinking the draft
     * is the one outcome that is worse than showing an odd-looking chip.
     */
    @Test
    fun `an address the validator would refuse is kept anyway`() {
        val draft = mailto(to = "root@localhost")
        assertEquals(listOf("root@localhost"), draft.recipients.map { it.email })
    }

    @Test
    fun `blank and whitespace-only entries are dropped rather than becoming empty chips`() {
        val draft = mailto(to = "ada@gridlink.me, ,  ", cc = "  ")
        assertEquals(listOf("ada@gridlink.me"), draft.recipients.map { it.email })
    }

    @Test
    fun `subject and body carry across untouched`() {
        val draft = mailto(to = "ada@gridlink.me", subject = "Re: rota", body = "line one\nline two")
        assertEquals("Re: rota", draft.subject)
        assertEquals("line one\nline two", draft.body)
    }

    /**
     * 🔴 [GridlinkComposeDraft.Fresh] seeds a recipient query for the sample gallery's benefit. A
     * link-driven draft must not inherit it, or the composer opens with a half-typed search sitting
     * under chips the user did not type.
     */
    @Test
    fun `the recipient search box starts empty`() {
        assertEquals("", mailto(to = "ada@gridlink.me").recipientQuery)
    }

    /** A share arrives as subject and body with no address, and must not invent one. */
    @Test
    fun `a share with no address produces an empty recipient list and no attachments`() {
        val draft = mailto(subject = "A photo", body = "look at this")
        assertTrue(draft.recipients.isEmpty())
        assertTrue("the Gridlink composer has no attachment path", draft.attachments.isEmpty())
    }

    /** Nothing here is editing a stored draft, so nothing may claim a draft's server id. */
    @Test
    fun `the draft is new, not an edit of a stored one`() {
        assertEquals(null, mailto(to = "ada@gridlink.me").draftEmailId)
    }
}
