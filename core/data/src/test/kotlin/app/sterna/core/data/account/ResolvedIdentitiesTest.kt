package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two-group identity model: manual [StoredAccount.identities] overriding server
 * [StoredAccount.serverIdentities], pristine server identities self-correcting, the
 * save-persists-only-manual invariant, and the empty→synthesized fallback.
 */
class ResolvedIdentitiesTest {

    private fun id(id: String, email: String, name: String = id, signature: String = "") =
        StoredIdentity(id = id, name = name, email = email, signature = signature)

    private fun account(
        manual: List<StoredIdentity> = emptyList(),
        server: List<StoredIdentity> = emptyList(),
        defaultIdentityId: String? = null,
    ) = StoredAccount(
        id = "acc",
        server = "example.test",
        username = "user@example.test",
        accountName = "User",
        signature = "legacy-sig",
        identities = manual,
        serverIdentities = server,
        defaultIdentityId = defaultIdentityId,
    )

    @Test fun manualOverridesServer_sameEmail_manualWins() {
        // Same address, differing case/whitespace: the manual entry must win (its name + signature).
        val server = id("srv", " Work@Example.test ", name = "Server Name", signature = "srv-sig")
        val manual = id("mine", "work@example.test", name = "My Name", signature = "my-sig")
        val resolved = account(manual = listOf(manual), server = listOf(server)).resolvedIdentities()

        assertEquals(1, resolved.size)
        assertEquals("mine", resolved[0].id)
        assertEquals("My Name", resolved[0].name)
        assertEquals("my-sig", resolved[0].signature)
    }

    @Test fun pristineServerIdentity_appears_thenSelfCorrects_whenServerDropsIt() {
        val serverAlias = id("srv-alias", "alias@example.test")
        val manual = id("mine", "me@example.test")

        val withAlias = account(manual = listOf(manual), server = listOf(serverAlias))
        assertTrue(withAlias.resolvedIdentities().any { it.email == "alias@example.test" })

        // Server later drops the alias; with no manual copy it must disappear (no stale freeze).
        val afterServerRefresh = withAlias.copy(serverIdentities = emptyList())
        assertFalse(afterServerRefresh.resolvedIdentities().any { it.email == "alias@example.test" })
        assertEquals(listOf("me@example.test"), afterServerRefresh.resolvedIdentities().map { it.email })
    }

    @Test fun savePersistsOnlyManual_serverAliasesNotFrozen() {
        // Simulate the editor's save: write back ONLY the manual list. Server identities must be
        // untouched, and a later server-side removal must self-correct (the fold-on-save bug fix).
        val serverAlias = id("srv-alias", "alias@example.test")
        val manual = id("mine", "me@example.test")
        val before = account(manual = listOf(manual), server = listOf(serverAlias))

        // What SettingsScreen.save() does: setIdentities(manualOnly) — never the merged list.
        val manualOnly = before.resolvedIdentities().filter { r -> r.id == "mine" }
        val afterSave = before.copy(identities = manualOnly)

        assertEquals(listOf("mine"), afterSave.identities.map { it.id })
        assertFalse(afterSave.identities.any { it.email == "alias@example.test" })
        // Alias still merges live while the server advertises it...
        assertTrue(afterSave.resolvedIdentities().any { it.email == "alias@example.test" })
        // ...and vanishes once the server drops it, since it was never frozen into identities.
        assertFalse(afterSave.copy(serverIdentities = emptyList()).resolvedIdentities()
            .any { it.email == "alias@example.test" })
    }

    @Test fun emptyBoth_fallsBackToSynthesizedDefault() {
        val resolved = account(manual = emptyList(), server = emptyList()).resolvedIdentities()

        assertEquals(1, resolved.size)
        assertEquals("default", resolved[0].id)
        assertEquals("user@example.test", resolved[0].email)
        assertEquals("User", resolved[0].name)
        assertEquals("legacy-sig", resolved[0].signature)
    }

    @Test fun resolved_manualFirst_thenServer() {
        val manual = id("mine", "me@example.test")
        val serverAlias = id("srv", "alias@example.test")
        val resolved = account(manual = listOf(manual), server = listOf(serverAlias)).resolvedIdentities()

        assertEquals(listOf("mine", "srv"), resolved.map { it.id })
    }

    @Test fun defaultIdentity_resolvesServerId_underManualFirstOrder() {
        val manual = id("mine", "me@example.test")
        val serverAlias = id("srv", "alias@example.test")
        val acc = account(manual = listOf(manual), server = listOf(serverAlias), defaultIdentityId = "srv")

        assertEquals("srv", acc.defaultIdentity()?.id)
    }

    @Test fun defaultIdentity_manualFirstIsFallback_whenUnset() {
        val manual = id("mine", "me@example.test")
        val serverAlias = id("srv", "alias@example.test")
        val acc = account(manual = listOf(manual), server = listOf(serverAlias), defaultIdentityId = null)

        assertEquals("mine", acc.defaultIdentity()?.id)
    }

    @Test fun defaultIdentity_serverOverriddenByManual_degradesToFirst() {
        // Default pointed at a server identity that a same-email manual entry now overrides:
        // the server id is deduped out, so it must degrade to the first resolved (the manual one).
        val server = id("srv", "work@example.test")
        val manual = id("mine", "work@example.test")
        val acc = account(manual = listOf(manual), server = listOf(server), defaultIdentityId = "srv")

        assertNull(acc.resolvedIdentities().firstOrNull { it.id == "srv" })
        assertEquals("mine", acc.defaultIdentity()?.id)
    }
}
