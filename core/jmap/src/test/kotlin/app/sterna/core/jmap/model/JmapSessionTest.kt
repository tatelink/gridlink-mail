package app.sterna.core.jmap.model

import app.sterna.core.jmap.Jmap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [JmapSession.mailAccountIds] (issue #31): every mail-capable account of a login, primary
 * first, degrading to the single primary account on servers that omit accountCapabilities.
 */
class JmapSessionTest {

    private val mailCap = buildJsonObject { }

    private fun account(vararg capabilities: String) = JmapAccount(
        name = "acc",
        accountCapabilities = capabilities.associateWith { mailCap },
    )

    @Test fun listsEveryMailCapableAccountPrimaryFirst() {
        val session = JmapSession(
            apiUrl = "https://mail.example.com/jmap/api/",
            accounts = mapOf(
                "shared" to account(Jmap.MAIL_CAPABILITY),
                "own" to account(Jmap.MAIL_CAPABILITY),
                "contactsOnly" to account("urn:ietf:params:jmap:contacts"),
            ),
            primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "own"),
        )

        // The primary leads even though the map lists "shared" first; the contacts-only
        // account is not a mail account and never surfaces.
        assertEquals(listOf("own", "shared"), session.mailAccountIds())
    }

    @Test fun fallsBackToThePrimaryWhenCapabilitiesAreAbsent() {
        val session = JmapSession(
            apiUrl = "https://mail.example.com/jmap/api/",
            accounts = mapOf("only" to JmapAccount(name = "acc")),
            primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "only"),
        )

        assertEquals(listOf("only"), session.mailAccountIds())
    }

    @Test fun parsesAccountCapabilitiesFromSessionJson() {
        val json = Json { ignoreUnknownKeys = true }
        val session = json.decodeFromString(
            JmapSession.serializer(),
            """
            {
              "apiUrl": "https://mail.example.com/jmap/api/",
              "primaryAccounts": { "${Jmap.MAIL_CAPABILITY}": "a" },
              "accounts": {
                "a": { "name": "me@example.com",
                       "accountCapabilities": { "${Jmap.MAIL_CAPABILITY}": {} } },
                "b": { "name": "shared@example.com", "isPersonal": false,
                       "accountCapabilities": { "${Jmap.MAIL_CAPABILITY}": {} } }
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("a", "b"), session.mailAccountIds())
    }
}
