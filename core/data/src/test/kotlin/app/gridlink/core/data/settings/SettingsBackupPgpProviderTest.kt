package app.gridlink.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chosen OpenPGP app is the ONE preference that must NOT travel in a settings backup, so this
 * file locks an absence where its neighbours lock a presence.
 *
 * A package name is a fact about one device. Restored onto a phone that does not have that app,
 * it names a provider Gridlink cannot bind — and the screen would then show a choice the user never
 * made, for an app they do not have. The honest answer on the new device is to let discovery
 * decide again, which is what `null` (no choice expressed) means (#151).
 *
 * The check is on the ENCODED export, not on the class: the codec writes with `encodeDefaults`, so
 * every field of [SettingsBackup] appears as a key in every export. A field added to the backup
 * therefore shows up here whether or not `exportSettings` was taught to fill it — which is the
 * likelier half-mistake, and the one an assertion on values alone would miss.
 */
class SettingsBackupPgpProviderTest {

    @Test fun `an export carries no OpenPGP provider field at all`() {
        val exported = SettingsBackupCodec.encode(SettingsBackup())
        val offending = exported.lines().map { it.trim() }.filter { it.contains("pgp", true) }
        assertEquals(
            "the chosen OpenPGP app entered the settings backup. Restored on a device without " +
                "that app it names a provider that cannot be bound, and discovery no longer gets " +
                "to decide.",
            emptyList<String>(),
            offending,
        )
    }

    @Test fun `a backup file naming a provider imports without adopting it`() {
        // Not a hypothetical: a hand-edited or future-build file can carry the key. Unknown keys
        // are ignored, so it decodes — and must decode to a backup that says nothing about PGP.
        val text = """{"version":1,"themeMode":"DARK","pgpProvider":"com.pgpony.android"}"""
        val decoded = SettingsBackupCodec.decode(text)
        assertTrue("a backup with an unknown key must still import", decoded != null)
        val reEncoded = SettingsBackupCodec.encode(decoded!!)
        assertTrue(
            "the provider name survived a decode/encode round trip: it is being carried after " +
                "all. Exported: $reEncoded",
            !reEncoded.contains("com.pgpony.android"),
        )
    }
}
