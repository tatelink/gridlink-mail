package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the composer's top bar calls the message it is holding (#96).
 *
 * The rule used to live inside the `TopAppBar` composable, where no test could reach it, and it was
 * wrong in a case nobody could see from the outside: `restore=true` alone said "Edit", so a composer
 * that came back EMPTY after the app was killed still claimed to be editing a queued message. Every
 * way of opening the composer is enumerated here, once and for all.
 */
class ComposeTitleTest {
    private fun title(
        draftId: String? = null,
        mode: String? = null,
        replyTo: String? = null,
        restore: Boolean = false,
        editingOutbox: Boolean = false,
    ) = composeTitle(draftId, mode, replyTo, restore, editingOutbox)

    // --- the eight ways a composer opens ---------------------------------------------------------

    @Test fun aComposerThatOpenedNothingIsANewMail() {
        // A blank compose, and equally one prefilled from a mailto: link, from "write to this
        // participant" or from a system Share: fields may be filled in, but no draft, no original
        // and no queued row was opened — so none of them names the screen.
        assertEquals(ComposeTitle.NEW, title())
    }

    @Test fun aReplyIsAReply() {
        assertEquals(ComposeTitle.REPLY, title(replyTo = "m1"))
    }

    @Test fun aReplyAllIsAReply() {
        assertEquals(ComposeTitle.REPLY, title(replyTo = "m1", mode = "replyAll"))
    }

    @Test fun aForwardIsAForward() {
        // A forward carries the same replyTo as a reply and used to read "Reply" (#96).
        assertEquals(ComposeTitle.FORWARD, title(replyTo = "m1", mode = "forward"))
    }

    @Test fun aReopenedSavedDraftIsADraft() {
        assertEquals(ComposeTitle.DRAFT, title(draftId = "d1"))
    }

    @Test fun aMessageTakenBackOutOfTheOutboxIsAnEdit() {
        assertEquals(ComposeTitle.OUTBOX_EDIT, title(restore = true, editingOutbox = true))
    }

    @Test fun anUndoneSendIsANewMailNotAnEdit() {
        // Intended, and not the same case as the one above: undoing dropped the queued row, so
        // there is no longer anything in the outbox to be editing.
        assertEquals(ComposeTitle.NEW, title(restore = true, editingOutbox = false))
    }

    // --- the reporter's scenario, in plain words -------------------------------------------------

    @Test fun aComposerThatCameBackEmptyAfterTheAppWasKilledIsNotAnEdit() {
        // Kill the app with the composer open on a restored message, then reopen it: the navigation
        // still says restore=true, but the message it referred to died with the process, so nothing
        // is parked behind this screen. The fields are empty; the title must not claim otherwise.
        val restoredDraftSurvived = false
        assertEquals(ComposeTitle.NEW, title(restore = true, editingOutbox = restoredDraftSurvived))
    }

    // --- precedence: the order the cases are tested in is itself the rule ------------------------

    @Test fun aDraftOutranksEverything() {
        assertEquals(
            ComposeTitle.DRAFT,
            title(draftId = "d1", replyTo = "m1", mode = "forward", restore = true, editingOutbox = true),
        )
    }

    @Test fun aForwardOutranksTheReplyItSharesItsArgumentWith() {
        assertEquals(ComposeTitle.FORWARD, title(replyTo = "m1", mode = "forward"))
        assertEquals(ComposeTitle.REPLY, title(replyTo = "m1", mode = null))
    }

    @Test fun aHeldOutboxRowOnlyTitlesAComposerThatWasActuallyRestored() {
        // Belt and braces: the flag alone, without the navigation argument, is not an edit.
        assertEquals(ComposeTitle.NEW, title(restore = false, editingOutbox = true))
    }
}
