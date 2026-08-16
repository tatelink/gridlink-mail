package app.gridlink.core.data.dav

import app.gridlink.core.data.db.CalendarEventEntity
import app.gridlink.core.jmap.model.JmapCalendarEvent
import app.gridlink.core.jmap.model.JmapRecurrenceDay
import app.gridlink.core.jmap.model.JmapRecurrenceRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * JMAP events landing in the same table the CalDAV path fills, and coming back out again.
 *
 * The contract being pinned is that nothing above the cache can tell which protocol wrote a row:
 * both produce [CalendarEventEntity], both read back as `ParsedCalendarEvent`, and the only thing
 * that differs is which parser [DavMappers.toParsed] picks.
 */
class DavMappersJmapTest {

    private val eastern = ZoneId.of("America/New_York")

    private val meeting = JmapCalendarEvent(
        id = "E1",
        uid = "8c9f2b16-1e0a-4a2e-9a3d-0f2b6d5a1c77",
        title = "Standup",
        start = "2026-06-01T09:00:00",
        duration = "PT30M",
        timeZone = "America/New_York",
        sequence = 3,
        updated = "2026-06-20T01:29:17Z",
    )

    @Test fun buildsARowWithASyntheticKeyThatCannotCollideWithADavHref() {
        val row = DavMappers.jmapEvents("acct", "cal-1", meeting, eastern).single()

        assertEquals("jmap:event/E1", row.href)
        assertEquals("jmap:calendar/cal-1", row.collectionUrl)
        // A DAV href is a decoded path and always starts with '/', so the two spaces never meet.
        assertTrue(!row.href.startsWith("/"))
        assertEquals(CalendarEventEntity.FORMAT_JSCALENDAR, row.payloadFormat)
        assertEquals("8c9f2b16-1e0a-4a2e-9a3d-0f2b6d5a1c77", row.uid)
        assertEquals("2026-06-01T09:00", row.startLocal)
        assertEquals("2026-06-01T09:30", row.endLocal)
        assertEquals("America/New_York", row.zoneId)
        assertEquals(LocalDateTime.parse("2026-06-01T09:00").toLocalDate().toEpochDay(), row.startDay)
        // One day long, so there is no second day to widen the range query with.
        assertNull(row.endDay)
    }

    @Test fun theEtagStandsInForSomethingJmapDoesNotHave() {
        val row = DavMappers.jmapEvents("acct", "cal-1", meeting, eastern).single()
        assertEquals("3:2026-06-20T01:29:17Z", row.etag)

        val edited = DavMappers.jmapEvents(
            "acct",
            "cal-1",
            meeting.copy(sequence = 4, updated = "2026-06-21T08:00:00Z"),
            eastern,
        ).single()

        // 🔴 The system-calendar mirror decides what changed by fingerprinting this. A constant
        // would hide every edit from it; a value that moved on its own would rewrite the phone's
        // calendar on every sync.
        assertTrue(row.etag != edited.etag)
        assertEquals(row.etag, DavMappers.jmapEvents("acct", "cal-1", meeting, eastern).single().etag)
    }

    @Test fun aMovedInstanceGetsItsOwnRowKeyedTheSameWayAMultiVeventFileWouldBe() {
        val series = meeting.copy(
            recurrenceRule = JmapRecurrenceRule(
                frequency = "weekly",
                byDay = listOf(JmapRecurrenceDay(day = "mo")),
            ),
            recurrenceOverrides = mapOf(
                "2026-06-15T09:00:00" to JsonObject(
                    mapOf("start" to JsonPrimitive("2026-06-15T11:00:00")),
                ),
            ),
        )

        val rows = DavMappers.jmapEvents("acct", "cal-1", series, eastern)

        assertEquals(2, rows.size)
        assertEquals("jmap:event/E1", rows[0].href)
        assertEquals("jmap:event/E1#2026-06-15", rows[1].href)
        // Same uid on both, exactly as for a `.ics` holding a master and its override, which is why
        // the uid cannot be the primary key on either path.
        assertEquals(rows[0].uid, rows[1].uid)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", rows[0].rrule)
        assertNull(rows[1].rrule)
        assertEquals("2026-06-15", rows[1].recurrenceId)
        // The prefix delete that clears one file's rows works unchanged on the synthetic key.
        assertEquals("jmap:event/E1#", DavMappers.hrefPrefix(rows[0].href))
    }

    @Test fun readingBackPicksTheParserTheRowNames() {
        val row = DavMappers.jmapEvents("acct", "cal-1", meeting, eastern).single()

        val parsed = DavMappers.toParsed(row, eastern)

        assertNotNull(parsed)
        assertEquals("Standup", parsed!!.summary)
        assertEquals(LocalDateTime.parse("2026-06-01T09:00"), parsed.start)
        // The href handed back is the row's own key, since JMAP has no separate file behind it.
        assertEquals("jmap:event/E1", parsed.href)
    }

    @Test fun aJscalendarRowReadAsIcalendarWouldBeTheBugTheDiscriminatorPrevents() {
        val row = DavMappers.jmapEvents("acct", "cal-1", meeting, eastern).single()

        // Mislabel it and the JSON goes through the iCalendar reader, which finds no VEVENT. The
        // row still places an event, because the columns are the fallback, but the description and
        // everything else living only in the payload is gone. That degradation is the reason the
        // format is a stored column instead of something guessed from the text.
        val mislabelled = DavMappers.toParsed(
            row.copy(payloadFormat = CalendarEventEntity.FORMAT_ICALENDAR),
            eastern,
        )

        assertNotNull(mislabelled)
        assertEquals(LocalDateTime.parse("2026-06-01T09:00"), mislabelled!!.start)
        assertEquals("Standup", mislabelled.summary)
    }

    @Test fun anUnknownFormatFallsBackRatherThanBlankingTheCalendar() {
        val row = DavMappers.jmapEvents("acct", "cal-1", meeting, eastern)
            .single()
            .copy(payloadFormat = "some-format-from-a-newer-build")

        val parsed = DavMappers.toParsed(row, eastern)

        assertNotNull(parsed)
        assertEquals(LocalDateTime.parse("2026-06-01T09:00"), parsed!!.start)
    }
}
