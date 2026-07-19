package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class K9SettingsImporterTest {

    private fun parse(resource: String): K9ImportResult {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("missing test fixture $resource")
        return stream.use { K9SettingsImporter.parse(it) }
    }

    @Test fun outlook_xoauth2_mapsToOAuthImapAccount() {
        val result = parse("/k9s/outlook-xoauth2.k9s")

        assertTrue(result.skipped.isEmpty())
        assertEquals(1, result.accounts.size)
        val account = result.accounts.single()

        assertEquals(MailProtocol.IMAP, account.protocol)
        assertEquals(AuthType.OAUTH, account.authType)
        assertEquals("outlook.office365.com", account.imapHost)
        assertEquals(993, account.imapPort)
        assertEquals(ConnectionSecurity.TLS, account.imapSecurity)
        assertEquals("smtp.office365.com", account.smtpHost)
        assertEquals(587, account.smtpPort)
        assertEquals(ConnectionSecurity.STARTTLS, account.smtpSecurity)
        assertEquals("demo@example.com", account.username)

        // No secrets are ever carried across from the export.
        assertEquals("", account.oauthAccessToken)

        assertEquals(1, account.identities.size)
        val identity = account.identities.single()
        assertEquals("identity-0", identity.id)
        assertEquals("Demo User", identity.name)
        assertEquals("demo@example.com", identity.email)
        // signatureUse=false → no signature carried over.
        assertEquals("", identity.signature)
    }

    @Test fun imapPlain_mapsToBasicAndKeepsSignatureWhenUsed() {
        val result = parse("/k9s/imap-plain.k9s")

        assertEquals(1, result.accounts.size)
        val account = result.accounts.single()
        assertEquals(AuthType.BASIC, account.authType)
        assertEquals(ConnectionSecurity.TLS, account.imapSecurity)
        assertEquals(ConnectionSecurity.STARTTLS, account.smtpSecurity)

        val identity = account.identities.single()
        assertEquals("Cheers,\nVictor", identity.signature)
    }

    @Test fun pop3_isSkipped() {
        val result = parse("/k9s/pop3.k9s")

        assertTrue(result.accounts.isEmpty())
        assertEquals(1, result.skipped.size)
        assertEquals(K9SkipReason.POP3, result.skipped.single().reason)
    }

    @Test fun multi_importsGoodImapAndSkipsPop() {
        val result = parse("/k9s/multi.k9s")

        assertEquals(2, result.accounts.size)
        assertEquals(1, result.skipped.size)
        assertEquals(K9SkipReason.POP3, result.skipped.single().reason)

        val twoIdentities = result.accounts.first { it.username == "three@example.com" }
        assertEquals(2, twoIdentities.identities.size)
        assertEquals("identity-0", twoIdentities.identities[0].id)
        assertEquals("identity-1", twoIdentities.identities[1].id)
    }

    @Test fun malformedMixed_importsGoodOneAndSkipsBroken() {
        val result = parse("/k9s/malformed-mixed.k9s")

        assertEquals(1, result.accounts.size)
        assertEquals("healthy@example.com", result.accounts.single().username)
        assertEquals(1, result.skipped.size)
        assertEquals(K9SkipReason.MALFORMED, result.skipped.single().reason)
    }

    @Test fun unknownVersion_isIgnored() {
        val result = parse("/k9s/unknown-version.k9s")

        assertEquals(1, result.accounts.size)
        assertEquals("future@example.com", result.accounts.single().username)
    }

    @Test fun nonK9sRoot_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            "<foo/>".byteInputStream().use { K9SettingsImporter.parse(it) }
        }
    }
}
