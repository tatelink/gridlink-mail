package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the padlock settles on when the composer re-checks OpenPGP (#35), and — the part that was
 * missing — what it owes the user when it has to give a mode up.
 *
 * The composer re-checks on open, when a restored message puts its own mode back, and on every
 * "From" switch. Dropping a mode is sometimes right (nothing here can encrypt), but doing it in
 * silence is not: the toggle is not even drawn when OpenPGP is unavailable, so a message the user
 * had encrypted came back with no padlock and no explanation, looking like an ordinary mail.
 */
class PgpRefreshTest {
    private fun refresh(
        current: PgpMode,
        available: Boolean,
        accountConfigured: Boolean = true,
        encryptByDefault: Boolean = false,
        userSet: Boolean = false,
    ) = pgpRefresh(current, available, accountConfigured, encryptByDefault, userSet)

    @Test fun withoutOpenPgpTheModeGoesOff() {
        assertEquals(PgpMode.OFF, refresh(PgpMode.ENCRYPT, available = false).mode)
        assertEquals(PgpMode.OFF, refresh(PgpMode.SIGN, available = false).mode)
    }

    @Test fun losingAnEncryptedMessagesPadlockIsAnnounced() {
        // The provider went away (uninstalled, or it no longer answers) while a message queued as
        // encrypted waited: reopening it must say so rather than quietly show a plain mail.
        val outcome = refresh(PgpMode.ENCRYPT, available = false, accountConfigured = true)
        assertEquals(PgpMode.OFF, outcome.mode)
        assertEquals(PgpDropReason.NO_PROVIDER, outcome.dropped)
    }

    @Test fun anAccountWithoutOpenPgpSaysThatInsteadOfBlamingTheProvider() {
        // Switching "From" to an account that has no OpenPGP set up: the reason is not the same,
        // and telling the user OpenKeychain is missing when it is installed would be a lie.
        val outcome = refresh(PgpMode.SIGN, available = false, accountConfigured = false)
        assertEquals(PgpMode.OFF, outcome.mode)
        assertEquals(PgpDropReason.NOT_CONFIGURED, outcome.dropped)
    }

    @Test fun nothingIsAnnouncedWhenNothingWasLost() {
        // A plain message on an account with no OpenPGP: there was no padlock to lose, so the
        // composer stays quiet. This is the common case and it must not toast on every open.
        val outcome = refresh(PgpMode.OFF, available = false, accountConfigured = false)
        assertEquals(PgpMode.OFF, outcome.mode)
        assertEquals(PgpDropReason.NONE, outcome.dropped)
    }

    @Test fun theSecondCheckOfTheSameDropSaysNothingMore() {
        // The composer re-checks once a restored mode is back in place, so the drop can be examined
        // twice. The first check took the mode to OFF; the second finds OFF and adds nothing, which
        // is what keeps the explanation to a single message.
        val first = refresh(PgpMode.ENCRYPT, available = false)
        assertEquals(PgpDropReason.NO_PROVIDER, first.dropped)
        assertEquals(PgpDropReason.NONE, refresh(first.mode, available = false).dropped)
    }

    @Test fun anAccountThatEncryptsByDefaultOpensLocked() {
        val outcome = refresh(PgpMode.OFF, available = true, encryptByDefault = true, userSet = false)
        assertEquals(PgpMode.ENCRYPT, outcome.mode)
        assertEquals(PgpDropReason.NONE, outcome.dropped)
    }

    @Test fun aLockTheUserSetIsNeverRewritten() {
        // Hand-set, or carried by a message that was queued as signed: the mode is the user's.
        assertEquals(
            PgpMode.SIGN,
            refresh(PgpMode.SIGN, available = true, encryptByDefault = true, userSet = true).mode,
        )
        assertEquals(
            PgpMode.OFF,
            refresh(PgpMode.OFF, available = true, encryptByDefault = true, userSet = true).mode,
        )
    }

    @Test fun anAvailableProviderLeavesAnOrdinaryComposerAlone() {
        assertEquals(PgpMode.OFF, refresh(PgpMode.OFF, available = true).mode)
        assertEquals(PgpMode.ENCRYPT, refresh(PgpMode.ENCRYPT, available = true).mode)
    }
}
