package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StoredAccount.resolvedIdentities] (issue #31): a linked sub-account's [StoredAccount.username]
 * is its LOGIN's address (the one that authenticates), so the standalone fallback identity would
 * be a silently wrong From. The sub-account's own address comes from the session's account name
 * (Stalwart advertises the address there); a non-address name yields an empty list so
 * AccountStore.identities() can fall back to the login's identities instead.
 */
class ResolvedIdentitiesTest {

    private fun standalone() = StoredAccount(
        id = "login-uuid",
        server = "https://mail.example.org",
        username = "alex@example.org",
        accountName = "Alex",
        signature = "-- Alex",
    )

    private fun linked(accountName: String) = StoredAccount(
        id = "sub-uuid",
        server = "https://mail.example.org",
        username = "alex@example.org", // the login's address, NOT the sub-account's
        accountName = accountName,
        loginId = "login-uuid",
        jmapAccountId = "u",
    )

    @Test fun standaloneFallsBackToItsOwnUsername() {
        val identities = standalone().resolvedIdentities()

        assertEquals(1, identities.size)
        assertEquals("alex@example.org", identities[0].email)
        assertEquals("Alex", identities[0].name)
        assertEquals("-- Alex", identities[0].signature)
    }

    @Test fun linkedDerivesItsAddressFromTheSessionAccountName() {
        val identities = linked(accountName = "jordan@example.org").resolvedIdentities()

        assertEquals(1, identities.size)
        assertEquals("jordan@example.org", identities[0].email)
        // Never the login's address under the sub-account's name.
        assertTrue(identities.none { it.email == "alex@example.org" })
    }

    @Test fun linkedWithDisplayNameOnlyOffersNothingRatherThanAWrongFrom() {
        assertTrue(linked(accountName = "Jordan Lee").resolvedIdentities().isEmpty())
        assertTrue(linked(accountName = "").resolvedIdentities().isEmpty())
        // Not an address either: two @s or embedded spaces.
        assertTrue(linked(accountName = "a@b@c").resolvedIdentities().isEmpty())
        assertTrue(linked(accountName = "jordan lee@example.org").resolvedIdentities().isEmpty())
    }

    @Test fun linkedStoredIdentitiesStillWin() {
        val manual = StoredIdentity(id = "m1", name = "Jordan", email = "jordan@example.org")
        val identities = linked(accountName = "Jordan Lee").copy(identities = listOf(manual)).resolvedIdentities()

        assertEquals(listOf(manual), identities)
    }
}
