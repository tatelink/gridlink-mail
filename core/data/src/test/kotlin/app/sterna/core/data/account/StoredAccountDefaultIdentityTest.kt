package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the default-identity resolver + backward-compatible deserialization (#78). */
class StoredAccountDefaultIdentityTest {

    private fun identity(id: String, email: String) =
        StoredIdentity(id = id, name = id, email = email)

    private fun account(
        identities: List<StoredIdentity>,
        defaultIdentityId: String? = null,
    ) = StoredAccount(
        id = "acc",
        server = "example.test",
        username = "user@example.test",
        identities = identities,
        defaultIdentityId = defaultIdentityId,
    )

    @Test fun default_matchesStoredId() {
        val work = identity("work", "work@example.test")
        val personal = identity("personal", "me@example.test")
        val acc = account(listOf(work, personal), defaultIdentityId = "personal")

        assertEquals(personal, acc.defaultIdentity())
    }

    @Test fun default_fallsBackToFirst_whenIdMissing() {
        // Stored default no longer exists among current identities (e.g. server refresh dropped it).
        val work = identity("work", "work@example.test")
        val personal = identity("personal", "me@example.test")
        val acc = account(listOf(work, personal), defaultIdentityId = "gone")

        assertEquals(work, acc.defaultIdentity())
    }

    @Test fun default_fallsBackToFirst_whenUnset() {
        val work = identity("work", "work@example.test")
        val personal = identity("personal", "me@example.test")
        val acc = account(listOf(work, personal), defaultIdentityId = null)

        assertEquals(work, acc.defaultIdentity())
    }

    @Test fun oldSavedAccount_loadsWithNullDefault() {
        // A record serialized before this feature existed has no defaultIdentityId key.
        val legacyJson = """
            {"id":"acc","server":"example.test","username":"user@example.test",
             "identities":[{"id":"work","name":"Work","email":"work@example.test"}]}
        """.trimIndent()
        val account = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(StoredAccount.serializer(), legacyJson)

        assertNull(account.defaultIdentityId)
        assertEquals("work", account.defaultIdentity()?.id)
    }
}
