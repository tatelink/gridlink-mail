package app.sterna.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sender or recipients? [showsRecipients] is that decision, and both surfaces make it here: the
 * list row (`InboxScreen`) and the reader (`MessageViewModel._ownMessage`).
 *
 * #115: a message you send to YOURSELF arrives in the Inbox, and the Inbox showed it as "To: your
 * address" — a line that reads as sent mail in the folder of received mail. The sender test was
 * true wherever the message sat, folder included.
 *
 * The fix is narrow on purpose: the author test is dropped only where the context is EXPLICITLY
 * incoming — the Inbox, and the unified view, which aggregates nothing but Inboxes. Everywhere
 * else it decides exactly as before, and two of those elsewheres are load-bearing:
 *
 *  - #69, a sent message or a draft moved to the Trash still reads "To: …". Nothing but the author
 *    test rescues it there, since the Trash role fails the folder test.
 *  - a folder with no role at all (a custom folder, or a role that has not resolved yet) keeps the
 *    author test, because nothing has established that it holds incoming mail.
 */
class ShowsRecipientsTest {

    // -- what #115 is about -------------------------------------------------------------------

    @Test fun `a message sent to yourself, in the Inbox, shows its sender`() {
        assertFalse(showsRecipients(role = "inbox", unified = false, selfAuthored = true))
    }

    @Test fun `a message sent to yourself, in the unified view, shows its sender`() {
        // The default screen, and the likeliest one in the report: with no folder selected the role
        // is null, so a rule written for "inbox" alone would leave the defect exactly where it was
        // reported. The unified view aggregates Inboxes and nothing else, so it IS incoming.
        assertFalse(showsRecipients(role = null, unified = true, selfAuthored = true))
    }

    @Test fun `a message received from somebody else shows its sender`() {
        assertFalse(showsRecipients(role = "inbox", unified = false, selfAuthored = false))
        assertFalse(showsRecipients(role = null, unified = true, selfAuthored = false))
    }

    // -- the witnesses that must not move ------------------------------------------------------

    @Test fun `a sent message moved to the Trash still shows its recipients`() {
        // Codeberg #69. The Trash is not an outgoing folder, so the author test is the only thing
        // that rescues this row; a rule that dropped it outside the Inbox would take it away.
        assertTrue(showsRecipients(role = "trash", unified = false, selfAuthored = true))
    }

    @Test fun `an archived message of your own still shows its recipients`() {
        assertTrue(showsRecipients(role = "archive", unified = false, selfAuthored = true))
    }

    @Test fun `your own message filed in a custom folder still shows its recipients`() {
        // No role: nothing has established that this folder holds incoming mail, so the author
        // test stands. Same answer for a role that has not resolved yet.
        assertTrue(showsRecipients(role = null, unified = false, selfAuthored = true))
        assertTrue(showsRecipients(role = "Invoices", unified = false, selfAuthored = true))
    }

    @Test fun `Sent and Drafts show recipients whoever wrote the message`() {
        // Codeberg #59: in those folders the author is yourself by construction, and a row whose
        // sender cannot be told still has to read right.
        assertTrue(showsRecipients(role = "sent", unified = false, selfAuthored = false))
        assertTrue(showsRecipients(role = "drafts", unified = false, selfAuthored = false))
    }

    // -- the roles as they actually arrive -----------------------------------------------------

    @Test fun `roles are matched however the server spells them`() {
        // JMAP hands the role through unnormalised (only the IMAP side lower-cases it), and the
        // neighbouring helpers in this file already trim and lower-case. A "Sent" that missed the
        // folder test used to fall through to the author test and be right by luck; in the Inbox
        // that luck now runs the other way, so the normalisation is no longer optional.
        assertTrue(showsRecipients(role = "Sent", unified = false, selfAuthored = false))
        assertTrue(showsRecipients(role = " DRAFTS ", unified = false, selfAuthored = false))
        assertFalse(showsRecipients(role = "INBOX", unified = false, selfAuthored = true))
        assertFalse(showsRecipients(role = " Inbox ", unified = false, selfAuthored = true))
    }

    @Test fun `an outgoing folder wins over the unified flag`() {
        // Defensive: the two never coincide on screen (a folder is selected or it is not), but the
        // order of the terms is the guarantee — Sent must never be read as an incoming context.
        assertTrue(showsRecipients(role = "sent", unified = true, selfAuthored = false))
    }
}
