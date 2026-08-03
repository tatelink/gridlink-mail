package app.sterna.core.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's Reply/Forward bar switch (#63) must travel in a settings backup like every other
 * preference. The same three checks as [SettingsBackupSignatureTest], for the same reason it
 * states: a setting that silently fails to restore is worse than one that does not exist.
 */
class SettingsBackupReplyBarTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theSwitchSurvivesAJsonRoundTrip() {
        val backup = SettingsBackup(replyBar = false)
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(false, back.replyBar)
    }

    @Test fun aBackupWrittenBeforeItExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields, so importing an older
        // backup keeps the repository default (the bar is shown) instead of silently removing it.
        val old = """{"version":1,"themeMode":"DARK","signatureDelimiter":false}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.replyBar)
        assertTrue(backup.isPlausible())
    }

    @Test fun itIsCarriedAsFalseWhenExplicitlyOff() {
        // The direction that matters: the default is true, so "off" is the answer a backup has to
        // carry, and a codec that dropped false values would restore the bar the user removed.
        val encoded = SettingsBackupCodec.encode(SettingsBackup(replyBar = false))
        assertTrue("exported: $encoded", encoded.contains("replyBar"))
    }
}
