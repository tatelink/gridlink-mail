package app.gridlink.core.data.account

import app.gridlink.core.data.dav.DavMappers
import app.gridlink.core.data.db.DavCollectionEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar choices are filed by login and calendar host, not by account record, so removing an
 * account and adding it back (a new record id) finds them again; and a write with no named calendar
 * goes to the server's default first.
 */
class CalendarPrefsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun account(id: String, server: String, username: String = "Avery@Gridlink.me", dav: String = "") =
        StoredAccount(id = id, server = server, username = username, davServer = dav)

    @Test fun `a re-added account gets the same key as the one it replaced`() {
        val before = account("old-id", "https://mail.gridlink.me/")
        val after = account("new-id", "mail.gridlink.me:443")
        assertEquals(calendarPrefsKey(before), calendarPrefsKey(after))
        assertEquals("avery@gridlink.me|mail.gridlink.me", calendarPrefsKey(after))
    }

    @Test fun `the calendar server the user typed decides the host, and another host is another key`() {
        val withDav = account("a", "https://mail.example.com", dav = "https://dav.example.com/remote.php/dav")
        assertEquals("avery@gridlink.me|dav.example.com", calendarPrefsKey(withDav))
        assertNotEquals(calendarPrefsKey(withDav), calendarPrefsKey(account("b", "https://mail.example.com")))
        // No server at all: the address's own domain.
        assertEquals("avery@gridlink.me|gridlink.me", calendarPrefsKey(account("c", "")))
    }

    @Test fun `the hidden set kept on the old account record still counts`() {
        val legacy = account("a", "mail.gridlink.me").copy(hiddenCalendars = setOf("jmap:calendar/d"))
        val all = mapOf(calendarPrefsKey(legacy) to CalendarPrefs(hidden = setOf("jmap:calendar/e")))
        assertEquals(setOf("jmap:calendar/d", "jmap:calendar/e"), calendarPrefsFor(legacy, all).hidden)
        assertTrue(calendarPrefsFor(account("x", "other.host"), all).isEmpty())
    }

    @Test fun `prefs round-trip and a blob from before a field existed still reads`() {
        val prefs = CalendarPrefs(setOf("u1"), mapOf("u1" to "Home"), mapOf("u1" to "#2A9D8F"), setOf("u2"))
        val map = mapOf("k" to prefs)
        assertEquals(map, json.decodeFromString<Map<String, CalendarPrefs>>(json.encodeToString(map)))
        val old = """{"k":{"hidden":["u1"]}}"""
        assertEquals(CalendarPrefs(hidden = setOf("u1")), json.decodeFromString<Map<String, CalendarPrefs>>(old)["k"])
    }

    private fun cal(url: String, order: Int, remoteId: String? = null) = DavCollectionEntity(
        accountId = "a",
        url = url,
        kind = DavCollectionEntity.KIND_CALENDAR,
        displayName = null,
        color = null,
        syncToken = null,
        sortOrder = order,
        remoteId = remoteId,
    )

    @Test fun `a write with no named calendar goes to the server default, then the first shown`() {
        val shared = cal("jmap:calendar/s", 0)
        val own = cal("jmap:calendar/b", 1)
        val both = listOf(shared, own)
        assertEquals(own, DavMappers.defaultCalendar(both, CalendarPrefs(serverDefaults = setOf(own.url))))
        assertEquals(shared, DavMappers.defaultCalendar(both, CalendarPrefs()))
        assertEquals(
            shared,
            DavMappers.defaultCalendar(both, CalendarPrefs(hidden = setOf(own.url), serverDefaults = setOf(own.url))),
        )
        // Everything hidden still lands somewhere real.
        assertEquals(shared, DavMappers.defaultCalendar(both, CalendarPrefs(hidden = setOf(own.url, shared.url))))
        assertNull(DavMappers.defaultCalendar(emptyList(), CalendarPrefs()))
    }

    @Test fun `a calendar is known by its JMAP id whichever local url it was given`() {
        // Synced over CalDAV first, then JMAP: the row kept its CalDAV path. Re-added fresh: a JMAP url.
        val adopted = cal("https://mail.gridlink.me/dav/cal/avery@gridlink.me/work/", 0, remoteId = "d")
        val fresh = cal("jmap:calendar/d", 0, remoteId = "d")
        assertEquals(calendarPrefKey(adopted), calendarPrefKey(fresh))
        val hiddenOnTheOld = CalendarPrefs(hidden = setOf(calendarPrefKey(adopted)))
        assertTrue(hiddenOnTheOld.isHidden(fresh))
        // A CalDAV-only calendar is known by its url, and an old url-keyed hidden set still counts.
        val dav = cal("https://dav.example.com/cal/home/", 0)
        assertEquals(dav.url, calendarPrefKey(dav))
        assertTrue(CalendarPrefs(hidden = setOf(adopted.url)).isHidden(adopted))
    }

    @Test fun `each protocol keeps its own server default`() {
        val prefs = CalendarPrefs()
            .withServerDefault("jmap:calendar/b", jmap = true)
            .withServerDefault("https://dav.example.com/cal/default/", jmap = false)
        assertEquals(setOf("jmap:calendar/b", "https://dav.example.com/cal/default/"), prefs.serverDefaults)
        // A JMAP sync that finds no default clears only the JMAP answer.
        assertEquals(
            setOf("https://dav.example.com/cal/default/"),
            prefs.withServerDefault(null, jmap = true).serverDefaults,
        )
        assertEquals(
            setOf("jmap:calendar/c", "https://dav.example.com/cal/default/"),
            prefs.withServerDefault("jmap:calendar/c", jmap = true).serverDefaults,
        )
    }
}
