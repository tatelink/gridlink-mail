package app.gridlink.core.jmap

import app.gridlink.core.jmap.model.ContactCardGroup
import app.gridlink.core.jmap.model.ContactCardWrite
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
 * The JMAP contacts surface (RFC 9610): capability gating, the UID→id bridge, and the two writes.
 * The update test pins the property this whole path exists for — a patch names ONLY the touched
 * groups, so everything the model does not know survives on the server.
 */
class JmapClientContactsTest {
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

    @Test fun supportsContacts_readsBothTheSessionAndTheAccountLevel() {
        assertTrue(client.supportsContacts(contactsSession()))
        // Stalwart advertises per-account: session capabilities sparse, account rich.
        assertTrue(
            client.supportsContacts(
                JmapSession(
                    apiUrl = "https://mail.example.com/jmap/api/",
                    accounts = mapOf(
                        "d" to JmapAccount(
                            name = "tate",
                            accountCapabilities = mapOf(Jmap.CONTACTS_CAPABILITY to buildJsonObject {}),
                        ),
                    ),
                ),
            ),
        )
        assertFalse(client.supportsContacts(JmapSession(apiUrl = "https://mail.example.com/jmap/api/")))
    }

    @Test fun getAddressBooks_emptyAndNoCallWhenCapabilityAbsent() = runBlocking {
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        assertTrue(client.getAddressBooks(session, "d", BasicAuth("u", "p")).isEmpty())
        assertEquals(0, server.requestCount) // capability gate skips the network
    }

    @Test fun getAddressBooks_parsesList() = runBlocking {
        server.enqueue(MockResponse().setBody(ADDRESS_BOOKS_JSON))

        val books = client.getAddressBooks(contactsSession(), "d", BasicAuth("u", "p"))

        assertEquals(2, books.size)
        assertEquals("Contacts", books[0].name)
        assertTrue(books[0].isDefault)
        assertEquals("Trusted Senders", books[1].name)

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("AddressBook/get"))
        assertTrue(sent.contains(Jmap.CONTACTS_CAPABILITY))
    }

    @Test fun queryContactCardId_returnsTheFirstMatchOrNull() = runBlocking {
        server.enqueue(MockResponse().setBody(QUERY_ONE_JSON))
        assertEquals(
            "card9",
            client.queryContactCardId(contactsSession(), "d", "urn:uuid:abc", BasicAuth("u", "p")),
        )
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("ContactCard/query"))
        assertTrue(sent.contains("urn:uuid:abc"))

        server.enqueue(MockResponse().setBody(QUERY_NONE_JSON))
        assertNull(client.queryContactCardId(contactsSession(), "d", "urn:uuid:missing", BasicAuth("u", "p")))
    }

    @Test fun createContactCard_sendsTheCardAndReturnsTheServerId() = runBlocking {
        server.enqueue(MockResponse().setBody(CREATE_JSON))

        val id = client.createContactCard(
            contactsSession(),
            accountId = "d",
            addressBookId = "b",
            card = ContactCardWrite(
                uid = "u-1",
                fullName = "Kenna Chadwick",
                given = "Kenna",
                family = "Chadwick",
                emails = listOf("k@acme.example"),
            ),
            auth = BasicAuth("u", "p"),
        )

        assertEquals("card1", id)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("ContactCard/set"))
        assertTrue(sent.contains("\"uid\":\"u-1\""))
        assertTrue(sent.contains("\"addressBookIds\":{\"b\":true}"))
        assertTrue(sent.contains("Kenna Chadwick"))
        // Empty groups are OMITTED on create, not sent as null.
        assertFalse(sent.contains("\"notes\""))
        assertFalse(sent.contains("\"phones\""))
    }

    @Test fun createContactCard_throwsWithTheServersReason() {
        server.enqueue(MockResponse().setBody(CREATE_REFUSED_JSON))
        try {
            runBlocking {
                client.createContactCard(
                    contactsSession(), "d", "b",
                    ContactCardWrite(uid = "u-1", fullName = "X"),
                    BasicAuth("u", "p"),
                )
            }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertTrue(e.message!!.contains("over quota"))
        }
    }

    @Test fun updateContactCard_patchesOnlyTheTouchedGroups() = runBlocking {
        server.enqueue(MockResponse().setBody(UPDATE_JSON))

        client.updateContactCard(
            contactsSession(),
            accountId = "d",
            cardId = "card9",
            card = ContactCardWrite(
                uid = "u-1",
                fullName = "Kenna Chadwick",
                given = "Kenna",
                family = "Chadwick",
                title = "Dispatcher",
                emails = listOf("k@acme.example"),
            ),
            touched = setOf(ContactCardGroup.TITLE),
            auth = BasicAuth("u", "p"),
        )

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"update\":{\"card9\""))
        assertTrue(sent.contains("\"titles\""))
        assertTrue(sent.contains("Dispatcher"))
        // 🔴 The whole point of the JMAP path: untouched groups are never mentioned, so the
        // photo, addresses and labels this model does not carry survive the edit. ("full" is the
        // NAME group's marker; a bare "name" also appears INSIDE a JSContact Title, so it can't
        // be the sentinel here.)
        assertFalse(sent.contains("\"full\""))
        assertFalse(sent.contains("\"emails\""))
        assertFalse(sent.contains("Chadwick"))
    }

    @Test fun updateContactCard_saysACLearedGroupOutLoudAsNull() = runBlocking {
        server.enqueue(MockResponse().setBody(UPDATE_JSON))

        client.updateContactCard(
            contactsSession(), "d", "card9",
            ContactCardWrite(uid = "u-1", fullName = "Kenna Chadwick", note = null),
            touched = setOf(ContactCardGroup.NOTE),
            auth = BasicAuth("u", "p"),
        )

        // In patch language an absent property means "keep"; removal has to be said as null.
        assertTrue(server.takeRequest().body.readUtf8().contains("\"notes\":null"))
    }

    @Test fun updateContactCard_noCallWhenNothingWasTouched() = runBlocking {
        client.updateContactCard(
            contactsSession(), "d", "card9",
            ContactCardWrite(uid = "u-1", fullName = "Kenna Chadwick"),
            touched = emptySet(),
            auth = BasicAuth("u", "p"),
        )
        assertEquals(0, server.requestCount) // the no-op save is a no-op on the wire
    }

    @Test fun updateContactCard_throwsWhenTheServerDidNotUpdate() {
        server.enqueue(MockResponse().setBody(UPDATE_REFUSED_JSON))
        try {
            runBlocking {
                client.updateContactCard(
                    contactsSession(), "d", "card9",
                    ContactCardWrite(uid = "u-1", fullName = "X"),
                    touched = setOf(ContactCardGroup.NAME),
                    auth = BasicAuth("u", "p"),
                )
            }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertTrue(e.message!!.contains("forbidden"))
        }
    }

    private companion object {
        val ADDRESS_BOOKS_JSON = """
            {"methodResponses":[["AddressBook/get",{"accountId":"d","list":[
                {"id":"b","name":"Contacts","isDefault":true},
                {"id":"c","name":"Trusted Senders","isDefault":false}
            ]},"ab0"]]}
        """.trimIndent()

        val QUERY_ONE_JSON = """
            {"methodResponses":[["ContactCard/query",{"accountId":"d","ids":["card9"]},"cq0"]]}
        """.trimIndent()

        val QUERY_NONE_JSON = """
            {"methodResponses":[["ContactCard/query",{"accountId":"d","ids":[]},"cq0"]]}
        """.trimIndent()

        val CREATE_JSON = """
            {"methodResponses":[["ContactCard/set",{"accountId":"d","created":{"new":{"id":"card1"}}},"cs0"]]}
        """.trimIndent()

        val CREATE_REFUSED_JSON = """
            {"methodResponses":[["ContactCard/set",{"accountId":"d","created":null,
                "notCreated":{"new":{"type":"overQuota","description":"over quota"}}},"cs0"]]}
        """.trimIndent()

        val UPDATE_JSON = """
            {"methodResponses":[["ContactCard/set",{"accountId":"d","updated":{"card9":null}},"cs0"]]}
        """.trimIndent()

        val UPDATE_REFUSED_JSON = """
            {"methodResponses":[["ContactCard/set",{"accountId":"d","updated":null,
                "notUpdated":{"card9":{"type":"forbidden"}}},"cs0"]]}
        """.trimIndent()
    }
}
