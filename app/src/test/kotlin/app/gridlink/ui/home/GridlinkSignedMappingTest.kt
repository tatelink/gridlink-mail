package app.gridlink.ui.home

import app.gridlink.core.data.mail.SmimeStatus
import app.gridlink.core.data.mail.SmimeVerdict
import app.gridlink.ui.gridlink.GridlinkSignedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two rules the reading pane's signature row rests on, pinned here because both are decisions
 * rather than translations, and a later tidy-up would otherwise "simplify" them away.
 */
class GridlinkSignedMappingTest {

    @Test
    fun `the certificate's address is shown, not the sender's`() {
        // The mismatch case: the whole value of the row is that these two are different, so the
        // signer address has to survive the mapping intact.
        val row = gridlinkSignedOf(
            SmimeVerdict(
                status = SmimeStatus.MISMATCH,
                signerEmail = "attacker@evil.example",
                signerName = "Dana Sender",
            ),
        )
        assertEquals(GridlinkSignedState.MISMATCH, row.state)
        assertEquals("attacker@evil.example", row.signer)
    }

    @Test
    fun `an issuer is only named under a signature this device trusts`() {
        val issuer = "Example Trust CA"
        assertEquals(
            issuer,
            gridlinkSignedOf(SmimeVerdict(SmimeStatus.VALID, issuer = issuer)).issuer,
        )
        // Naming the issuer under an untrusted or mismatched signature lends it the authority of a
        // CA nothing on this phone has agreed with, which is the borrowed credibility the whole
        // status distinction exists to refuse.
        assertNull(gridlinkSignedOf(SmimeVerdict(SmimeStatus.UNTRUSTED, issuer = issuer)).issuer)
        assertNull(gridlinkSignedOf(SmimeVerdict(SmimeStatus.MISMATCH, issuer = issuer)).issuer)
    }
}
