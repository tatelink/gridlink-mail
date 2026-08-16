package app.gridlink.core.data.contacts

import app.gridlink.core.data.db.AddressBookContactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSContact reader, against payloads shaped the way Stalwart 0.16.15 actually sends them.
 *
 * Every assertion here is really about one thing: a card that arrived over JMAP has to land in the
 * same [ParsedContact] a vCard would have produced, because everything above this layer is written
 * against that type and gets no say in which protocol fetched the card.
 */
class JsContactTest {

    private fun parse(raw: String) = JsContact.parse(raw)

    @Test fun readsAPersonIntoTheSameShapeTheVcardPathProduces() {
        val parsed = parse(
            """
            {"id":"c1","uid":"urn:uuid:9e3f",
             "name":{"full":"Ada Lovelace","components":[
                {"kind":"given","value":"Ada"},{"kind":"surname","value":"Lovelace"}]},
             "organizations":{"o1":{"name":"Analytical Engines"}},
             "titles":{"t1":{"name":"Countess"}},
             "emails":{"e1":{"address":"ada@example.com"}},
             "phones":{"p1":{"number":"+1 555 0100"}},
             "notes":{"n1":{"note":"Met at the fair"}},
             "addresses":{"a1":{"full":"12 Marylebone Rd\nLondon\nNW1"}}}
            """.trimIndent(),
        )!!

        assertEquals("urn:uuid:9e3f", parsed.uid)
        assertEquals("Ada Lovelace", parsed.formattedName)
        assertEquals("Lovelace", parsed.family)
        assertEquals("Ada", parsed.given)
        assertEquals("Analytical Engines", parsed.organization)
        assertEquals("Countess", parsed.title)
        assertEquals("ada@example.com", parsed.primaryEmail)
        assertEquals(listOf("+1 555 0100"), parsed.phones)
        assertEquals("Met at the fair", parsed.note)
        // The address is flattened to one display line, newlines and all, exactly as ADR is.
        assertEquals(listOf("12 Marylebone Rd, London, NW1"), parsed.addresses)
        assertFalse(parsed.isOrganization)
        assertEquals("Lovelace", parsed.fileAsFamily)
        assertEquals("Ada", parsed.fileAsGiven)
    }

    @Test fun theComponentsAreTheFallbackNotTheOtherWayRound() {
        // A card can carry `full` without carrying components at all, and `full` is what the card
        // says it is called. Building the name from components first would rewrite it.
        val components = """[{"kind":"given","value":"Ada"},{"kind":"surname","value":"Lovelace"}]"""

        val full = parse("""{"id":"c1","name":{"full":"Dr. Ada Lovelace","components":$components}}""")!!
        assertEquals("Dr. Ada Lovelace", full.formattedName)

        val componentsOnly = parse("""{"id":"c1","name":{"components":$components}}""")!!
        assertEquals("Ada Lovelace", componentsOnly.formattedName)
    }

    @Test fun anOrgCardWithNoOrganizationsEntryStillFilesAsACompany() {
        // 🔴 Stalwart writes exactly this for a company created through its own UI. Without the
        // full name copied into `organization`, `isOrganization` (which is ORG == FN) comes out
        // false and the Contacts tab files Redoak Foodservice under a surname it does not have.
        val parsed = parse("""{"id":"c2","kind":"org","name":{"full":"Redoak Foodservice"}}""")!!

        assertTrue(parsed.isOrganization)
        assertEquals("Redoak Foodservice", parsed.fileAsFamily)
        assertEquals("", parsed.fileAsGiven)
        // The company name is not a job title, so the subtitle stays empty rather than repeating it.
        assertEquals("", parsed.role)
    }

    @Test fun prefIsARankSoTheLowestNumberIsThePrimaryAddress() {
        val parsed = parse(
            """
            {"id":"c1","emails":{
                "e1":{"address":"work@example.com","pref":2},
                "e2":{"address":"home@example.com","pref":1},
                "e3":{"address":"old@example.com"}},
             "phones":{"p1":{"number":"+1 555 0199"},"p2":{"number":"+1 555 0100","pref":1}}}
            """.trimIndent(),
        )!!

        // RFC 9553 §1.5.3: 1 is MOST preferred, and an absent pref is LEAST. `primaryEmail` is the
        // address the app writes to, so getting the direction wrong sends mail to the stale one.
        assertEquals("home@example.com", parsed.primaryEmail)
        assertEquals(listOf("home@example.com", "work@example.com", "old@example.com"), parsed.emails)
        assertEquals(listOf("+1 555 0100", "+1 555 0199"), parsed.phones)
    }

    @Test fun severalNotesReadAsOneBlockRatherThanOnlyTheFirst() {
        val parsed = parse("""{"id":"c1","notes":{"n1":{"note":"First"},"n2":{"note":"Second"}}}""")!!

        assertEquals("First\n\nSecond", parsed.note)
    }

    @Test fun onlyAnInlineBase64PhotoIsReadable() {
        val inline = parse("""{"id":"c1","media":{"m1":{"uri":"data:image/png;base64,iVBORw0KGgo="}}}""")!!
        // `kind` absent counts as a photo: Stalwart omits it on cards converted from a vCard PHOTO.
        assertEquals("image/png", inline.photo?.mediaType)
        assertEquals("iVBORw0KGgo=", inline.photo?.base64)

        // Nothing to show offline, and nothing this app would re-upload.
        assertNull(parse("""{"id":"c1","media":{"m1":{"kind":"photo","uri":"https://x/e.png"}}}""")!!.photo)
        // A data URI that is not base64 is not something the photo pipeline can decode.
        assertNull(parse("""{"id":"c1","media":{"m1":{"uri":"data:image/svg+xml,<svg/>"}}}""")!!.photo)
        // A logo is not a portrait.
        assertNull(parse("""{"id":"c1","media":{"m1":{"kind":"logo","uri":"data:image/png;base64,AA=="}}}""")!!.photo)
    }

    @Test fun gridlinkCustomFieldsSurviveTheRoundTripAndForeignPropsAreSkipped() {
        val parsed = parse(
            """
            {"id":"c1","vCardProps":[
                ["x-gridlink-field",{},"text",["Nickname","Countess"]],
                ["x-abrelatednames",{},"text","Babbage"],
                ["x-gridlink-field",{},"text","not a pair"]]}
            """.trimIndent(),
        )!!

        // The exact inverse of the writer. An entry of another name, or one whose value is not the
        // [label, value] pair, is skipped rather than guessed at.
        assertEquals(1, parsed.customFields.size)
        assertEquals("Nickname", parsed.customFields.first().label)
        assertEquals("Countess", parsed.customFields.first().value)
    }

    @Test fun aPayloadThatNoLongerReadsIsNullNotACrash() {
        // The display columns carry a row whose payload this app cannot parse; the contacts list
        // must not throw on it.
        assertNull(JsContact.parse("not json at all"))
        assertNull(JsContact.parse(null))
        assertNull(JsContact.decode("""{"id":42}"""))
    }

    @Test fun encodeThenDecodeKeepsWhatTheModelHolds() {
        val raw = """
            {"id":"c1","uid":"urn:uuid:9e3f","name":{"full":"Ada Lovelace"},
             "emails":{"e1":{"address":"ada@example.com","pref":1}},
             "vCardProps":[["x-gridlink-field",{},"text",["Nickname","Countess"]]],
             "updated":"2026-08-16T11:02:00Z"}
        """.trimIndent()

        val round = JsContact.decode(JsContact.encode(JsContact.decode(raw)!!))!!

        assertEquals("c1", round.id)
        assertEquals("urn:uuid:9e3f", round.uid)
        assertEquals("Ada Lovelace", round.name?.full)
        assertEquals(listOf("ada@example.com"), round.emailAddresses())
        assertEquals("Countess", round.customFields().single().value)
        // ⚠️ `updated` is what the JMAP sync path stores as the row's etag, so losing it in the
        // cache round trip would make the system-contacts mirror re-write every card every sync.
        assertEquals("2026-08-16T11:02:00Z", round.updated)
    }

    @Test fun contactPayloadPicksTheReaderTheRowsFormatCallsFor() {
        val jsContact = """{"id":"c1","name":{"full":"Ada Lovelace"}}"""
        val vCard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada Lovelace\r\nEND:VCARD\r\n"

        assertEquals(
            "Ada Lovelace",
            ContactPayload.parse(jsContact, AddressBookContactEntity.FORMAT_JSCONTACT)?.formattedName,
        )
        assertEquals(
            "Ada Lovelace",
            ContactPayload.parse(vCard, AddressBookContactEntity.FORMAT_VCARD)?.formattedName,
        )
        // An unrecognised format reads as vCard rather than as nothing: a row is only ever written
        // with a format this app knows, so an unknown one means a downgrade or a hand-edited
        // database, and refusing outright would blank the address book instead.
        assertEquals("Ada Lovelace", ContactPayload.parse(vCard, "who knows")?.formattedName)
        // Crossing the wires is exactly what the discriminator prevents.
        assertNull(ContactPayload.parse(jsContact, AddressBookContactEntity.FORMAT_VCARD))
    }
}
