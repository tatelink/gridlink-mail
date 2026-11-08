package app.gridlink.ui.home

import app.gridlink.core.data.account.StoredAccount
import app.gridlink.core.data.account.StoredIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/** The account store, flattened into the composer's From choices. */
class GridlinkFromChoicesTest {

    private fun account(
        id: String,
        vararg identities: StoredIdentity,
        signature: String = "",
        default: String? = null,
    ) =
        StoredAccount(
            id = id,
            server = "https://$id.test",
            username = "$id@mail.test",
            signature = signature,
            identities = identities.toList(),
            defaultIdentityId = default,
        )

    private val a = account(
        "a",
        StoredIdentity("1", "Tate", "a@mail.test", signature = "Tate A"),
        StoredIdentity("2", "Sales", "sales@mail.test"),
        signature = "Account A",
        default = "2",
    )
    private val b = account("b", signature = "Account B")
    private val c = account("c")

    @Test fun `the active account comes first, then the rest in store order`() {
        val ids = gridlinkFromChoices(listOf(a, b, c), activeId = "b") { true }.map { it.accountId }
        assertEquals(listOf("b", "a", "c"), ids)
    }

    @Test fun `accounts that cannot send are left out`() {
        val ids = gridlinkFromChoices(listOf(a, b, c), activeId = "a") { it != "c" }.map { it.accountId }
        assertEquals(listOf("a", "b"), ids)
    }

    @Test fun `each account offers its identities and its chosen default`() {
        val first = gridlinkFromChoices(listOf(a), activeId = "a") { true }.single()
        assertEquals(listOf("a@mail.test", "sales@mail.test"), first.identities.map { it.email })
        assertEquals("sales@mail.test", first.defaultEmail)
    }

    @Test fun `a blank identity signature falls back to the account's, as the composer always did`() {
        val first = gridlinkFromChoices(listOf(a), activeId = "a") { true }.single()
        assertEquals(listOf("Tate A", "Account A"), first.identities.map { it.signature })
    }

    @Test fun `an account with no identities set up sends as its own address`() {
        val only = gridlinkFromChoices(listOf(b), activeId = "b") { true }.single()
        assertEquals("b@mail.test", only.identities.single().email)
        assertEquals("Account B", only.identities.single().signature)
    }
}
