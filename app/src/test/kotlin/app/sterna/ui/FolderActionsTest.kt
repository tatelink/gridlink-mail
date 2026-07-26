package app.sterna.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which folders drop the incoming-mail-only actions (Snooze, Report spam) — Codeberg #82:
 * you do not snooze a draft, nor report your own sent message as spam.
 */
class FolderActionsTest {

    @Test fun `drafts is an outgoing folder`() {
        assertTrue(isOutgoingFolder("drafts"))
    }

    @Test fun `sent is an outgoing folder`() {
        assertTrue(isOutgoingFolder("sent"))
    }

    @Test fun `inbox keeps the actions`() {
        assertFalse(isOutgoingFolder("inbox"))
    }

    @Test fun `archive keeps the actions`() {
        assertFalse(isOutgoingFolder("archive"))
    }

    @Test fun `junk and trash keep the actions`() {
        assertFalse(isOutgoingFolder("junk"))
        assertFalse(isOutgoingFolder("trash"))
    }

    @Test fun `an unknown or custom folder keeps the actions`() {
        assertFalse(isOutgoingFolder(null))
        assertFalse(isOutgoingFolder("Invoices"))
        assertFalse(isOutgoingFolder(""))
    }

    @Test fun `role matching ignores case and stray spacing`() {
        assertTrue(isOutgoingFolder("Drafts"))
        assertTrue(isOutgoingFolder(" SENT "))
    }
}
