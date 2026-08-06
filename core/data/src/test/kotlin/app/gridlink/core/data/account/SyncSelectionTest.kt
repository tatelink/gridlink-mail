package app.gridlink.core.data.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the sync selection the setup screen writes: its defaults, and that an account record
 * written before the field existed still loads.
 */
class SyncSelectionTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun defaultsToMailOnly() {
        val selection = SyncSelection()

        assertTrue("mail is the only thing this build syncs", selection.mail)
        assertFalse(selection.calendar)
        assertFalse(selection.contacts)
    }

    @Test fun nothingUnbuiltIsSelectedByDefault() {
        // Drives whether the setup screen shows its "not connected yet" caption. A default that
        // tripped it would put an admission on screen about a choice the user never made.
        assertFalse(SyncSelection().hasUnbuiltSelection)
    }

    @Test fun eitherUnbuiltTypeTripsTheAdmission() {
        assertTrue(SyncSelection(calendar = true).hasUnbuiltSelection)
        assertTrue(SyncSelection(contacts = true).hasUnbuiltSelection)
    }

    @Test fun oldSavedAccount_loadsAsMailOnly() {
        // A record serialized before this field existed carries no syncSelection key. Those accounts
        // have only ever synced mail, so mail-only is not merely a safe default here, it is what
        // they were actually doing.
        val legacyJson = """
            {"id":"acc","server":"example.test","username":"user@example.test"}
        """.trimIndent()

        val account = json.decodeFromString(StoredAccount.serializer(), legacyJson)

        assertEquals(SyncSelection(), account.syncSelection)
        assertTrue(account.syncSelection.mail)
    }

    @Test fun selectionSurvivesARoundTrip() {
        val account = StoredAccount(
            id = "acc",
            server = "example.test",
            username = "user@example.test",
            syncSelection = SyncSelection(mail = true, calendar = true, contacts = false),
        )

        val restored = json.decodeFromString(
            StoredAccount.serializer(),
            json.encodeToString(StoredAccount.serializer(), account),
        )

        assertEquals(account.syncSelection, restored.syncSelection)
    }
}
