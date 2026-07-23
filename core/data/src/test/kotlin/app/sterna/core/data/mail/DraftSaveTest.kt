package app.sterna.core.data.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps re-saving a draft from destroying it (#63): the original may only be
 * destroyed by a replacement that reproduced it. Re-saving used to create an attachment-less
 * draft and then destroy the server copy that held the files compose was showing as chips —
 * irreversible loss of data the UI had just displayed.
 */
class DraftSaveTest {

    @Test fun aPlainDraftWithEveryAttachmentCarriedMayReplaceTheOriginal() {
        assertTrue(draftReplacementIsFaithful(attachmentsIn = 2, attachmentsCarried = 2, bodyIsLossy = false))
    }

    @Test fun noAttachmentsAtAllIsFaithful() {
        assertTrue(draftReplacementIsFaithful(attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = false))
    }

    @Test fun aDroppedAttachmentKeepsTheOriginal() {
        // The blocker: one part couldn't be re-attached (upload refused, staged file gone).
        // Destroying the original here deletes the only copy of that file.
        assertFalse(draftReplacementIsFaithful(attachmentsIn = 2, attachmentsCarried = 1, bodyIsLossy = false))
    }

    @Test fun everyAttachmentDroppedKeepsTheOriginal() {
        assertFalse(draftReplacementIsFaithful(attachmentsIn = 1, attachmentsCarried = 0, bodyIsLossy = false))
    }

    @Test fun aFlattenedBodyKeepsTheOriginalEvenWithNoAttachments() {
        // A draft authored elsewhere in HTML is flattened to plain text on open; saving it back
        // cannot restore the formatting, so the HTML original stays put.
        assertFalse(draftReplacementIsFaithful(attachmentsIn = 0, attachmentsCarried = 0, bodyIsLossy = true))
    }
}
