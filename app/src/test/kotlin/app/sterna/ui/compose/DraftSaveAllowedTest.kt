package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the composer may offer to keep a draft (#35). A draft is uploaded
 * to the mail server as it stands in the composer, so an encrypted message must not have one: that
 * would hand the provider the exact plaintext the encryption exists to withhold. The reported bug
 * was a split between two exits — the toolbar hid "Save draft" while encrypting, the leave dialog
 * offered it anyway — so what is pinned here is that ONE function answers for every exit, and that
 * it answers on the mode alone (however the mode got there).
 */
class DraftSaveAllowedTest {

    // --- Encrypted: never ------------------------------------------------------------------------

    @Test fun anEncryptedMessageMayNotBeKeptAsADraft() {
        assertFalse(draftSaveAllowed(PgpMode.ENCRYPT))
    }

    @Test fun aLockClosedByHandForbidsTheDraftLikeTheAccountDefault() {
        // The lock cycles off → sign → encrypt; two taps from plain is a hand-set ENCRYPT, and it
        // must forbid the draft exactly as an encrypt-by-default account does. Same field, same
        // answer: the dialog cannot end up more permissive than the toolbar depending on how the
        // message came to be encrypted.
        val handSet = nextPgpMode(nextPgpMode(PgpMode.OFF))
        assertFalse(draftSaveAllowed(handSet))
    }

    // --- Everything else: allowed ----------------------------------------------------------------

    @Test fun aPlainMessageMayBeKeptAsADraft() {
        assertTrue(draftSaveAllowed(PgpMode.OFF))
    }

    @Test fun aSignedMessageMayBeKeptAsADraft() {
        // SIGN is deliberately not caught: a signed message is readable in transit and at rest by
        // design, so its draft leaks nothing the send would not. Catching it here would be a
        // regression, not extra safety.
        assertTrue(draftSaveAllowed(PgpMode.SIGN))
    }

    @Test fun unlockingAnEncryptedMessageAllowsTheDraftAgain() {
        // Third tap on the lock: back to plain, and the draft is offered again — including for a
        // draft reopened for editing, whose mode is whatever the composer currently shows.
        assertTrue(draftSaveAllowed(nextPgpMode(PgpMode.ENCRYPT)))
    }

    // --- The cycle the hand-set path relies on ---------------------------------------------------

    @Test fun theLockCyclesOffSignEncryptOff() {
        assertTrue(nextPgpMode(PgpMode.OFF) == PgpMode.SIGN)
        assertTrue(nextPgpMode(PgpMode.SIGN) == PgpMode.ENCRYPT)
        assertTrue(nextPgpMode(PgpMode.ENCRYPT) == PgpMode.OFF)
    }

    // --- The two rules are independent -----------------------------------------------------------

    @Test fun contentAloneDoesNotMakeAnEncryptedDraftSavable() {
        // #69 says the compose holds something worth saving; #35 says it may not be saved anyway.
        // Both must hold for a save, so a full message under a closed lock is still refused.
        assertTrue(draftHasContent("alex@example.org", "", "", "Lunch", "see you at noon", false))
        assertFalse(draftSaveAllowed(PgpMode.ENCRYPT))
    }
}
