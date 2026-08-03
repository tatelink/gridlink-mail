package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which buttons the "you are leaving without saving" dialog offers (#35, #127).
 *
 * The report, with a screenshot: an unencrypted draft, the dialog titled "Discard message?", and
 * two buttons — [Discard] [Save draft]. Neither goes back to what the user was writing. Tapping
 * outside the dialog did, all along; there was simply nothing on screen that said so, and the one
 * button that reads like a way out is the one that throws the editing away.
 *
 * So Cancel is not optional in any layout, and that is what the first test says. The encrypted
 * layout already had it; the other two did not.
 *
 * The second guarantee here is 1.4.3's, and it is a security one: an encrypted message must never
 * be offered a way to become a draft, because a draft is uploaded exactly as typed. It used to be
 * held by the SHAPE of the composable — the save branch sat inside an `if` — where nothing could
 * run it. Here it is a value, and it can be checked against every input there is.
 */
class DiscardChoicesTest {

    @Test fun everyLayoutOffersAWayBackToTheComposer() {
        listOf(true, false).forEach { mayKeepDraft ->
            assertTrue(
                "the leave dialog must always offer Cancel (mayKeepDraft = $mayKeepDraft): the " +
                    "message is intact behind it, and a dialog whose every button leaves is a " +
                    "dialog with no way back (#35, #127).",
                DiscardChoice.CANCEL in discardChoices(mayKeepDraft),
            )
        }
    }

    @Test fun theWayBackIsTheFirstButton() {
        // Leftmost, and away from the destructive one: the same order the settings screens' own
        // three-answer exit uses (Cancel · Discard · Save), so the two do not read differently.
        listOf(true, false).forEach { mayKeepDraft ->
            assertEquals(DiscardChoice.CANCEL, discardChoices(mayKeepDraft).first())
        }
    }

    @Test fun anEncryptedMessageIsNeverOfferedAWayToBecomeADraft() {
        // The 1.4.3 leak, as a value rather than as the shape of a composable: the toolbar hid
        // Save while encrypting and this dialog uploaded the same plaintext one tap later.
        assertTrue(
            "no Save draft may be offered when a draft cannot be kept",
            DiscardChoice.SAVE_DRAFT !in discardChoices(mayKeepDraft = false),
        )
    }

    @Test fun theDialogOffersASaveExactlyWhenTheToolbarDoes() {
        // Same rule as the toolbar's Save button, from the same function — the dialog must not
        // offer what the toolbar hides, nor withhold what it shows (#35).
        assertTrue(DiscardChoice.SAVE_DRAFT !in discardChoices(draftSaveAllowed(PgpMode.ENCRYPT)))
        assertTrue(DiscardChoice.SAVE_DRAFT in discardChoices(draftSaveAllowed(PgpMode.SIGN)))
        assertTrue(DiscardChoice.SAVE_DRAFT in discardChoices(draftSaveAllowed(PgpMode.OFF)))
    }

    @Test fun leavingIsAlwaysOnOffer() {
        // The dialog exists to let the user leave; a layout without Discard would be a trap.
        listOf(true, false).forEach { mayKeepDraft ->
            assertTrue(DiscardChoice.DISCARD in discardChoices(mayKeepDraft))
        }
    }

    @Test fun theConfirmingAnswerComesLast() {
        assertEquals(
            listOf(DiscardChoice.CANCEL, DiscardChoice.DISCARD, DiscardChoice.SAVE_DRAFT),
            discardChoices(mayKeepDraft = true),
        )
        assertEquals(
            listOf(DiscardChoice.CANCEL, DiscardChoice.DISCARD),
            discardChoices(mayKeepDraft = false),
        )
    }

    @Test fun noButtonIsOfferedTwice() {
        listOf(true, false).forEach { mayKeepDraft ->
            val choices = discardChoices(mayKeepDraft)
            assertEquals(choices.size, choices.distinct().size)
        }
    }
}
