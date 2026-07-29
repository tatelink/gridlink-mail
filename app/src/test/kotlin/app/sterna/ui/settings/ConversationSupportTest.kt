package app.sterna.ui.settings

import app.sterna.core.data.account.MailProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Conversations settings act on server-side threads, which IMAP has not (issue A6/A7): the
 * section is greyed out only when EVERY configured account is IMAP. One JMAP account keeps it live.
 */
class ConversationSupportTest {

    @Test fun `only imap accounts disables the section`() {
        assertTrue(isImapOnly(listOf(MailProtocol.IMAP)))
        assertTrue(isImapOnly(listOf(MailProtocol.IMAP, MailProtocol.IMAP)))
    }

    @Test fun `a single jmap account keeps it live`() {
        assertFalse(isImapOnly(listOf(MailProtocol.JMAP)))
        // One JMAP among IMAP accounts is enough — the settings still do something.
        assertFalse(isImapOnly(listOf(MailProtocol.IMAP, MailProtocol.JMAP)))
    }

    @Test fun `no accounts is not imap-only`() {
        // Nothing to disable before an account exists.
        assertFalse(isImapOnly(emptyList()))
    }
}
