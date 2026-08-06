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

    @Test fun defaultsToTheWholeAccount() {
        val selection = SyncSelection()

        assertTrue(selection.mail)
        assertTrue("the calendar that came with the account is part of the account", selection.calendar)
        assertTrue(selection.contacts)
    }

    @Test fun mailOnlyIsStillExpressible() {
        // The point of the toggles. Someone who wants mail and nothing else must be able to say so
        // and have it stick, or the setup screen is asking a question it does not honour.
        val selection = SyncSelection(calendar = false, contacts = false)

        assertTrue(selection.mail)
        assertFalse(selection.calendar)
        assertFalse(selection.contacts)
    }

    @Test fun oldSavedAccount_picksUpCalendarAndContacts() {
        // A record serialized before this field existed carries no syncSelection key, so it lands on
        // the defaults. 🔴 That is deliberate rather than accidental: those accounts were created by
        // a build with no DAV client in it, where the toggles were documented as storing a preference
        // and fetching nothing. A `false` from that era records what the app could do, not a choice
        // the user made, so it must not permanently opt them out.
        val legacyJson = """
            {"id":"acc","server":"example.test","username":"user@example.test"}
        """.trimIndent()

        val account = json.decodeFromString(StoredAccount.serializer(), legacyJson)

        assertEquals(SyncSelection(), account.syncSelection)
        assertTrue(account.syncSelection.calendar)
        assertTrue(account.syncSelection.contacts)
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
