package app.sterna.core.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        //
        // The VALUE, not the key. `contains("replyBar")` was true of any export at all: the codec
        // writes with encodeDefaults and explicitNulls, so an unset field is exported as
        // `"replyBar": null` and the key is always there. An encoding that lost the setting on
        // every export left this assertion green — which is precisely what it was written to stop.
        val encoded = SettingsBackupCodec.encode(SettingsBackup(replyBar = false))
        assertTrue("exported: $encoded", encoded.contains("\"replyBar\": false"))
    }

    @Test fun theBarIsOnUntilSomebodyTurnsItOff() {
        // The default, run rather than described. Nothing covered it: flipping the elvis in
        // SettingsRepository.replyBar took the bar away from EVERY user who had never touched the
        // switch — the exact opposite of what the setting announces — with the whole suite green.
        assertTrue("a reader that has never seen the switch shows the bar", replyBarFrom(null))
        assertTrue(replyBarFrom(true))
        assertFalse("a reader that turned it off must not get it back", replyBarFrom(false))
    }
}
