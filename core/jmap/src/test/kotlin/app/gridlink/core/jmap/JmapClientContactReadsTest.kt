package app.gridlink.core.jmap

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
 * The JMAP contacts READ surface: listing ids, fetching cards, and the state/changes pair that
 * makes a second sync cheap.
 *
 * Its twin is [JmapClientContactsTest], which covers the writes. They are split because the two
 * halves fail for different reasons: a write breaks on what the server refuses, a read breaks on
 * what this app cannot parse, and a fixture that proves one proves nothing about the other.
 *
 * The card fixtures are shaped the way Stalwart 0.16.15 actually answers, including the parts that
 * are easy to get wrong from the RFC alone: `pref` as a rank rather than a score, a `kind: "org"`
 * card that names no organisation, and a photo converted from a vCard `PHOTO` with no `kind`.
 */
class JmapClientContactReadsTest {
    private lateinit var server: MockWebServer
    private val client = JmapClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun contactsSession() = JmapSession(
        apiUrl = server.url("/jmap/api/").toString(),
        capabilities = mapOf(Jmap.CONTACTS_CAPABILITY to buildJsonObject {}),
    )

    @Test fun queryContactCardIds_readsTheStateAndTotalAndAsksForNoFilter() = runBlocking {
        server.enqueue(MockResponse().setBody(QUERY_PAGE_JSON))

        val page = client.queryContactCardIds(contactsSession(), "d", BasicAuth("u", "p"), limit = 2, position = 4)

        assertEquals(listOf("c1", "c2"), page.ids)
        assertEquals("qs-1", page.queryState)
        assertEquals(9, page.total)

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("ContactCard/query"))
        assertTrue(sent.contains("\"limit\":2"))
        assertTrue(sent.contains("\"position\":4"))
        assertTrue(sent.contains("\"calculateTotal\":true"))
        // 🔴 No filter at all. An unsupported filter fails the METHOD on a server that does not
        // implement it, so listing an account would return nothing rather than everything.
        assertFalse(sent.contains("\"filter\""))
    }

    @Test fun queryContactCardIds_missingTotalIsNullNotZero() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"methodResponses":[["ContactCard/query",{"ids":[]},"cq1"]]}"""))

        // A server that did not count has not said "none": zero here would end a paged listing
        // on its first round.
        assertNull(client.queryContactCardIds(contactsSession(), "d", BasicAuth("u", "p")).total)
    }

    @Test fun getContactCards_noCallForAnEmptyIdList() = runBlocking {
        assertTrue(client.getContactCards(contactsSession(), "d", emptyList(), BasicAuth("u", "p")).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun getContactCards_parsesAPersonWithEveryModelledGroup() = runBlocking {
        server.enqueue(MockResponse().setBody(CARDS_JSON))

        val cards = client.getContactCards(contactsSession(), "d", listOf("c1", "c2"), BasicAuth("u", "p"))

        val person = cards.first()
        assertEquals("urn:uuid:9e3f", person.uid)
        assertEquals("b", person.primaryAddressBookId())
        assertEquals("Ada Lovelace", person.name?.full)
        assertEquals("Lovelace", person.name?.component("surname"))
        assertEquals("Ada", person.name?.component("given"))
        assertEquals("Analytical Engines", person.organizationName())
        assertEquals("Countess", person.titleName())
        assertEquals(listOf("+1 555 0100"), person.phoneNumbers())
        assertEquals("Met at the fair", person.noteText())
        assertEquals(listOf("12 Marylebone Rd, London, NW1"), person.addressLines())

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("ContactCard/get"))
        assertTrue(sent.contains(Jmap.CONTACTS_CAPABILITY))
    }

    @Test fun getContactCards_prefIsARankSoTheLowestNumberComesFirst() = runBlocking {
        server.enqueue(MockResponse().setBody(CARDS_JSON))

        val person = client.getContactCards(contactsSession(), "d", listOf("c1"), BasicAuth("u", "p")).first()

        // 🔴 RFC 9553 §1.5.3: 1 is MOST preferred and an absent pref is least. Sorting the other
        // way would put the address the user actually uses last, and the first is what the app
        // writes to.
        assertEquals(listOf("ada@example.com", "ada@bletchley.example"), person.emailAddresses())
    }

    @Test fun getContactCards_readsAPhotoOnlyWhenItIsInline() = runBlocking {
        server.enqueue(MockResponse().setBody(CARDS_JSON))

        val cards = client.getContactCards(contactsSession(), "d", listOf("c1", "c2"), BasicAuth("u", "p"))

        // `kind` absent counts as a photo: Stalwart omits it on cards converted from a vCard PHOTO.
        val photo = cards.first().photo()
        assertEquals("image/png", photo?.mediaType)
        assertEquals("iVBORw0KGgo=", photo?.base64)
        // A remote URI is nothing this app can show offline and nothing it would re-upload.
        assertNull(cards[1].photo())
    }

    @Test fun getContactCards_readsGridlinkCustomFieldsBackOutOfVCardProps() = runBlocking {
        server.enqueue(MockResponse().setBody(CARDS_JSON))

        val person = client.getContactCards(contactsSession(), "d", listOf("c1"), BasicAuth("u", "p")).first()

        // The exact inverse of the writer, and a foreign jCard entry beside it is skipped rather
        // than guessed at.
        assertEquals(1, person.customFields().size)
        assertEquals("Nickname", person.customFields().first().label)
        assertEquals("Countess of Lovelace", person.customFields().first().value)
    }

    @Test fun getContactCards_anOrgCardThatNamesNoOrganisationStillReadsAsOne() = runBlocking {
        server.enqueue(MockResponse().setBody(CARDS_JSON))

        val company = client.getContactCards(contactsSession(), "d", listOf("c1", "c2"), BasicAuth("u", "p"))[1]

        assertEquals("org", company.kind)
        assertNull(company.organizationName())
        assertEquals("Redoak Foodservice", company.name?.full)
    }

    @Test fun contactCardState_asksForNoCardBodiesAtAll() = runBlocking {
        server.enqueue(MockResponse().setBody(STATE_ONLY_JSON))

        assertEquals("cs-7", client.contactCardState(contactsSession(), "d", BasicAuth("u", "p")))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("ContactCard/get"))
        // Empty ids: the state is the whole point, and downloading the book to read one string off
        // the envelope would make the cheap half of a sync the expensive half.
        assertTrue(body.contains("\"ids\":[]"))
    }

    @Test fun contactCardState_missingStateIsNullSoTheCallerDoesAFullSync() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"methodResponses":[["ContactCard/get",{"list":[]},"cc1"]]}"""))

        // A server that named no state has given no seed, and "" is not one either.
        assertNull(client.contactCardState(contactsSession(), "d", BasicAuth("u", "p")))
    }

    @Test fun contactCardChanges_readsTheChangeSet() = runBlocking {
        server.enqueue(MockResponse().setBody(CHANGES_JSON))

        val result = client.contactCardChanges(contactsSession(), "d", "cs-7", BasicAuth("u", "p"))

        assertTrue(result.calculated)
        assertEquals("cs-8", result.newState)
        assertEquals(listOf("c9"), result.created)
        assertEquals(listOf("c1"), result.updated)
        assertEquals(listOf("c4"), result.destroyed)
        assertFalse(result.hasMoreChanges)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"sinceState\":\"cs-7\""))
    }

    @Test fun contactCardChanges_neverInventsAStartingState() = runBlocking {
        // 🔴 Stalwart rejects a made-up sinceState at the REQUEST level, killing every method in
        // the batch rather than just this one. A blank state must never reach the wire.
        val result = client.contactCardChanges(contactsSession(), "d", null, BasicAuth("u", "p"))

        assertFalse(result.calculated)
        assertEquals(0, server.requestCount)
        assertTrue(client.contactCardChanges(contactsSession(), "d", "  ", BasicAuth("u", "p")).created.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun contactCardChanges_cannotCalculateIsNotAnEmptyChangeSet() = runBlocking {
        server.enqueue(MockResponse().setBody(CANNOT_CALCULATE_JSON))

        val result = client.contactCardChanges(contactsSession(), "d", "stale", BasicAuth("u", "p"))

        // A caller reading this as "nothing changed" would stop syncing and never say why.
        assertFalse(result.calculated)
        assertTrue(result.created.isEmpty())
        assertNull(result.newState)
    }

    private companion object {
        val QUERY_PAGE_JSON = """
            {"methodResponses":[["ContactCard/query",
                {"accountId":"d","ids":["c1","c2"],"queryState":"qs-1","total":9,"position":4},"cq1"]]}
        """.trimIndent()

        val CARDS_JSON = """
            {"methodResponses":[["ContactCard/get",{"accountId":"d","state":"cs-7","list":[
                {"id":"c1","uid":"urn:uuid:9e3f","addressBookIds":{"b":true},
                 "name":{"full":"Ada Lovelace","components":[
                    {"kind":"given","value":"Ada"},{"kind":"surname","value":"Lovelace"}]},
                 "organizations":{"o1":{"name":"Analytical Engines"}},
                 "titles":{"t1":{"name":"Countess"}},
                 "emails":{"e1":{"address":"ada@bletchley.example","pref":2},
                           "e2":{"address":"ada@example.com","pref":1}},
                 "phones":{"p1":{"number":"+1 555 0100"}},
                 "notes":{"n1":{"note":"Met at the fair"}},
                 "addresses":{"a1":{"full":"12 Marylebone Rd\nLondon\nNW1"}},
                 "media":{"m1":{"uri":"data:image/png;base64,iVBORw0KGgo="}},
                 "vCardProps":[
                    ["x-gridlink-field",{},"text",["Nickname","Countess of Lovelace"]],
                    ["x-abrelatednames",{},"text","Babbage"]],
                 "updated":"2026-08-16T11:02:00Z"},
                {"id":"c2","uid":"urn:uuid:aa10","kind":"org","addressBookIds":{"b":true},
                 "name":{"full":"Redoak Foodservice"},
                 "media":{"m1":{"kind":"photo","uri":"https://example.com/logo.png"}}}
            ]},"cc1"]]}
        """.trimIndent()

        val STATE_ONLY_JSON = """
            {"methodResponses":[["ContactCard/get",{"accountId":"d","state":"cs-7","list":[]},"cc1"]]}
        """.trimIndent()

        val CHANGES_JSON = """
            {"methodResponses":[["ContactCard/changes",{"accountId":"d","oldState":"cs-7","newState":"cs-8",
                "created":["c9"],"updated":["c1"],"destroyed":["c4"],"hasMoreChanges":false},"cc1"]]}
        """.trimIndent()

        val CANNOT_CALCULATE_JSON = """
            {"methodResponses":[["error",{"type":"cannotCalculateChanges"},"cc1"]]}
        """.trimIndent()
    }
}
