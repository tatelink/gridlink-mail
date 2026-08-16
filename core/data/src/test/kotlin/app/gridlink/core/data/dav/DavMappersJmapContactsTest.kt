package app.gridlink.core.data.dav

import app.gridlink.core.data.contacts.ContactPayload
import app.gridlink.core.data.db.AddressBookContactEntity
import app.gridlink.core.jmap.model.JmapAddressBook
import app.gridlink.core.jmap.model.JmapCardEmail
import app.gridlink.core.jmap.model.JmapCardName
import app.gridlink.core.jmap.model.JmapCardNameComponent
import app.gridlink.core.jmap.model.JmapContactCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JMAP cards landing in the same table the CardDAV path fills, and coming back out again.
 *
 * The contract is the calendar's: nothing above the cache can tell which protocol wrote a row. Both
 * produce [AddressBookContactEntity], both read back as `ParsedContact`, and the only difference is
 * which parser [ContactPayload] picks.
 */
class DavMappersJmapContactsTest {

    private val ada = JmapContactCard(
        id = "C1",
        uid = "urn:uuid:9e3f",
        addressBookIds = mapOf("b" to true),
        name = JmapCardName(
            full = "Ada Lovelace",
            components = listOf(
                JmapCardNameComponent(kind = "given", value = "Ada"),
                JmapCardNameComponent(kind = "surname", value = "Lovelace"),
            ),
        ),
        emails = mapOf("e1" to JmapCardEmail(address = "ada@example.com", pref = 1)),
        updated = "2026-08-16T11:02:00Z",
    )

    @Test fun buildsARowWithASyntheticKeyThatCannotCollideWithADavHref() {
        val row = DavMappers.jmapContact("acct", "b", ada)

        assertEquals("jmap:card/C1", row.href)
        assertEquals("jmap:addressbook/b", row.collectionUrl)
        // A DAV href is a decoded path and always starts with '/', so the two spaces never meet.
        assertTrue(!row.href.startsWith("/"))
        assertEquals(AddressBookContactEntity.FORMAT_JSCONTACT, row.payloadFormat)
        assertEquals("urn:uuid:9e3f", row.uid)
        assertEquals("Ada Lovelace", row.displayName)
        assertEquals("Lovelace", row.fileAsFamily)
        assertEquals("Ada", row.fileAsGiven)
        assertEquals("ada@example.com", row.primaryEmail)
    }

    @Test fun theEtagStandsInForSomethingJmapDoesNotHave() {
        val row = DavMappers.jmapContact("acct", "b", ada)
        assertEquals("2026-08-16T11:02:00Z", row.etag)

        val edited = DavMappers.jmapContact("acct", "b", ada.copy(updated = "2026-08-17T09:00:00Z"))

        // 🔴 The system-contacts mirror decides what changed by fingerprinting this. A constant
        // would hide every edit from it; a value that moved on its own would rewrite the phone's
        // address book on every sync.
        assertTrue(row.etag != edited.etag)
        assertEquals(row.etag, DavMappers.jmapContact("acct", "b", ada).etag)

        // A card the server dated not at all still needs a stable stand-in, and the id is one.
        assertEquals("C1", DavMappers.jmapContact("acct", "b", ada.copy(updated = null)).etag)
    }

    @Test fun aCardWithNoUidFallsBackToItsOwnKeyRatherThanBlank() {
        // The uid column is not the sync identity (the href is), but it is what the write path
        // hands back to the server as the card's UID, so an empty one must never be stored.
        val row = DavMappers.jmapContact("acct", "b", ada.copy(uid = ""))

        assertEquals("jmap:card/C1", row.uid)
    }

    @Test fun readingBackPicksTheParserTheRowNames() {
        val row = DavMappers.jmapContact("acct", "b", ada)

        val parsed = ContactPayload.parse(row)

        assertNotNull(parsed)
        assertEquals("Ada Lovelace", parsed!!.formattedName)
        assertEquals("ada@example.com", parsed.primaryEmail)
    }

    @Test fun aJscontactRowReadAsVcardWouldBeTheBugTheDiscriminatorPrevents() {
        val row = DavMappers.jmapContact("acct", "b", ada)

        // Mislabelled, the JSON goes through the vCard reader, which finds no BEGIN:VCARD. The
        // contact still appears, because the columns are the fallback, but the edit form seeds
        // from this parse: a null here is what would turn opening the card and saving it untouched
        // into a phantom edit. That is why the format is a stored column, not a guess at the text.
        assertNull(ContactPayload.parse(row.copy(payloadFormat = AddressBookContactEntity.FORMAT_VCARD)))
    }

    @Test fun anUnknownFormatFallsBackRatherThanRefusing() {
        val vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada Lovelace\r\nEND:VCARD\r\n"
        val row = DavMappers.jmapContact("acct", "b", ada)
            .copy(raw = vcard, payloadFormat = "some-format-from-a-newer-build")

        assertEquals("Ada Lovelace", ContactPayload.parse(row)?.formattedName)
    }

    @Test fun theBookRowNeverCarriesASyncTokenOfItsOwn() {
        val book = DavMappers.jmapBook("acct", JmapAddressBook(id = "b", name = "Contacts"), order = 0)

        assertEquals("jmap:addressbook/b", book.url)
        assertEquals("Contacts", book.displayName)
        // Only the sync itself may write a token, exactly as on the discovery path: a token carried
        // over from a listing would claim a delta this app never actually took.
        assertNull(book.syncToken)
    }
}
