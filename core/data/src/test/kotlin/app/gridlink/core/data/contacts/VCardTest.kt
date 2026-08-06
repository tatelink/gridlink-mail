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
}
