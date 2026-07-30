package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the "you are leaving without saving" dialog is allowed to claim (#70/#35).
 *
 * The defect it exists to close: a message reopened from the Outbox keeps its row there, so leaving
 * hands it back — while the dialog said "Discard message?" and offered a "Discard" button. The user
 * believed they had destroyed a mail that was still there.
 *
 * There is deliberately no input for where that row lands. `OutboxLogic.stateAfterEdit` sends a
 * spent send back to FAILED and everything else back to QUEUED, so a single wording has to be true
 * of both — which is why it says the message stays in the outbox and stops there, rather than
 * promising it will go out.
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
        // Only the changes go; the message is still in the outbox afterwards.
        assertEquals(DiscardWording.OUTBOX, discardWording(editingOutbox = true, mayKeepDraft = true))
    }

    @Test fun whereTheMessageIsOutranksWhyItCannotBeADraft() {
        // Reached by closing the padlock BY HAND on a message reopened from the outbox — an already
        // encrypted row cannot be reopened at all (OutboxLogic.canEdit refuses it, its ciphertext
        // is not in the row). The encrypted wording ends on "Leaving now discards the message",
        // which is false here: the row is still in the outbox. An outbox wording wins, because it
        // is the one that says what actually became of the mail.
        assertTrue(discardWording(editingOutbox = true, mayKeepDraft = false).fromOutbox)
    }

    @Test fun theOutboxWordingSplitsOnWhetherSaveDraftIsOnOffer() {
        // The body has to be true of BOTH buttons in front of the user, and "Save draft" is the one
        // that takes the message OUT of the outbox (it consumes the queued row). So the sentence
        // that mentions drafts is used only where that button exists; the other layout, which can
        // only leave, gets the shorter sentence and may point at the outbox's own delete.
        assertEquals(DiscardWording.OUTBOX, discardWording(editingOutbox = true, mayKeepDraft = true))
        assertEquals(
            DiscardWording.OUTBOX_ENCRYPTED,
            discardWording(editingOutbox = true, mayKeepDraft = false),
        )
    }

    @Test fun bothOutboxLayoutsShareTheirTitleAndTheirDiscardButton() {
        // Only the body differs between the two: [fromOutbox] is what the title and the button key
        // on, so they cannot drift apart as the body splits.
        assertTrue(discardWording(editingOutbox = true, mayKeepDraft = true).fromOutbox)
        assertTrue(discardWording(editingOutbox = true, mayKeepDraft = false).fromOutbox)
        assertFalse(discardWording(editingOutbox = false, mayKeepDraft = true).fromOutbox)
        assertFalse(discardWording(editingOutbox = false, mayKeepDraft = false).fromOutbox)
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
