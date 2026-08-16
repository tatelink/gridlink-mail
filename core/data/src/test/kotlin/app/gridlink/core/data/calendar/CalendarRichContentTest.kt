package app.gridlink.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The two facts an appointment carries besides its own text: whether the description is HTML, and
 * what is attached to it.
 *
 * Both protocols are exercised here rather than in their own reader's file, because the whole point
 * of the change is that they agree: an Exchange invitation delivered over CalDAV and the same
 * meeting fetched over JMAP must reach the screen saying the same thing about its own description.
 * A test per reader would let those two answers drift and still pass.
 */
class CalendarRichContentTest {

    private val eastern = ZoneId.of("America/New_York")

    // ---- Descriptions -------------------------------------------------------------------------

    @Test fun anOrdinaryDescriptionIsNotClaimedToBeHtmlOnEitherProtocol() {
        val dav = ICalendarStream.parse(ics(), eastern).single()
        val jmap = JsCalendar.parse(json(), eastern).single()

        assertFalse(dav.descriptionIsHtml)
        assertFalse(jmap.descriptionIsHtml)
        assertEquals("Bring the checklist.", dav.description)
        assertEquals("Bring the checklist.", jmap.description)
    }

    @Test fun exchangesHtmlDescriptionIsPreferredOverThePlainCopyItSendsAlongside() {
        val parsed = ICalendarStream.parse(
            ics(extra = "X-ALT-DESC;FMTTYPE=text/html:<p>Bring the <b>checklist</b>.</p>"),
            eastern,
        ).single()

        // Both copies are in the file and they say the same thing at different fidelities. Taking
        // the plain one would drop the formatting for no reason; that is the whole bug.
        assertTrue(parsed.descriptionIsHtml)
        assertEquals("<p>Bring the <b>checklist</b>.</p>", parsed.description)
    }

    @Test fun anAltDescThatDoesNotSayItIsHtmlIsIgnoredRatherThanRenderedAsMarkup() {
        // Older exporters put RTF in this property. Rendering that in a WebView shows the reader a
        // page of control words, which is worse than the plain description sitting next to it.
        val parsed = ICalendarStream.parse(
            ics(extra = """X-ALT-DESC;FMTTYPE=text/rtf:{\rtf1\ansi Bring the checklist.}"""),
            eastern,
        ).single()

        assertFalse(parsed.descriptionIsHtml)
        assertEquals("Bring the checklist.", parsed.description)
    }

    @Test fun aJmapEventSayingItsDescriptionIsHtmlIsBelieved() {
        val parsed = JsCalendar.parse(
            json(
                description = "<p>Bring the <b>checklist</b>.</p>",
                extra = """"descriptionContentType": "text/html",""",
            ),
            eastern,
        ).single()

        assertTrue(parsed.descriptionIsHtml)
    }

    @Test fun aContentTypeWithACharsetOnItIsStillHtml() {
        val parsed = JsCalendar.parse(
            json(extra = """"descriptionContentType": "text/html; charset=utf-8","""),
            eastern,
        ).single()

        assertTrue(parsed.descriptionIsHtml)
    }

    @Test fun aMovedInstanceThatRewordsItsDescriptionKeepsTheSeriesContentType() {
        // The patch names `description` and nothing else, so the series' own content type applies.
        // Reading the override as plain would show one day of a formatted series as raw markup.
        val events = JsCalendar.parse(
            json(
                description = "<p>Bring the checklist.</p>",
                extra = """
                    "descriptionContentType": "text/html",
                    "recurrenceRule": { "frequency": "weekly" },
                    "recurrenceOverrides": {
                      "2026-06-17T14:30:00": { "description": "<p>Bring <i>two</i>.</p>" }
                    },
                """.trimIndent(),
            ),
            eastern,
        )

        val override = events.single { it.recurrenceId != null }
        assertTrue(override.descriptionIsHtml)
        assertEquals("<p>Bring <i>two</i>.</p>", override.description)
    }

    @Test fun anInstanceThatSaysItIsPlainOverridesTheSeriesSayingItIsHtml() {
        val events = JsCalendar.parse(
            json(
                extra = """
                    "descriptionContentType": "text/html",
                    "recurrenceRule": { "frequency": "weekly" },
                    "recurrenceOverrides": {
                      "2026-06-17T14:30:00": {
                        "description": "Bring two.", "descriptionContentType": "text/plain"
                      }
                    },
                """.trimIndent(),
            ),
            eastern,
        )

        assertFalse(events.single { it.recurrenceId != null }.descriptionIsHtml)
    }

    // ---- Attachments --------------------------------------------------------------------------

    @Test fun anIcalendarAttachIsReadWithTheNameAndTypeTheServerGaveIt() {
        val parsed = ICalendarStream.parse(
            ics(
                extra = "ATTACH;FMTTYPE=application/pdf;FILENAME=walkthrough.pdf;SIZE=20480:" +
                    "https://files.gridlink.me/w.pdf",
            ),
            eastern,
        ).single()

        val attachment = parsed.attachments.single()
        assertEquals("https://files.gridlink.me/w.pdf", attachment.href)
        assertEquals("walkthrough.pdf", attachment.displayName)
        assertEquals("application/pdf", attachment.contentType)
        assertEquals(20480L, attachment.size)
    }

    @Test fun anAttachmentWithNoNameFallsBackToTheTailOfItsUrlRatherThanShowingTheUrl() {
        val parsed = ICalendarStream.parse(
            ics(extra = "ATTACH:https://files.gridlink.me/reports/q3%20audit.pdf?token=abc"),
            eastern,
        ).single()

        // The query string is not part of the name, and a chip reading the whole URL is a chip
        // nobody can tell apart from the next one.
        assertEquals("q3%20audit.pdf", parsed.attachments.single().displayName)
    }

    @Test fun anInlinedBinaryAttachmentIsSkippedRatherThanCachedTwice() {
        val parsed = ICalendarStream.parse(
            ics(extra = "ATTACH;VALUE=BINARY;ENCODING=BASE64;FMTTYPE=image/png:iVBORw0KGgoAAAA="),
            eastern,
        ).single()

        assertTrue(parsed.attachments.isEmpty())
    }

    @Test fun anAttachmentNothingCouldFetchIsNotOfferedAsAChip() {
        val parsed = ICalendarStream.parse(
            ics(extra = "ATTACH:cid:part1.0001@example.com"),
            eastern,
        ).single()

        assertTrue(parsed.attachments.isEmpty())
    }

    @Test fun aVeryLongListOfAttachmentsIsCappedRatherThanCarriedWhole() {
        val many = (1..40).joinToString("\r\n") { "ATTACH:https://files.gridlink.me/$it.pdf" }
        val parsed = ICalendarStream.parse(ics(extra = many), eastern).single()

        assertEquals(ICalendarStream.MAX_ATTACHMENTS, parsed.attachments.size)
    }

    @Test fun aJmapEnclosureIsAnAttachmentAndAnIconIsNot() {
        val parsed = JsCalendar.parse(
            json(
                extra = """
                    "links": {
                      "1": {
                        "href": "https://files.gridlink.me/w.pdf", "rel": "enclosure",
                        "title": "walkthrough.pdf", "contentType": "application/pdf", "size": 20480
                      },
                      "2": { "href": "https://files.gridlink.me/logo.png", "rel": "icon" },
                      "3": { "href": "https://files.gridlink.me/full.html", "rel": "describedby" }
                    },
                """.trimIndent(),
            ),
            eastern,
        ).single()

        // An icon is the calendar's decoration and `describedby` is another rendering of the
        // description itself. Listing either as an attachment invents a document.
        val attachment = parsed.attachments.single()
        assertEquals("walkthrough.pdf", attachment.displayName)
        assertEquals(20480L, attachment.size)
    }

    @Test fun aLinkWithNoRelIsTakenAsAnAttachmentBecauseTheServerStillSentAFile() {
        val parsed = JsCalendar.parse(
            json(extra = """"links": { "1": { "href": "https://files.gridlink.me/w.pdf" } },"""),
            eastern,
        ).single()

        assertEquals(1, parsed.attachments.size)
    }

    @Test fun aBlobBackedLinkSurvivesEvenWithNothingFetchableInItsHref() {
        val parsed = JsCalendar.parse(
            json(
                extra = """
                    "links": {
                      "1": { "href": "", "blobId": "G12ab", "title": "notes.txt", "rel": "enclosure" }
                    },
                """.trimIndent(),
            ),
            eastern,
        ).single()

        // The download URL for a blob comes out of the JMAP session, not out of the event, so an
        // empty href here is not a broken link; it is the ordinary shape of a managed attachment.
        val attachment = parsed.attachments.single()
        assertEquals("G12ab", attachment.blobId)
        assertEquals("notes.txt", attachment.displayName)
    }

    @Test fun aLinkPointingAtNothingAtAllIsDropped() {
        val parsed = JsCalendar.parse(
            json(extra = """"links": { "1": { "href": "/relative/path.pdf" } },"""),
            eastern,
        ).single()

        assertTrue(parsed.attachments.isEmpty())
    }

    @Test fun aSizeTheServerDidNotStateStaysUnknownRatherThanBecomingZero() {
        val parsed = JsCalendar.parse(
            json(extra = """"links": { "1": { "href": "https://files.gridlink.me/w.pdf" } },"""),
            eastern,
        ).single()

        assertNull(parsed.attachments.single().size)
    }

    // ---- Reaching the screen ------------------------------------------------------------------

    @Test fun everyOccurrenceOfASeriesCarriesTheDescriptionFlagAndTheAttachments() {
        val events = JsCalendar.parse(
            json(
                description = "<p>Bring the checklist.</p>",
                extra = """
                    "descriptionContentType": "text/html",
                    "recurrenceRule": { "frequency": "weekly" },
                    "links": {
                      "1": { "href": "https://files.gridlink.me/w.pdf", "rel": "enclosure" }
                    },
                """.trimIndent(),
            ),
            eastern,
        )

        val occurrences = ICalendarStream.occurrences(
            events = events,
            window = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 30),
            displayZone = eastern,
        )

        // Three weekly instances in June from the 10th. Every one of them is the same meeting with
        // the same document on it: an attachment that only appeared on the first would look like
        // the organiser had removed it.
        assertTrue(occurrences.size > 1)
        assertTrue(occurrences.all { it.descriptionIsHtml })
        assertTrue(occurrences.all { it.attachments.size == 1 })
    }

    @Test fun aPlainCaldavEventReachesTheScreenSayingNothingAboutHtmlOrFiles() {
        val occurrence = ICalendarStream.occurrences(
            events = ICalendarStream.parse(ics(), eastern),
            window = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 6, 30),
            displayZone = eastern,
        ).single()

        assertFalse(occurrence.descriptionIsHtml)
        assertTrue(occurrence.attachments.isEmpty())
    }

    // ---- Fixtures -----------------------------------------------------------------------------

    /** One timed VEVENT, with [extra] folded in as further properties of the same event. */
    private fun ics(extra: String? = null) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n")
        append("UID:8c9f2b16\r\n")
        append("DTSTART;TZID=America/New_York:20260610T143000\r\n")
        append("DTEND;TZID=America/New_York:20260610T154500\r\n")
        append("SUMMARY:Store 4820 walkthrough\r\n")
        append("DESCRIPTION:Bring the checklist.\r\n")
        if (extra != null) append(extra).append("\r\n")
        append("END:VEVENT\r\nEND:VCALENDAR\r\n")
    }

    /** The same event as JSCalendar, with [extra] spliced in as further members of the object. */
    private fun json(description: String = "Bring the checklist.", extra: String = "") =
        """
        {
          "@type": "Event",
          "id": "E1",
          "uid": "8c9f2b16",
          "title": "Store 4820 walkthrough",
          "description": "$description",
          $extra
          "start": "2026-06-10T14:30:00",
          "duration": "PT1H15M",
          "timeZone": "America/New_York"
        }
        """.trimIndent()
}
