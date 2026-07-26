package app.sterna.core.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two signature-placement settings must travel in a settings backup like every other
 * preference — a setting that silently fails to restore is worse than one that does not exist.
 */
class SettingsBackupSignatureTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun bothSettingsSurviveAJsonRoundTrip() {
        val backup = SettingsBackup(signatureOnReplies = true, signatureBelowQuote = true)
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(true, back.signatureOnReplies)
        assertEquals(true, back.signatureBelowQuote)
    }

    @Test fun aBackupWrittenBeforeTheyExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields.
        val old = """{"version":1,"themeMode":"DARK","unarchiveOnReply":true}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.signatureOnReplies)
        assertNull(backup.signatureBelowQuote)
        assertTrue(backup.isPlausible())
    }

    @Test fun theyAreCarriedAsFalseWhenExplicitlyOff() {
        val json0 = SettingsBackupCodec.encode(
            SettingsBackup(signatureOnReplies = false, signatureBelowQuote = false),
        )
        assertTrue("exported: $json0", json0.contains("signatureOnReplies"))
        assertTrue("exported: $json0", json0.contains("signatureBelowQuote"))
    }
}
