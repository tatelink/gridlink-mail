package app.jmail.core.jmap

import app.jmail.core.jmap.model.JmapSession
import app.jmail.core.jmap.model.Quota
import app.jmail.core.jmap.model.VacationResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class JmapClientTest {
    private lateinit var server: MockWebServer
    private val client = JmapClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun fetchSession_parsesAccountAndSendsBasicAuth() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(SESSION_JSON),
        )

        val session = client.fetchSession(
            server.url("/.well-known/jmap").toString(),
            BasicAuth("admin@masto.top", "secret"),
        )

        assertEquals("https://mail.example.com/jmap/api/", session.apiUrl)
        assertEquals("acc1", session.mailAccountId())
        assertEquals("admin@masto.top", session.accounts["acc1"]?.name)

        val expected = "Basic " + Base64.getEncoder()
            .encodeToString("admin@masto.top:secret".toByteArray())
        assertEquals(expected, server.takeRequest().getHeader("Authorization"))
    }

    @Test fun getMailboxes_parsesList() = runBlocking {
        server.enqueue(MockResponse().setBody(MAILBOX_JSON))

        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        val mailboxes = client.getMailboxes(session, "acc1", BasicAuth("u", "p"))

        assertEquals(2, mailboxes.size)
        assertEquals("Inbox", mailboxes[0].name)
        assertEquals(3, mailboxes[0].unreadEmails)
        assertEquals("sent", mailboxes[1].role)

        // The request should be a well-formed JMAP method call.
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("Mailbox/get"))
        assertTrue(sent.contains("urn:ietf:params:jmap:mail"))
    }

    @Test fun getEmail_coercesNullAddressFields() = runBlocking {
        server.enqueue(MockResponse().setBody(EMAIL_GET_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        val email = client.getEmail(session, "acc1", "e1", BasicAuth("u", "p"))

        assertEquals("Hello", email.subject)
        assertEquals(emptyList<Any>(), email.cc) // cc was JSON null
        assertEquals("alice@example.com", email.from.first().email)
        assertEquals("<p>Hi</p>", email.htmlContent())
    }

    @Test fun getMailboxes_throwsOnJmapError() {
        server.enqueue(MockResponse().setBody(ERROR_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        try {
            runBlocking { client.getMailboxes(session, "acc1", BasicAuth("u", "p")) }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertTrue(e.message!!.contains("accountNotFound"))
        }
    }

    @Test fun getVacationResponse_parsesSingleton() = runBlocking {
        server.enqueue(MockResponse().setBody(VACATION_GET_JSON))
        val vr = client.getVacationResponse(vacationSession(), "acc1", BasicAuth("u", "p"))

        assertEquals("singleton", vr?.id)
        assertEquals(true, vr?.isEnabled)
        assertEquals("Away", vr?.subject)
        assertEquals("Back Monday", vr?.textBody)

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("VacationResponse/get"))
        assertTrue(sent.contains("urn:ietf:params:jmap:vacationresponse"))
    }

    @Test fun getVacationResponse_nullAndNoCallWhenCapabilityAbsent() = runBlocking {
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        val vr = client.getVacationResponse(session, "acc1", BasicAuth("u", "p"))

        assertNull(vr)
        assertEquals(0, server.requestCount) // capability gate skips the network entirely
    }

    @Test fun setVacationResponse_sendsFieldsAndSucceeds() = runBlocking {
        server.enqueue(MockResponse().setBody(VACATION_SET_JSON))
        val vacation = VacationResponse(
            isEnabled = true,
            subject = "Away",
            textBody = "Back Monday",
            fromDate = "2026-06-23T00:00:00Z",
            toDate = "2026-06-30T23:59:59Z",
        )
        val result = client.setVacationResponse(vacationSession(), "acc1", BasicAuth("u", "p"), vacation)

        assertEquals("singleton", result.id)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("VacationResponse/set"))
        assertTrue(sent.contains("\"isEnabled\":true"))
        assertTrue(sent.contains("2026-06-23T00:00:00Z"))
    }

    @Test fun setVacationResponse_throwsOnNotUpdated() {
        server.enqueue(MockResponse().setBody(VACATION_NOTUPDATED_JSON))
        try {
            runBlocking {
                client.setVacationResponse(vacationSession(), "acc1", BasicAuth("u", "p"), VacationResponse())
            }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertTrue(e.message!!.contains("forbidden"))
        }
    }

    private fun vacationSession() = JmapSession(
        apiUrl = server.url("/jmap/api/").toString(),
        capabilities = mapOf(Jmap.VACATION_CAPABILITY to buildJsonObject {}),
    )

    @Test fun getQuotas_parsesList() = runBlocking {
        server.enqueue(MockResponse().setBody(QUOTA_GET_JSON))
        val session = JmapSession(
            apiUrl = server.url("/jmap/api/").toString(),
            capabilities = mapOf(Jmap.QUOTA_CAPABILITY to buildJsonObject {}),
        )
        val quotas = client.getQuotas(session, "acc1", BasicAuth("u", "p"))

        assertEquals(2, quotas.size)
        assertEquals("octets", quotas[0].resourceType)
        assertEquals(1048576L, quotas[0].used)
        assertEquals(10485760L, quotas[0].limit)
        assertEquals("count", quotas[1].resourceType)

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("Quota/get"))
        assertTrue(sent.contains("urn:ietf:params:jmap:quota"))
    }

    @Test fun getQuotas_emptyAndNoCallWhenCapabilityAbsent() = runBlocking {
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        val quotas = client.getQuotas(session, "acc1", BasicAuth("u", "p"))

        assertTrue(quotas.isEmpty())
        assertEquals(0, server.requestCount) // capability gate skips the network
    }

    private companion object {
        const val SESSION_JSON = """
            {
              "capabilities": {
                "urn:ietf:params:jmap:core": {},
                "urn:ietf:params:jmap:mail": {}
              },
              "accounts": {
                "acc1": { "name": "admin@masto.top", "isPersonal": true, "isReadOnly": false }
              },
              "primaryAccounts": { "urn:ietf:params:jmap:mail": "acc1" },
              "username": "admin@masto.top",
              "apiUrl": "https://mail.example.com/jmap/api/",
              "downloadUrl": "https://mail.example.com/jmap/download/",
              "state": "s1"
            }
        """

        const val MAILBOX_JSON = """
            {
              "methodResponses": [
                ["Mailbox/get", {
                  "accountId": "acc1",
                  "state": "m1",
                  "notFound": [],
                  "list": [
                    {"id":"mb1","name":"Inbox","role":"inbox","sortOrder":1,"totalEmails":10,"unreadEmails":3},
                    {"id":"mb2","name":"Sent","role":"sent","sortOrder":2,"totalEmails":5,"unreadEmails":0}
                  ]
                }, "c0"]
              ],
              "sessionState": "abc"
            }
        """

        const val EMAIL_GET_JSON = """
            {
              "methodResponses": [
                ["Email/get", {
                  "accountId": "acc1",
                  "state": "e1",
                  "notFound": [],
                  "list": [
                    {
                      "id": "e1",
                      "subject": "Hello",
                      "from": [{"name":"Alice","email":"alice@example.com"}],
                      "to": null,
                      "cc": null,
                      "keywords": {"${'$'}seen": true},
                      "htmlBody": [{"partId":"1","type":"text/html"}],
                      "bodyValues": {"1": {"value":"<p>Hi</p>","isTruncated":false}}
                    }
                  ]
                }, "g0"]
              ],
              "sessionState": "abc"
            }
        """

        const val ERROR_JSON = """
            {
              "methodResponses": [
                ["error", {"type":"accountNotFound"}, "c0"]
              ],
              "sessionState": "abc"
            }
        """

        const val VACATION_GET_JSON = """
            {
              "methodResponses": [
                ["VacationResponse/get", {
                  "accountId": "acc1",
                  "state": "v1",
                  "notFound": [],
                  "list": [
                    {"id":"singleton","isEnabled":true,"subject":"Away","textBody":"Back Monday","fromDate":null,"toDate":null}
                  ]
                }, "v0"]
              ],
              "sessionState": "abc"
            }
        """

        const val VACATION_SET_JSON = """
            {
              "methodResponses": [
                ["VacationResponse/set", {"accountId":"acc1","newState":"v2","updated":{"singleton":null}}, "v0"]
              ],
              "sessionState": "abc"
            }
        """

        const val VACATION_NOTUPDATED_JSON = """
            {
              "methodResponses": [
                ["VacationResponse/set", {"accountId":"acc1","notUpdated":{"singleton":{"type":"forbidden"}}}, "v0"]
              ],
              "sessionState": "abc"
            }
        """

        const val QUOTA_GET_JSON = """
            {
              "methodResponses": [
                ["Quota/get", {
                  "accountId": "acc1",
                  "state": "q1",
                  "notFound": [],
                  "list": [
                    {"id":"q-storage","resourceType":"octets","used":1048576,"limit":10485760,"scope":"account","name":"Storage","types":["Mail"]},
                    {"id":"q-count","resourceType":"count","used":42,"limit":1000,"scope":"account","name":"Messages","types":["Mail"]}
                  ]
                }, "q0"]
              ],
              "sessionState": "abc"
            }
        """
    }
}
