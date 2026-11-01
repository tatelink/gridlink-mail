package app.gridlink.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Executes the transformation that runs when the user switches OpenPGP app. Expectations are
 * written out by hand, never recomputed from the function under test.
 *
 * It is the half of the reset a JVM test can reach; the other half — that [AccountStore] actually
 * calls it, on the whole list — is pinned by [AccountStorePgpResetWiringTest].
 */
class PgpSignKeyResetTest {

    private fun account(
        id: String,
        keyId: Long,
        enabled: Boolean = true,
        encryptByDefault: Boolean = false,
    ) = StoredAccount(
        id = id,
        server = "mail.example.test",
        username = "$id@example.test",
        pgpEnabled = enabled,
        pgpSignKeyId = keyId,
        pgpEncryptByDefault = encryptByDefault,
    )

    @Test fun `EVERY account loses its key, not just the first one`() {
        // The provider is global; a per-account reset leaves the other accounts printing "0x…"
        // for a key the newly chosen app has never emitted, and their first signature fails on a
        // key the screen says is configured.
        val after = withoutPgpSignKeys(
            listOf(account("a", 0x1122334455667788L), account("b", 0x99L), account("c", 0x1L)),
        )
        assertEquals(
            "an account kept a key id from the previous provider's keyring.",
            listOf(0L, 0L, 0L),
            after.map { it.pgpSignKeyId },
        )
        assertEquals(listOf("a", "b", "c"), after.map { it.id })
    }

    @Test fun `the two PGP switches are left exactly as the user set them`() {
        // ⭐ Deliberate: with the key gone the screen reads "No key selected" beside its Choose key
        // button, which is the true state. Turning PGP off as well would undo a setting the user
        // made, and nothing would ever turn it back on (K-9 does the same with NO_OPENPGP_KEY).
        val after = withoutPgpSignKeys(
            listOf(
                account("on", 0x77L, enabled = true, encryptByDefault = true),
                account("off", 0x88L, enabled = false, encryptByDefault = false),
            ),
        )
        assertEquals(
            "switching provider silently turned OpenPGP off (or on) for an account.",
            listOf(true, false),
            after.map { it.pgpEnabled },
        )
        assertEquals(
            "switching provider silently changed encrypt-by-default for an account.",
            listOf(true, false),
            after.map { it.pgpEncryptByDefault },
        )
    }

    @Test fun `an account that never had a key comes back untouched`() {
        val before = account("fresh", 0L, enabled = false)
        assertEquals(listOf(before), withoutPgpSignKeys(listOf(before)))
    }

    @Test fun `nothing else about an account is rewritten`() {
        val before = account("a", 0x5L, enabled = true, encryptByDefault = true)
        assertEquals(
            "the reset rewrote something other than the signing key.",
            listOf(before.copy(pgpSignKeyId = 0L)),
            withoutPgpSignKeys(listOf(before)),
        )
    }

    @Test fun `an empty account list is an empty result, not a crash`() {
        assertEquals(emptyList<StoredAccount>(), withoutPgpSignKeys(emptyList()))
    }
}
