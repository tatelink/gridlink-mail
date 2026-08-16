package app.gridlink.core.jmap

import app.gridlink.core.jmap.model.JmapAccount
import app.gridlink.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The JMAP calendars surface (draft-ietf-jmap-calendars, carrying RFC 8984 JSCalendar).
 *
 * The response fixtures below are REAL Stalwart 0.16.15 output, captured from the live server on
 * 2026-08-16, not hand-written to match the parser. That is deliberate: a fixture written from the
 * spec proves the parser agrees with the spec, and the failures that actually bite are the places
 * a server and the spec disagree. Two of those are pinned here as behaviour, not as trivia — there
 * is no per-calendar filter, and an invented `sinceState` kills the whole request.
 */
class JmapClientCalendarsTest {
    private lateinit var server: MockWebServer
    private val client = JmapClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun calendarSession() = JmapSession(
        apiUrl = server.url("/jmap/").toString(),
        capabilities = mapOf(Jmap.CALENDARS_CAPABILITY to buildJsonObject {}),
    )

    @Test fun supportsCalendars_readsBothTheSessionAndTheAccountLevel() {
        assertTrue(client.supportsCalendars(calendarSession()))
        assertTrue(
            client.supportsCalendars(
                JmapSession(
                    apiUrl = "https://mail.example.com/jmap/",
                    accounts = mapOf(
                        "d" to JmapAccount(
                            name = "tate",
                            accountCapabilities = mapOf(Jmap.CALENDARS_CAPABILITY to buildJsonObject {}),
                        ),
                    ),
                ),
            ),
        )
        assertFalse(client.supportsCalendars(JmapSession(apiUrl = "https://mail.example.com/jmap/")))
    }

    @Test fun calendarsAccountId_fallsBackToTheMailAccountOnASingleAccountServer() {
        // The self-hosted case: capability advertised at the session level only, one account.
        val session = JmapSession(
            apiUrl = "https://mail.example.com/jmap/",
            capabilities = mapOf(Jmap.CALENDARS_CAPABILITY to buildJsonObject {}),
            accounts = mapOf("d" to JmapAccount(name = "tate")),
        )
        assertEquals("d", session.calendarsAccountId())
    }

    @Test fun getCalendars_emptyAndNoCallWhenCapabilityAbsent() = runBlocking {
        val session = JmapSession(apiUrl = server.url("/jmap/").toString())
        assertTrue(client.getCalendars(session, "d", BasicAuth("u", "p")).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun getCalendars_parsesTheListAndItsRights() = runBlocking {
        server.enqueue(MockResponse().setBody(CALENDARS_JSON))

        val calendars = client.getCalendars(calendarSession(), "d", BasicAuth("u", "p"))

        assertEquals(3, calendars.size)
        assertEquals("Calendar", calendars[0].name)
        assertTrue(calendars[0].isDefault)
        assertTrue(calendars[0].myRights.mayWriteAll)
        assertTrue(calendars[0].myRights.mayRSVP)
        // ⚠️ Unsubscribed calendars are RETURNED, not filtered. Stalwart leaves secondaries
        // unsubscribed, and dropping them here would make JMAP and CalDAV disagree about the
        // account's contents.
        assertEquals("Family Calendar", calendars[1].name)
        assertFalse(calendars[1].isSubscribed)

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("Calendar/get"))
        assertTrue(sent.contains(Jmap.CALENDARS_CAPABILITY))
    }

    @Test fun getCalendars_absentRightsBlockDeniesRatherThanAllows() = runBlocking {
        server.enqueue(MockResponse().setBody(CALENDAR_NO_RIGHTS_JSON))

        val calendar = client.getCalendars(calendarSession(), "d", BasicAuth("u", "p")).single()

        // A server that did not say is not a server that said yes: an editable-looking calendar
        // that refuses at the last moment is worse than one that never offered.
        assertFalse(calendar.myRights.mayWriteAll)
        assertFalse(calendar.myRights.mayReadItems)
    }

    @Test fun queryCalendarEventIds_sendsTheWindowAndReadsTheStateAndTotal() = runBlocking {
        server.enqueue(MockResponse().setBody(QUERY_WINDOW_JSON))

        val page = client.queryCalendarEventIds(
            calendarSession(), "d", BasicAuth("u", "p"),
            after = "2026-08-01T00:00:00Z",
            before = "2026-09-01T00:00:00Z",
            limit = 5,
        )

        assertEquals(listOf("p", "q", "w", "7", "ba"), page.ids)
        assertEquals("swuwa", page.queryState)
        assertEquals(9, page.total)

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"after\":\"2026-08-01T00:00:00Z\""))
        assertTrue(sent.contains("\"before\":\"2026-09-01T00:00:00Z\""))
        assertTrue(sent.contains("\"calculateTotal\":true"))
        // 🔴 Stalwart answers `unsupportedFilter` to inCalendars AND to calendarIds, and an
        // unsupported filter fails the whole method rather than degrading to "all calendars".
        // Selecting one calendar is the caller's job, off the events' own calendarIds.
        assertFalse(sent.contains("inCalendars"))
        assertFalse(sent.contains("calendarIds"))
    }

    @Test fun queryCalendarEventIds_sendsNoFilterWhenThereIsNoWindow() = runBlocking {
        server.enqueue(MockResponse().setBody(QUERY_WINDOW_JSON))
        client.queryCalendarEventIds(calendarSession(), "d", BasicAuth("u", "p"))
        assertFalse(server.takeRequest().body.readUtf8().contains("\"filter\""))
    }

    @Test fun queryCalendarEventIds_missingTotalIsNullNotZero() = runBlocking {
        server.enqueue(MockResponse().setBody(QUERY_NO_TOTAL_JSON))
        // "The server did not count" and "nothing matched" are different answers, and only one of
        // them means the list in hand is the whole set.
        assertNull(client.queryCalendarEventIds(calendarSession(), "d", BasicAuth("u", "p")).total)
    }

    @Test fun queryCalendarEventId_bridgesAUidToTheServersOwnId() = runBlocking {
        server.enqueue(MockResponse().setBody(QUERY_UID_JSON))

        val id = client.queryCalendarEventId(calendarSession(), "d", "0e17a658-cfc2", BasicAuth("u", "p"))

        assertEquals("i", id)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"uid\":\"0e17a658-cfc2\""))

        server.enqueue(MockResponse().setBody(QUERY_NONE_JSON))
        assertNull(client.queryCalendarEventId(calendarSession(), "d", "missing", BasicAuth("u", "p")))
    }

    @Test fun getCalendarEvents_noCallForAnEmptyIdList() = runBlocking {
        assertTrue(client.getCalendarEvents(calendarSession(), "d", emptyList(), BasicAuth("u", "p")).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun getCalendarEvents_parsesATimedEventWithItsZoneAndDuration() = runBlocking {
        server.enqueue(MockResponse().setBody(EVENTS_JSON))

        val events = client.getCalendarEvents(calendarSession(), "d", listOf("i", "j", "k"), BasicAuth("u", "p"))

        val bikeService = events.first { it.id == "i" }
        assertEquals("Lakeside Bike Service", bikeService.title)
        // 🔴 A local wall time plus a zone, NOT an instant. Collapsing these two into an epoch
        // would shift every recurring event by an hour, twice a year.
        assertEquals("2026-06-10T14:30:00", bikeService.start)
        assertEquals("America/New_York", bikeService.timeZone)
        assertEquals("PT1H", bikeService.duration)
        assertFalse(bikeService.showWithoutTime)
        assertEquals("b", bikeService.primaryCalendarId())
        // The iCalendar UID survives verbatim; it is the bridge to the CalDAV-keyed cache.
        assertTrue(bikeService.uid.startsWith("040000008200E00074C5B7101A82E008"))
    }

    @Test fun getCalendarEvents_readsAStructuredRecurrenceRuleNotAnRruleString() = runBlocking {
        server.enqueue(MockResponse().setBody(EVENTS_JSON))

        val trash = client.getCalendarEvents(calendarSession(), "d", listOf("k"), BasicAuth("u", "p"))
            .first { it.id == "k" }

        // The whole reason this path beats the iCalendar one: no RRULE text to validate by hand.
        val rule = trash.recurrenceRule!!
        assertEquals("weekly", rule.frequency)
        assertEquals(2, rule.interval)
        assertEquals("2026-07-05T00:00:00", rule.until)
        assertEquals(listOf("mo"), rule.byDay.map { it.day })
        assertNull(rule.byDay.single().nthOfPeriod)
        // ⚠️ Lower case here, upper case in iCalendar. Anything rendering an RRULE must convert.
        assertTrue(trash.showWithoutTime)
        assertEquals("P1D", trash.duration)
        assertNull(trash.timeZone)
    }

    @Test fun getCalendarEvents_readsLocationsAndAPlainDescription() = runBlocking {
        server.enqueue(MockResponse().setBody(EVENTS_JSON))

        val appt = client.getCalendarEvents(calendarSession(), "d", listOf("j"), BasicAuth("u", "p"))
            .first { it.id == "j" }

        assertEquals("2140 Windmere Rd, Ste 200 Fairhaven PA 17033-2841", appt.locationName())
        assertTrue(appt.description!!.contains("Arrive by 10:15 AM"))
        // No descriptionContentType means plain text, not "unknown, guess".
        assertFalse(appt.descriptionIsHtml())
    }

    @Test fun getCalendarEvents_readsHtmlDescriptionsAttachmentsAndParticipants() = runBlocking {
        server.enqueue(MockResponse().setBody(RICH_EVENT_JSON))

        val event = client.getCalendarEvents(calendarSession(), "d", listOf("z"), BasicAuth("u", "p")).single()

        assertTrue(event.descriptionIsHtml())
        assertTrue(event.description!!.contains("<b>"))
        val link = event.links.values.single()
        assertEquals("agenda.pdf", link.title)
        assertEquals("application/pdf", link.contentType)
        assertEquals(48213L, link.size)
        assertEquals("enclosure", link.rel)
        assertEquals("organiser@example.com", event.organizerEmail())
        val organiser = event.participants.values.first { it.isOrganizer() }
        assertEquals("organiser@example.com", organiser.address())
        val invitee = event.participants.values.first { !it.isOrganizer() }
        // sendTo is the address when there is no explicit email field.
        assertEquals("tate@gridlink.me", invitee.address())
        assertEquals("needs-action", invitee.participationStatus)
        assertTrue(invitee.expectReply)
    }

    @Test fun getCalendarEvents_unknownSizeIsNullNotZero() = runBlocking {
        server.enqueue(MockResponse().setBody(LINK_NO_SIZE_JSON))
        val link = client.getCalendarEvents(calendarSession(), "d", listOf("z"), BasicAuth("u", "p"))
            .single().links.values.single()
        // A zero-byte attachment and an attachment of unstated length are not the same thing.
        assertNull(link.size)
    }

    @Test fun getCalendarEvents_keepsAnOverrideItCannotModel() = runBlocking {
        server.enqueue(MockResponse().setBody(OVERRIDES_JSON))

        val event = client.getCalendarEvents(calendarSession(), "d", listOf("k"), BasicAuth("u", "p")).single()

        assertEquals(2, event.recurrenceOverrides.size)
        // A cancelled instance.
        assertTrue(event.recurrenceOverrides["2026-06-22T00:00:00"]!!.containsKey("excluded"))
        // A detached override naming a property this class does not model survives as raw JSON
        // rather than being decoded into nothing.
        assertTrue(event.recurrenceOverrides["2026-07-06T00:00:00"]!!.containsKey("alerts"))
    }

    @Test fun calendarEventChanges_readsTheChangeSet() = runBlocking {
        server.enqueue(MockResponse().setBody(CHANGES_JSON))

        val result = client.calendarEventChanges(calendarSession(), "d", "swuwa", BasicAuth("u", "p"))

        assertTrue(result.calculated)
        assertEquals("swuwb", result.newState)
        assertEquals(listOf("n1"), result.created)
        assertEquals(listOf("i"), result.updated)
        assertEquals(listOf("q"), result.destroyed)
        assertFalse(result.hasMoreChanges)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"sinceState\":\"swuwa\""))
    }

    @Test fun calendarEventChanges_neverInventsAStartingState() = runBlocking {
        // 🔴 Stalwart rejects a made-up sinceState with `notRequest` at the REQUEST level, killing
        // every method in the batch, not just this one. So a blank state must never reach the wire.
        val result = client.calendarEventChanges(calendarSession(), "d", null, BasicAuth("u", "p"))

        assertFalse(result.calculated)
        assertEquals(0, server.requestCount)
        assertTrue(client.calendarEventChanges(calendarSession(), "d", "  ", BasicAuth("u", "p")).created.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun calendarEventChanges_cannotCalculateIsNotAnEmptyChangeSet() = runBlocking {
        server.enqueue(MockResponse().setBody(CANNOT_CALCULATE_JSON))

        val result = client.calendarEventChanges(calendarSession(), "d", "stale", BasicAuth("u", "p"))

        // A caller reading this as "nothing changed" would stop syncing and never say why.
        assertFalse(result.calculated)
        assertTrue(result.created.isEmpty())
        assertNull(result.newState)
    }

    private companion object {
        /** Verbatim Stalwart `Calendar/get`, trimmed to two rights blocks for length. */
        val CALENDARS_JSON = """
            {"methodResponses":[["Calendar/get",{"accountId":"d","state":"syqvq","list":[
              {"id":"b","name":"Calendar","description":null,"color":null,"timeZone":null,
               "sortOrder":0,"isDefault":true,"isSubscribed":true,
               "myRights":{"mayReadFreeBusy":true,"mayReadItems":true,"mayWriteAll":true,
                 "mayWriteOwn":true,"mayUpdatePrivate":true,"mayRSVP":true,"mayShare":true,
                 "mayDelete":true}},
              {"id":"c","name":"Family Calendar","sortOrder":0,"isDefault":false,
               "isSubscribed":false,"myRights":{"mayReadItems":true}},
              {"id":"d","name":"Tasks","sortOrder":0,"isDefault":false,"isSubscribed":false,
               "myRights":{"mayReadItems":true}}
            ],"notFound":[]},"cal0"]]}
        """.trimIndent()

        val CALENDAR_NO_RIGHTS_JSON = """
            {"methodResponses":[["Calendar/get",{"accountId":"d","list":[
              {"id":"b","name":"Shared"}
            ]},"cal0"]]}
        """.trimIndent()

        val QUERY_WINDOW_JSON = """
            {"methodResponses":[["CalendarEvent/query",{"accountId":"d","queryState":"swuwa",
              "canCalculateChanges":true,"position":0,"ids":["p","q","w","7","ba"],
              "total":9,"limit":5},"cq0"]]}
        """.trimIndent()

        val QUERY_NO_TOTAL_JSON = """
            {"methodResponses":[["CalendarEvent/query",{"accountId":"d","queryState":"swuwa",
              "position":0,"ids":["p"]},"cq0"]]}
        """.trimIndent()

        val QUERY_UID_JSON = """
            {"methodResponses":[["CalendarEvent/query",{"accountId":"d","ids":["i"]},"cu0"]]}
        """.trimIndent()

        val QUERY_NONE_JSON = """
            {"methodResponses":[["CalendarEvent/query",{"accountId":"d","ids":[]},"cu0"]]}
        """.trimIndent()

        /** Verbatim Stalwart `CalendarEvent/get`: a timed event, one with a location, one all-day. */
        val EVENTS_JSON = """
            {"methodResponses":[["CalendarEvent/get",{"accountId":"d","state":"swuwa","list":[
              {"@type":"Event","id":"i","calendarIds":{"b":true},"isDraft":false,"isOrigin":true,
               "uid":"040000008200E00074C5B7101A82E00800000000D3ADF1CC0600DD01",
               "title":"Lakeside Bike Service","description":"\n","start":"2026-06-10T14:30:00",
               "duration":"PT1H","timeZone":"America/New_York","freeBusyStatus":"busy",
               "privacy":"public","priority":5,"sequence":0,"updated":"2026-06-20T01:29:17Z"},
              {"@type":"Event","id":"j","calendarIds":{"b":true},
               "uid":"040000008200E00074C5B7101A82E00800000000FABF81CC0600DD01",
               "title":"Meridian Clinic Established","start":"2026-06-12T10:15:00","duration":"PT1H15M",
               "timeZone":"America/New_York",
               "description":"Arrive by 10:15 AM Appointment Time: Starts at 10:30 AM\n",
               "locations":{"753defe1-ff4f-5002-95aa-0d5e662d10c3":{"@type":"Location",
                 "name":"2140 Windmere Rd, Ste 200 Fairhaven PA 17033-2841"}}},
              {"@type":"Event","id":"k","calendarIds":{"b":true},
               "uid":"040000008200E00074C5B7101A82E0080000000065FCF451B7F7DC01",
               "title":"Trash & Recycling Tomorrow","start":"2026-06-08T00:00:00","duration":"P1D",
               "timeZone":null,"showWithoutTime":true,"freeBusyStatus":"free",
               "recurrenceRule":{"frequency":"weekly","until":"2026-07-05T00:00:00","interval":2,
                 "firstDayOfWeek":"su","byDay":[{"day":"mo"}]}}
            ],"notFound":[]},"cg0"]]}
        """.trimIndent()

        val RICH_EVENT_JSON = """
            {"methodResponses":[["CalendarEvent/get",{"accountId":"d","list":[
              {"@type":"Event","id":"z","calendarIds":{"b":true},"uid":"rich-1",
               "title":"Quarterly review","start":"2026-09-02T09:00:00","duration":"PT1H",
               "timeZone":"America/New_York","descriptionContentType":"text/html",
               "description":"<p>Bring the <b>numbers</b>.</p>",
               "replyTo":{"imip":"mailto:organiser@example.com"},
               "links":{"l1":{"href":"https://mail.example.com/dav/att/agenda.pdf",
                 "contentType":"application/pdf","size":48213,"rel":"enclosure",
                 "title":"agenda.pdf","blobId":"blob-77"}},
               "participants":{
                 "p1":{"@type":"Participant","name":"The organiser",
                   "sendTo":{"imip":"mailto:organiser@example.com"},
                   "roles":{"owner":true,"attendee":true},"participationStatus":"accepted"},
                 "p2":{"@type":"Participant","sendTo":{"imip":"mailto:tate@gridlink.me"},
                   "roles":{"attendee":true},"participationStatus":"needs-action",
                   "expectReply":true}}}
            ]},"cg0"]]}
        """.trimIndent()

        val LINK_NO_SIZE_JSON = """
            {"methodResponses":[["CalendarEvent/get",{"accountId":"d","list":[
              {"id":"z","uid":"u","title":"T",
               "links":{"l1":{"href":"https://example.com/a.pdf","rel":"enclosure"}}}
            ]},"cg0"]]}
        """.trimIndent()

        val OVERRIDES_JSON = """
            {"methodResponses":[["CalendarEvent/get",{"accountId":"d","list":[
              {"id":"k","uid":"u","title":"Trash","start":"2026-06-08T00:00:00",
               "recurrenceRule":{"frequency":"weekly"},
               "recurrenceOverrides":{
                 "2026-06-22T00:00:00":{"excluded":true},
                 "2026-07-06T00:00:00":{"title":"Trash (holiday week)",
                   "alerts":{"a1":{"@type":"Alert","trigger":{"offset":"-PT30M"}}}}}}
            ]},"cg0"]]}
        """.trimIndent()

        val CHANGES_JSON = """
            {"methodResponses":[["CalendarEvent/changes",{"accountId":"d","oldState":"swuwa",
              "newState":"swuwb","hasMoreChanges":false,"created":["n1"],"updated":["i"],
              "destroyed":["q"]},"cc0"]]}
        """.trimIndent()

        val CANNOT_CALCULATE_JSON = """
            {"methodResponses":[["error",{"type":"cannotCalculateChanges"},"cc0"]]}
        """.trimIndent()
    }
}
