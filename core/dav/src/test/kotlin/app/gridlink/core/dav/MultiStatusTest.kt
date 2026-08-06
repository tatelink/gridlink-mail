package app.gridlink.core.dav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests written against bodies captured verbatim from the live Stalwart server, not against
 * the RFC's examples. The two disagree in ways that matter (CDATA-wrapped payloads, a 507 on the
 * collection's own href, a `sync-token` that means two different things depending on depth), and a
 * parser that only ever sees idealised input passes right up to the moment it meets a real server.
 */
class MultiStatusTest {

    private fun parse(xml: String) = MultiStatus.parse(xml.byteInputStream())

    @Test
    fun `reads etags and decodes hrefs from a carddav sync`() {
        val result = parse(
            """<?xml version="1.0" encoding="UTF-8"?><D:multistatus xmlns:D="DAV:" xmlns:B="urn:ietf:params:xml:ns:carddav"><D:response><D:href>/dav/card/brandon%40gridlink.me/default/0062.vcf</D:href><D:propstat><D:prop><D:getetag>&quot;734022837&quot;</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response><D:sync-token>urn:stalwart:davsync:f05</D:sync-token></D:multistatus>""",
        )

        assertEquals(1, result.responses.size)
        // Decoded: the @ must not stay as %40, or the same card gets two identities.
        assertEquals("/dav/card/brandon@gridlink.me/default/0062.vcf", result.responses[0].href)
        assertEquals("\"734022837\"", result.responses[0].prop(PropKey.GET_ETAG))
        assertEquals("urn:stalwart:davsync:f05", result.syncToken)
    }

    @Test
    fun `reads CDATA wrapped address data`() {
        val result = parse(
            """<?xml version="1.0" encoding="UTF-8"?><D:multistatus xmlns:D="DAV:" xmlns:B="urn:ietf:params:xml:ns:carddav"><D:response><D:href>/dav/card/x/003d.vcf</D:href><D:propstat><D:prop><D:getetag>&quot;566890310&quot;</D:getetag><B:address-data><![CDATA[BEGIN:VCARD
VERSION:3.0
FN:Karen Caldwell
TEL;TYPE=CELL:+1 704-232-8656
END:VCARD
]]></B:address-data></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>""",
        )

        val data = result.responses.single().prop(PropKey.ADDRESS_DATA)!!
        assertTrue(data.contains("FN:Karen Caldwell"))
        assertTrue(data.contains("TEL;TYPE=CELL:+1 704-232-8656"))
    }

    @Test
    fun `separates the collection listing from the home collection by resourcetype`() {
        val result = parse(
            """<?xml version="1.0" encoding="UTF-8"?><D:multistatus xmlns:D="DAV:" xmlns:A="urn:ietf:params:xml:ns:caldav"><D:response><D:href>/dav/cal/brandon%40gridlink.me/</D:href><D:propstat><D:prop><A:supported-calendar-component-set/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype><D:displayname>Brandon</D:displayname></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response><D:response><D:href>/dav/cal/brandon%40gridlink.me/default/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/><A:calendar/></D:resourcetype><D:displayname>Calendar</D:displayname></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>""",
        )

        val calendars = result.responses.filter { it.isType("urn:ietf:params:xml:ns:caldav|calendar") }
        assertEquals(1, calendars.size)
        assertEquals("Calendar", calendars.single().prop(PropKey.DISPLAY_NAME))
        // The home container is a plain collection and must not be synced as a calendar.
        assertEquals("Brandon", result.responses[0].prop(PropKey.DISPLAY_NAME))
        assertFalse(result.responses[0].isType("urn:ietf:params:xml:ns:caldav|calendar"))
    }

    @Test
    fun `does not keep properties a propstat reported as 404`() {
        val result = parse(
            """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/c/</D:href><D:propstat><D:prop><calendar-color xmlns="http://apple.com/ns/ical/"/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat><D:propstat><D:prop><D:displayname>Calendar</D:displayname></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>""",
        )

        // An empty string here would become an empty colour swatch in the UI, which is a claim
        // the server never made.
        assertNull(result.responses.single().prop(PropKey.CALENDAR_COLOR))
        assertEquals("Calendar", result.responses.single().prop(PropKey.DISPLAY_NAME))
    }

    @Test
    fun `a response level 404 is a deletion, a propstat 404 is not`() {
        val result = parse(
            """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/c/gone.ics</D:href><D:status>HTTP/1.1 404 Not Found</D:status></D:response><D:response><D:href>/c/here.ics</D:href><D:propstat><D:prop><D:getetag>&quot;1&quot;</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat><D:propstat><D:prop><D:displayname/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response></D:multistatus>""",
        )

        assertTrue(result.responses[0].isRemoved)
        assertFalse(result.responses[1].isRemoved)
    }

    @Test
    fun `reads a home set carried by a nested href`() {
        val result = parse(
            """<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><D:response><D:href>/principals/u/</D:href><D:propstat><D:prop><C:calendar-home-set><D:href>/dav/cal/brandon%40gridlink.me/</D:href></C:calendar-home-set></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>""",
        )

        assertEquals(
            "/dav/cal/brandon@gridlink.me/",
            result.responses.single().prop(PropKey.CALENDAR_HOME_SET),
        )
    }

    @Test
    fun `a collection level sync-token is a property, not the trailing token`() {
        val result = parse(
            """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/c/</D:href><D:propstat><D:prop><D:sync-token>urn:stalwart:davsync:eed</D:sync-token></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>""",
        )

        assertEquals("urn:stalwart:davsync:eed", result.responses.single().prop(PropKey.SYNC_TOKEN))
        // 🔴 Nothing may read this as "the token to resume from": see MultiStatusResult.syncToken.
        assertNull(result.syncToken)
    }

    @Test
    fun `refuses a doctype rather than expanding it`() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE m [<!ENTITY x "boom">]><D:multistatus xmlns:D="DAV:"><D:response><D:href>/&x;</D:href></D:response></D:multistatus>"""
        val failed = runCatching { parse(hostile) }.isFailure
        assertTrue("A DOCTYPE must fail the parse, not be processed", failed)
    }

    @Test
    fun `leaves a plus sign alone when decoding an href`() {
        val result = parse(
            """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/c/a+b%20c.vcf</D:href></D:response></D:multistatus>""",
        )
        // URLDecoder would turn the + into a space and rename the resource.
        assertEquals("/c/a+b c.vcf", result.responses.single().href)
    }
}
