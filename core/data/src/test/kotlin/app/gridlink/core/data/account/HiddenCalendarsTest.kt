package app.gridlink.core.data.account

import app.gridlink.core.data.calendar.ICalendarStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The per-account calendar switches survive the account store's own JSON, and a record written
 * before they existed reads back with every calendar shown.
 *
 * Stored as the HIDDEN set rather than the shown one on purpose: a calendar the server adds later is
 * not in either set, and it must appear rather than arrive switched off.
 */
class HiddenCalendarsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `the hidden set round-trips through the store's JSON`() {
        val account = StoredAccount(
            id = "a1",
            server = "https://mail.example.com",
            username = "me@example.com",
            hiddenCalendars = setOf("jmap:calendar/b", "https://dav.example.com/cal/work/"),
        )
        val back = json.decodeFromString<StoredAccount>(json.encodeToString(account))
        assertEquals(account.hiddenCalendars, back.hiddenCalendars)
    }

    @Test fun `a record from before the switches shows every calendar`() {
        val old = """{"id":"a1","server":"https://mail.example.com","username":"me@example.com"}"""
        assertTrue(json.decodeFromString<StoredAccount>(old).hiddenCalendars.isEmpty())
    }

    @Test fun `an occurrence carries the calendar its event came from`() {
        val ics = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:one",
            "DTSTART;VALUE=DATE:20260812",
            "SUMMARY:Audit",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val zone = ZoneId.of("America/New_York")
        val parsed = ICalendarStream.parse(ics, zone).single().copy(calendarUrl = "jmap:calendar/b")
        val day = LocalDate.of(2026, 8, 12)
        val occurrence = ICalendarStream.occurrences(listOf(parsed), day..day, zone).single()
        assertEquals("jmap:calendar/b", occurrence.calendarUrl)
    }
}
