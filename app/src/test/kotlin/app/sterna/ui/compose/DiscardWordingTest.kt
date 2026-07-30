package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the "you are leaving without saving" dialog is allowed to claim (#70/#35).
 *
 * The defect it exists to close: a message reopened from the Outbox keeps its row in the queue, so
 * leaving hands it back and it goes out — while the dialog said "Discard message?" and offered a
 * "Discard" button. The user believed they had destroyed a mail that was in fact about to be sent.
 */
class DiscardWordingTest {
    @Test fun anOrdinaryComposerSaysTheOrdinaryThing() {
        assertEquals(DiscardWording.PLAIN, discardWording(editingOutbox = false, mayKeepDraft = true))
    }

    @Test fun anEncryptedMessageExplainsWhyItCannotBecomeADraft() {
        assertEquals(DiscardWording.ENCRYPTED, discardWording(editingOutbox = false, mayKeepDraft = false))
    }

    @Test fun aMessageTakenBackOutOfTheOutboxIsNeverAnnouncedAsDestroyed() {
        // The reporter's scenario: reopen a queued message, change something, tap the close button.
        // Only the changes go; the message returns to the queue and is sent.
        assertEquals(DiscardWording.OUTBOX, discardWording(editingOutbox = true, mayKeepDraft = true))
    }

    @Test fun whereTheMessageGoesOutranksWhyItCannotBeADraft() {
        // An ENCRYPTED message reopened from the outbox: the encrypted wording ends on "Leaving now
        // discards the message", which is false here — the row is still queued. The outbox wording
        // wins, and it is the one that says what actually happens to the mail.
        assertEquals(DiscardWording.OUTBOX, discardWording(editingOutbox = true, mayKeepDraft = false))
    }

    @Test fun theDialogAndTheToolbarAgreeOnWhatMayBecomeADraft() {
        // The wording of "you can't save this" must follow the same rule as the hidden Save button,
        // or the dialog would explain an absence the toolbar does not have (#35).
        assertEquals(
            DiscardWording.ENCRYPTED,
            discardWording(editingOutbox = false, mayKeepDraft = draftSaveAllowed(PgpMode.ENCRYPT)),
        )
        assertEquals(
            DiscardWording.PLAIN,
            discardWording(editingOutbox = false, mayKeepDraft = draftSaveAllowed(PgpMode.SIGN)),
        )
        assertEquals(
            DiscardWording.PLAIN,
            discardWording(editingOutbox = false, mayKeepDraft = draftSaveAllowed(PgpMode.OFF)),
        )
    }
}
