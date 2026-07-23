package app.sterna.core.jmap

import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.Quota
import app.sterna.core.jmap.model.VacationResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

    @Test fun fetchSession_reAuthenticatesAfterCrossHostRedirect() = runBlocking {
        // OkHttp strips the Authorization header when a redirect crosses hosts (here: two mock
        // servers on different ports), which is exactly the Fastmail autodiscovery shape —
        // well-known redirecting to api.fastmail.com/jmap/session (issue #54). fetchSession
        // must retry the redirect target once, re-authenticated.
        val target = MockWebServer()
        target.start()
        try {
            target.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.getHeader("Authorization") == "Bearer fmu1-token") {
                        MockResponse().setHeader("Content-Type", "application/json").setBody(SESSION_JSON)
                    } else {
                        MockResponse().setResponseCode(401)
                    }
            }
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", target.url("/jmap/session").toString()),
            )

            val session = client.fetchSession(
                server.url("/.well-known/jmap").toString(),
                BearerAuth("fmu1-token"),
            )

            assertEquals("acc1", session.mailAccountId())
            // First hit arrives header-less (OkHttp dropped it), the retry authenticates.
            assertEquals(2, target.requestCount)
        } finally {
            target.shutdown()
        }
    }

    // The scheme-upgrade is exercised directly (a real https MockWebServer would need an
    // extra TLS test dependency we deliberately don't add). The no-upgrade-over-http case is
    // also covered end-to-end through fetchSession against the plaintext mock server below.

    @Test fun upgradeSessionUrls_upgradesHttpUrlsWhenFetchedOverHttps() {
        val raw = JmapSession(
            apiUrl = "http://mail.example.com/jmap/api/",
            downloadUrl = "http://mail.example.com/jmap/download/",
            uploadUrl = "http://mail.example.com/jmap/upload/",
            eventSourceUrl = "http://mail.example.com/jmap/eventsource/",
        )

        val upgraded = JmapClient.upgradeSessionUrls(raw, "https://mail.example.com/.well-known/jmap")

        assertEquals("https://mail.example.com/jmap/api/", upgraded.apiUrl)
        assertEquals("https://mail.example.com/jmap/download/", upgraded.downloadUrl)
        assertEquals("https://mail.example.com/jmap/upload/", upgraded.uploadUrl)
        assertEquals("https://mail.example.com/jmap/eventsource/", upgraded.eventSourceUrl)
    }

    @Test fun upgradeSessionUrls_leavesHttpsUrlsUntouched() {
        val raw = JmapSession(
            apiUrl = "https://mail.example.com/jmap/api/",
            downloadUrl = "https://mail.example.com/jmap/download/",
        )

        val upgraded = JmapClient.upgradeSessionUrls(raw, "https://mail.example.com/.well-known/jmap")

        // Already-https URLs are not double-rewritten or mangled.
        assertEquals("https://mail.example.com/jmap/api/", upgraded.apiUrl)
        assertEquals("https://mail.example.com/jmap/download/", upgraded.downloadUrl)
    }

    @Test fun upgradeSessionUrls_keepsNullOptionalUrlsNull() {
        val raw = JmapSession(apiUrl = "http://mail.example.com/jmap/api/")

        val upgraded = JmapClient.upgradeSessionUrls(raw, "https://mail.example.com/.well-known/jmap")

        // Optional URLs that were absent stay null, not "https://null".
        assertEquals("https://mail.example.com/jmap/api/", upgraded.apiUrl)
        assertNull(upgraded.downloadUrl)
        assertNull(upgraded.uploadUrl)
        assertNull(upgraded.eventSourceUrl)
    }

    @Test fun upgradeSessionUrls_doesNotUpgradeWhenFetchedOverHttp() {
        val raw = JmapSession(
            apiUrl = "http://mail.example.com/jmap/api/",
            downloadUrl = "http://mail.example.com/jmap/download/",
        )

        // A session reached over a plain-http sessionUrl is left as-is (impossible in the
        // https-only autodiscovery flow, but guards the upgrade behind a real TLS fetch).
        val unchanged = JmapClient.upgradeSessionUrls(raw, "http://mail.example.com/.well-known/jmap")

        assertEquals("http://mail.example.com/jmap/api/", unchanged.apiUrl)
        assertEquals("http://mail.example.com/jmap/download/", unchanged.downloadUrl)
    }

    @Test fun fetchSession_doesNotUpgradeWhenFetchedOverHttp() = runBlocking {
        server.enqueue(MockResponse().setBody(HTTP_URLS_SESSION_JSON))

        // End-to-end: the mock server speaks http, so the http:// session URLs survive.
        val session = client.fetchSession(
            server.url("/.well-known/jmap").toString(),
            BasicAuth("u", "p"),
        )

        assertEquals("http://mail.example.com/jmap/api/", session.apiUrl)
        assertEquals("http://mail.example.com/jmap/download/", session.downloadUrl)
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

    // --- saveDraft (#63: reopen/edit/replace saved drafts) ---

    @Test fun saveDraft_sendsThreadingHeadersAndReturnsCreatedId() = runBlocking {
        server.enqueue(MockResponse().setBody(DRAFT_CREATED_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        val id = client.saveDraft(
            session = session,
            accountId = "acc1",
            auth = BasicAuth("u", "p"),
            from = EmailAddress(name = "Alex", email = "alex@example.com"),
            to = listOf(EmailAddress(email = "someone@example.com")),
            subject = "Draft subject",
            textBody = "draft body",
            draftMailboxId = "mbDrafts",
            inReplyTo = listOf("<mid1@example.com>"),
            references = listOf("<mid0@example.com>", "<mid1@example.com>"),
        )

        assertEquals("d123", id)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"Email/set\""))
        // A reply draft keeps its threading headers, so sending it later joins the conversation.
        assertTrue(sent.contains("\"inReplyTo\":[\"<mid1@example.com>\"]"))
        assertTrue(sent.contains("\"references\":[\"<mid0@example.com>\",\"<mid1@example.com>\"]"))
        // Filed as a seen draft in the Drafts mailbox.
        assertTrue(sent.contains("\"\$draft\":true"))
        assertTrue(sent.contains("\"mbDrafts\":true"))
    }

    @Test fun saveDraft_referencesUploadedAttachmentBlobs() = runBlocking {
        // Re-saving an edited draft must carry its files: without the attachments array the new
        // draft is empty and the old one is destroyed, losing them for good (#63).
        server.enqueue(MockResponse().setBody(DRAFT_CREATED_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        client.saveDraft(
            session = session, accountId = "acc1", auth = BasicAuth("u", "p"),
            from = EmailAddress(email = "alex@example.com"),
            to = listOf(EmailAddress(email = "someone@example.com")),
            subject = "s", textBody = "b", draftMailboxId = "mbDrafts",
            attachments = listOf(
                EmailBodyPart(blobId = "blob1", type = "application/pdf", name = "report.pdf", size = 42),
                // A part with no blobId can't be referenced and must simply not be emitted.
                EmailBodyPart(partId = "/tmp/staged", type = "image/png", name = "local.png"),
            ),
        )

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"blobId\":\"blob1\""))
        assertTrue(sent.contains("\"name\":\"report.pdf\""))
        assertTrue(sent.contains("\"disposition\":\"attachment\""))
        assertTrue(!sent.contains("local.png"))
    }

    @Test fun saveDraft_omitsTheAttachmentsArrayWhenThereAreNone() = runBlocking {
        server.enqueue(MockResponse().setBody(DRAFT_CREATED_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        client.saveDraft(
            session = session, accountId = "acc1", auth = BasicAuth("u", "p"),
            from = EmailAddress(email = "alex@example.com"), to = emptyList(),
            subject = "s", textBody = "b", draftMailboxId = "mbDrafts",
        )

        assertTrue(!server.takeRequest().body.readUtf8().contains("\"attachments\""))
    }

    @Test fun saveDraft_throwsOnNotCreated() {
        server.enqueue(MockResponse().setBody(DRAFT_NOTCREATED_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        try {
            runBlocking {
                client.saveDraft(
                    session = session, accountId = "acc1", auth = BasicAuth("u", "p"),
                    from = EmailAddress(email = "alex@example.com"), to = emptyList(),
                    subject = "s", textBody = "b", draftMailboxId = "mbDrafts",
                )
            }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertTrue(e.message!!.contains("overQuota"))
        }
    }

    @Test fun saveDraft_surfacesMethodLevelError() {
        // A server can reject the whole method (e.g. Stalwart answers "forbidden" when the
        // request targets an account the credential doesn't own) — that must throw, not
        // silently pass as a saved draft.
        server.enqueue(
            MockResponse().setBody(
                """{"methodResponses":[["error",{"type":"forbidden","description":"You are not an owner of account u"},"s0"]]}""",
            ),
        )
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        try {
            runBlocking {
                client.saveDraft(
                    session = session, accountId = "u", auth = BasicAuth("u", "p"),
                    from = EmailAddress(email = "alex@example.com"), to = emptyList(),
                    subject = "s", textBody = "b", draftMailboxId = "mbDrafts",
                )
            }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertEquals("forbidden", e.errorType)
            assertTrue(e.message!!.contains("forbidden"))
        }
    }

    // ---- ghost pruning seams: externally-destroyed messages (issue #31 sub-accounts) ----

    @Test fun missingEmailIds_returnsOnlyTheExplicitNotFoundIds() = runBlocking {
        server.enqueue(MockResponse().setBody(MISSING_IDS_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        val missing = client.missingEmailIds(session, "acc1", listOf("e1", "e2", "e3"), BasicAuth("u", "p"))

        assertEquals(setOf("e2"), missing)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("Email/get"))
        assertTrue(sent.contains("\"ids\":[\"e1\",\"e2\",\"e3\"]"))
        // ids-only existence check — never a full header re-download.
        assertTrue(sent.contains("\"properties\":[\"id\"]"))
    }

    @Test fun missingEmailIds_emptyInputSkipsTheNetwork() = runBlocking {
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        assertTrue(client.missingEmailIds(session, "acc1", emptyList(), BasicAuth("u", "p")).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun missingEmailIds_throwsOnJmapErrorRatherThanGuessing() {
        // A method-level error must throw: a transient failure may never be read as
        // "these messages are gone" (the caller prunes ONLY on an explicit notFound).
        server.enqueue(MockResponse().setBody(ERROR_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        try {
            runBlocking { client.missingEmailIds(session, "acc1", listOf("e1"), BasicAuth("u", "p")) }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertTrue(e.message!!.contains("accountNotFound"))
        }
    }

    @Test fun move_carriesThePerIdSetErrorType() {
        server.enqueue(MockResponse().setBody(SET_NOTFOUND_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        try {
            runBlocking { client.move(session, "acc1", "e1", "mbTrash", BasicAuth("u", "p")) }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            // The repository keys the ghost prune off the typed SetError, not the message text.
            assertEquals("notFound", e.errorType)
        }
    }

    @Test fun setKeyword_carriesThePerIdSetErrorType() {
        server.enqueue(MockResponse().setBody(SET_NOTFOUND_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        try {
            runBlocking { client.setSeen(session, "acc1", "e1", true, BasicAuth("u", "p")) }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertEquals("notFound", e.errorType)
        }
    }

    private companion object {
        const val DRAFT_CREATED_JSON = """
            {"methodResponses":[["Email/set",
              {"accountId":"acc1","oldState":"s1","newState":"s2",
               "created":{"draft":{"id":"d123","threadId":"t1","blobId":"b1","size":321}}},
              "s0"]]}
        """

        const val DRAFT_NOTCREATED_JSON = """
            {"methodResponses":[["Email/set",
              {"accountId":"acc1","oldState":"s1","newState":"s1",
               "notCreated":{"draft":{"type":"overQuota","description":"Mailbox full"}}},
              "s0"]]}
        """

        const val MISSING_IDS_JSON = """
            {
              "methodResponses": [
                ["Email/get", {
                  "accountId": "acc1",
                  "state": "st1",
                  "list": [ { "id": "e1" }, { "id": "e3" } ],
                  "notFound": ["e2"]
                }, "g0"]
              ]
            }
        """

        const val SET_NOTFOUND_JSON = """
            {
              "methodResponses": [
                ["Email/set", {
                  "accountId": "acc1",
                  "notUpdated": { "e1": { "type": "notFound" } }
                }, "s0"]
              ]
            }
        """

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

        // A session as served by a misconfigured TLS reverse proxy: every advertised URL
        // comes back as http:// even though the session itself was fetched over https.
        const val HTTP_URLS_SESSION_JSON = """
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
              "apiUrl": "http://mail.example.com/jmap/api/",
              "downloadUrl": "http://mail.example.com/jmap/download/",
              "uploadUrl": "http://mail.example.com/jmap/upload/",
              "eventSourceUrl": "http://mail.example.com/jmap/eventsource/",
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
