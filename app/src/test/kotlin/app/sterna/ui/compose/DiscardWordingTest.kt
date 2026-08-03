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
        assertEquals(DiscardWording.PLAIN, discardWording(editingOutbox = false, mayKeepDraft = true, editingDraft = false))
    }

    @Test fun anEncryptedMessageExplainsWhyItCannotBecomeADraft() {
        assertEquals(DiscardWording.ENCRYPTED, discardWording(editingOutbox = false, mayKeepDraft = false, editingDraft = false))
    }

    @Test fun aMessageNeverSavedIsTheOneThatCanReallyBeDiscarded() {
        // The title "Discard message?" is TRUE here and stays: nothing of this message exists
        // anywhere else, so leaving really does destroy it.
        assertEquals(
            DiscardWording.PLAIN,
            discardWording(editingOutbox = false, mayKeepDraft = true, editingDraft = false),
        )
    }

    @Test fun aDraftAlreadyOnTheServerIsNotDiscardedByLeaving() {
        // #35/#127: reopen a saved draft, type, tap the close button. The dialog said "Discard
        // message?" while its own body said "changes" — and leaving destroys nothing on the server
        // (cancel() → abandon(), a no-op outside the outbox). The draft is still in Drafts
        // afterwards, exactly as it was before this composer opened.
        assertEquals(
            DiscardWording.DRAFT,
            discardWording(editingOutbox = false, mayKeepDraft = true, editingDraft = true),
        )
    }

    @Test fun aDraftBeingEncryptedStillExplainsWhyItCannotBeSaved() {
        // Reached by closing the padlock by hand on a reopened draft. The encrypted wording wins:
        // it is the one that explains why the Save button is gone, which is the question in front
        // of the user. (Its last sentence, "Leaving now discards the message", is inaccurate here —
        // the SERVER copy survives. Left as is: fixing it means a sixth wording and a new string in
        // nine languages, and the case needs a deliberate padlock tap on a reopened draft.)
        assertEquals(
            DiscardWording.ENCRYPTED,
            discardWording(editingOutbox = false, mayKeepDraft = false, editingDraft = true),
        )
    }

    @Test fun aQueuedMessageOutranksADraftBehindTheSameScreen() {
        // A send queued from a reopened draft carries both. Where the MESSAGE is outranks what the
        // screen was opened from: it is in the outbox, and the outbox wording is the one that says
        // so (#70).
        assertEquals(
            DiscardWording.OUTBOX,
            discardWording(editingOutbox = true, mayKeepDraft = true, editingDraft = true),
        )
        assertEquals(
            DiscardWording.OUTBOX_ENCRYPTED,
            discardWording(editingOutbox = true, mayKeepDraft = false, editingDraft = true),
        )
    }

    @Test fun onlyAMessageThatExistsNowhereElseMayBeAnnouncedAsDestroyed() {
        // The rule stated once over the whole input space, so a later branch cannot quietly widen
        // the one title that claims destruction. PLAIN is that title; everything with a copy
        // somewhere else (a server draft, an outbox row) must say something else.
        listOf(true, false).forEach { mayKeepDraft ->
            listOf(true, false).forEach { editingOutbox ->
                listOf(true, false).forEach { editingDraft ->
                    val wording = discardWording(editingOutbox, mayKeepDraft, editingDraft)
                    val existsElsewhere = editingOutbox || editingDraft
                    assertTrue(
                        "discardWording($editingOutbox, $mayKeepDraft, $editingDraft) = $wording " +
                            "may not be PLAIN: a copy of this message exists off this screen, so " +
                            "\"Discard message?\" is false.",
                        !existsElsewhere || wording != DiscardWording.PLAIN,
                    )
                }
            }
        }
    }

    @Test fun aMessageTakenBackOutOfTheOutboxIsNeverAnnouncedAsDestroyed() {
        // The reporter's scenario: reopen a queued message, change something, tap the close button.
        // Only the changes go; the message is still in the outbox afterwards.
        assertEquals(DiscardWording.OUTBOX, discardWording(editingOutbox = true, mayKeepDraft = true, editingDraft = false))
    }

    @Test fun whereTheMessageIsOutranksWhyItCannotBeADraft() {
        // Reached by closing the padlock BY HAND on a message reopened from the outbox — an already
        // encrypted row cannot be reopened at all (OutboxLogic.canEdit refuses it, its ciphertext
        // is not in the row). The encrypted wording ends on "Leaving now discards the message",
        // which is false here: the row is still in the outbox. An outbox wording wins, because it
        // is the one that says what actually became of the mail.
        assertTrue(discardWording(editingOutbox = true, mayKeepDraft = false, editingDraft = false).fromOutbox)
    }

    @Test fun theOutboxWordingSplitsOnWhetherSaveDraftIsOnOffer() {
        // The body has to be true of BOTH buttons in front of the user, and "Save draft" is the one
        // that takes the message OUT of the outbox (it consumes the queued row). So the sentence
        // that mentions drafts is used only where that button exists; the other layout, which can
        // only leave, gets the shorter sentence and may point at the outbox's own delete.
        assertEquals(DiscardWording.OUTBOX, discardWording(editingOutbox = true, mayKeepDraft = true, editingDraft = false))
        assertEquals(
            DiscardWording.OUTBOX_ENCRYPTED,
            discardWording(editingOutbox = true, mayKeepDraft = false, editingDraft = false),
        )
    }

    @Test fun bothOutboxLayoutsShareTheirTitleAndTheirDiscardButton() {
        // Only the body differs between the two: [fromOutbox] is what the title and the button key
        // on, so they cannot drift apart as the body splits.
        assertTrue(discardWording(editingOutbox = true, mayKeepDraft = true, editingDraft = false).fromOutbox)
        assertTrue(discardWording(editingOutbox = true, mayKeepDraft = false, editingDraft = false).fromOutbox)
        assertFalse(discardWording(editingOutbox = false, mayKeepDraft = true, editingDraft = false).fromOutbox)
        assertFalse(discardWording(editingOutbox = false, mayKeepDraft = false, editingDraft = false).fromOutbox)
    }

    @Test fun theDialogAndTheToolbarAgreeOnWhatMayBecomeADraft() {
        // The wording of "you can't save this" must follow the same rule as the hidden Save button,
        // or the dialog would explain an absence the toolbar does not have (#35).
        assertEquals(
            DiscardWording.ENCRYPTED,
            discardWording(editingOutbox = false, mayKeepDraft = draftSaveAllowed(PgpMode.ENCRYPT), editingDraft = false),
        )
        assertEquals(
            DiscardWording.PLAIN,
            discardWording(editingOutbox = false, mayKeepDraft = draftSaveAllowed(PgpMode.SIGN), editingDraft = false),
        )
        assertEquals(
            DiscardWording.PLAIN,
            discardWording(editingOutbox = false, mayKeepDraft = draftSaveAllowed(PgpMode.OFF), editingDraft = false),
        )
    }
}
