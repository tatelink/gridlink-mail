package app.gridlink.core.data.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cards copied verbatim out of the live account, not written for the test. Every one of these is a
 * shape the address book will meet on first sync, and three of them break a reader that trusts the
 * fields to be there.
 */
class VCardTest {

    @Test
    fun `an organisation split into a fake first and last name files under its own name`() {
        val card = VCard.parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            PROFILE:VCARD
            MAILER:gromox-oxvcard
            FN:Redoak Foodservice
            N:Foodservice;Redoak;;;
            ORG:Redoak Foodservice;
            CLASS:PUBLIC
            UID:hl-135
            TITLE:Customer Service
            REV;VALUE=DATE-TIME:2026-07-12T19:09:34Z
            END:VCARD
            """.trimIndent(),
        )!!

        assertTrue(card.isOrganization)
        // Trusting N would file this under F for "Foodservice".
        assertEquals("Redoak Foodservice", card.fileAsFamily)
        assertEquals("", card.fileAsGiven)
        assertEquals("Customer Service", card.role)
        // The trailing semicolon in ORG is a structured-value separator, not part of the name.
        assertEquals("Redoak Foodservice", card.organization)
    }

    @Test
    fun `a card with nothing but an address still has a name to file under`() {
        val card = VCard.parse(
            """
            BEGIN:VCARD
            VERSION:4.0
            EMAIL;PROP-ID=email:news-rt1@retailer.example
            UID:urn:uuid:84d159ce-dd9d-44a5-bc92-2b61aca0bb41
            FN;DERIVED=TRUE:
            END:VCARD
            """.trimIndent(),
        )!!

        // 🔴 The address book takes family.first() to pick a section. Blank here is a crash, and all
        // eight cards in the account's "Trusted Senders" book look exactly like this.
        assertEquals("news-rt1", card.fileAsFamily)
        assertEquals("", card.fileAsGiven)
        assertEquals("", card.role)
        assertEquals("news-rt1@retailer.example", card.primaryEmail)
        assertFalse(card.isOrganization)
    }

    @Test
    fun `an ordinary person keeps the surname as written`() {
        val card = VCard.parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Kenna Chadwick
            N:Chadwick;Kenna;;;
            TEL;TYPE=CELL:+1 717-555-0142
            END:VCARD
            """.trimIndent(),
        )!!

        assertEquals("Chadwick", card.fileAsFamily)
        assertEquals("Kenna", card.fileAsGiven)
        assertFalse(card.isOrganization)
        // 79 of the account's 113 cards have no address at all. Empty, not null, not a placeholder.
        assertEquals("", card.primaryEmail)
    }

    @Test
    fun `a person at a company shows the company as the subtitle, an organisation does not repeat itself`() {
        val person = VCard.parse(
            "BEGIN:VCARD\nFN:Brennan Ludlow\nN:Ludlow;Brennan;;;\nORG:Brightmar Regional;\nEND:VCARD",
        )!!
        assertEquals("Brightmar Regional", person.role)

        val company = VCard.parse("BEGIN:VCARD\nFN:Brightmar Regional\nORG:Brightmar Regional;\nEND:VCARD")!!
        // Subtitling "Brightmar Regional" with "Brightmar Regional" says nothing twice.
        assertEquals("", company.role)
    }

    @Test
    fun `a preferred address wins regardless of which spelling of PREF the card uses`() {
        val three = VCard.parse(
            """
            BEGIN:VCARD
            FN:Dara Loxwell
            N:Loxwell;Dara;;;
            EMAIL;TYPE=WORK:work@gridlink.me
            EMAIL;TYPE=HOME,PREF:home@gridlink.me
            END:VCARD
            """.trimIndent(),
        )!!
        assertEquals("home@gridlink.me", three.primaryEmail)

        val four = VCard.parse(
            "BEGIN:VCARD\nFN:D L\nEMAIL:a@x.me\nEMAIL;PREF=1:b@x.me\nEND:VCARD",
        )!!
        assertEquals("b@x.me", four.primaryEmail)
    }

    @Test
    fun `an escaped semicolon stays inside the surname`() {
        val card = VCard.parse("BEGIN:VCARD\nFN:Sean O;Brien\nN:O\\;Brien;Sean;;;\nEND:VCARD")!!
        assertEquals("O;Brien", card.fileAsFamily)
        assertEquals("Sean", card.fileAsGiven)
    }

    @Test
    fun `a card with no END is still a card`() {
        // Losing a whole contact over a missing terminator is a worse failure than tolerating one.
        val card = VCard.parse("BEGIN:VCARD\nFN:Rhea Gorman\nN:Gorman;Rhea;;;")
        assertNotNull(card)
        assertEquals("Gorman", card!!.fileAsFamily)
    }

    @Test
    fun `an empty card is not a contact`() {
        assertEquals(emptyList<ParsedContact>(), VCard.parseAll("BEGIN:VCARD\nVERSION:3.0\nEND:VCARD"))
        assertEquals(emptyList<ParsedContact>(), VCard.parseAll(null))
    }

    // ------------------------------------------------------------------------------------------
    // Photos
    // ------------------------------------------------------------------------------------------

    /** Valid base64 of "GIF89a" — a plausible few image bytes, decodable by any Base64 reader. */
    private val tinyB64 = "R0lGODlh"

    @Test
    fun `a v4 data-URI photo parses to its media type and payload`() {
        val card = VCard.parse(
            "BEGIN:VCARD\nVERSION:4.0\nFN:Kenna Chadwick\nPHOTO:data:image/png;base64,$tinyB64\nEND:VCARD",
        )!!
        assertEquals("image/png", card.photo!!.mediaType)
        assertEquals(tinyB64, card.photo!!.base64)
    }

    @Test
    fun `a v3 ENCODING=b photo parses, folded across lines and all`() {
        val card = VCard.parse(
            "BEGIN:VCARD\nVERSION:3.0\nFN:Kenna Chadwick\nPHOTO;ENCODING=b;TYPE=JPEG:R0lG\n ODlh\nEND:VCARD",
        )!!
        // TYPE=JPEG (a bare subtype) becomes a full media type; the fold's whitespace is gone.
        assertEquals("image/jpeg", card.photo!!.mediaType)
        assertEquals(tinyB64, card.photo!!.base64)
    }

    @Test
    fun `a remote photo URL is not an inline photo`() {
        // The model deliberately holds only embedded photos: fetching URLs is a network concern
        // the parser must not smuggle in. The card still parses; only the photo reads absent.
        val card = VCard.parse(
            "BEGIN:VCARD\nVERSION:4.0\nFN:K C\nPHOTO;MEDIATYPE=image/jpeg:https://x.example/a.jpg\nEND:VCARD",
        )!!
        assertNotNull(card)
        assertEquals(null, card.photo)
    }

    @Test
    fun `a corrupt photo reads absent rather than sinking the card`() {
        val card = VCard.parse(
            "BEGIN:VCARD\nVERSION:4.0\nFN:Kenna Chadwick\nN:Chadwick;Kenna;;;\nPHOTO:data:image/jpeg;base64,@@not-base64@@\nEND:VCARD",
        )!!
        assertEquals(null, card.photo)
        assertEquals("Chadwick", card.fileAsFamily)
    }

    // ------------------------------------------------------------------------------------------
    // Custom fields
    // ------------------------------------------------------------------------------------------

    @Test
    fun `custom fields parse in order with their escapes undone`() {
        val card = VCard.parse(
            """
            BEGIN:VCARD
            VERSION:4.0
            FN:Kenna Chadwick
            X-GRIDLINK-FIELD:Office;Highgate\, Suite 240
            X-GRIDLINK-FIELD:Case portal ID;HRB-4417
            END:VCARD
            """.trimIndent(),
        )!!
        assertEquals(2, card.customFields.size)
        assertEquals("Office", card.customFields[0].label)
        assertEquals("Highgate, Suite 240", card.customFields[0].value)
        assertEquals("Case portal ID", card.customFields[1].label)
        assertEquals("HRB-4417", card.customFields[1].value)
    }

    @Test
    fun `a custom field with only one half present is kept, a fully blank one is not`() {
        val card = VCard.parse(
            "BEGIN:VCARD\nVERSION:4.0\nFN:K C\nX-GRIDLINK-FIELD:Birthday;\nX-GRIDLINK-FIELD:;\nEND:VCARD",
        )!!
        assertEquals(1, card.customFields.size)
        assertEquals("Birthday", card.customFields[0].label)
        assertEquals("", card.customFields[0].value)
    }

    // ------------------------------------------------------------------------------------------
    // Grouped properties and addresses
    // ------------------------------------------------------------------------------------------

    @Test
    fun `an Apple-grouped card gives up its emails, phone, photo and address`() {
        // The `itemN.` spelling iOS and macOS exporters write. Before the group prefix was
        // stripped in ContentLines.parse, every one of these lines was invisible: the card
        // "synced" but showed no email, no phone, no photo — Tate's exact report.
        val card = VCard.parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Dara Loxwell
            N:Loxwell;Dara;;;
            item1.EMAIL;type=INTERNET;type=pref:d.loxwell@gridlink.me
            item2.EMAIL;type=INTERNET:dara@example.com
            item3.TEL;type=CELL:+1 717-555-0114
            item4.ADR;type=WORK:;;9200 Northfield Blvd;Ashvale;PA;17110;USA
            item5.PHOTO;ENCODING=b;TYPE=JPEG:$tinyB64
            item1.X-ABLabel:_${'$'}!<Work>!${'$'}_
            END:VCARD
            """.trimIndent(),
        )!!

        assertEquals(listOf("d.loxwell@gridlink.me", "dara@example.com"), card.emails)
        assertEquals(listOf("+1 717-555-0114"), card.phones)
        assertEquals("image/jpeg", card.photo!!.mediaType)
        assertEquals(listOf("9200 Northfield Blvd, Ashvale, PA 17110, USA"), card.addresses)
    }

    @Test
    fun `an address flattens to one line in envelope order`() {
        val card = VCard.parse(
            """
            BEGIN:VCARD
            VERSION:4.0
            FN:HR Benefits Group
            ADR;TYPE=WORK:;Suite 240;11220 Aspen Ln;Fairhaven;PA;17025;USA
            END:VCARD
            """.trimIndent(),
        )!!
        // Extended (the suite) rides with the street, then city, then region and postal as one
        // piece the way an envelope reads.
        assertEquals(listOf("Suite 240, 11220 Aspen Ln, Fairhaven, PA 17025, USA"), card.addresses)
    }

    @Test
    fun `a multi-line street becomes comma pieces and a blank ADR is not an address`() {
        val card = VCard.parse(
            "BEGIN:VCARD\nVERSION:3.0\nFN:K C\nADR:;;123 Main St\\nSuite 4;Hillcrest;PA;;\nADR:;;;;;;\nEND:VCARD",
        )!!
        assertEquals(listOf("123 Main St, Suite 4, Hillcrest, PA"), card.addresses)
    }
}
