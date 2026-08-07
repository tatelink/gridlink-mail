package app.sterna.core.jmap

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.Quota
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.core.jmap.model.VacationResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** The ids one `Email/set` request body carries, in order — its `update` keys or its `destroy`. */
/** The ids one `Email/get` request body asks for, in order. */
private fun getIdsOf(body: String): List<String> =
    Json.parseToJsonElement(body).jsonObject["methodCalls"]!!
        .jsonArray[0].jsonArray[1].jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.content }

private fun setIdsOf(body: String): List<String> {
    val args = Json.parseToJsonElement(body).jsonObject["methodCalls"]!!
        .jsonArray[0].jsonArray[1].jsonObject
    args["update"]?.jsonObject?.let { return it.keys.toList() }
    args["destroy"]?.jsonArray?.let { arr -> return arr.map { it.jsonPrimitive.content } }
    return emptyList()
}

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

    @Test fun fetchSession_reAuthenticatesWhenACrossHostRedirectAnswersAnAnonymousSession() = runBlocking {
        // Issue #137. Same shape as the test above — a redirect that crosses origins, so OkHttp
        // dropped the Authorization header — except that the server does NOT answer 401: it
        // answers 200 with an anonymous session ("username":"", "accounts":{}). Gating the replay
        // on the status code leaves the caller with mailAccountId() == null and the account is
        // rejected with "This user has no JMAP mail account.", which blames the user's mailbox.
        val target = MockWebServer()
        target.start()
        try {
            target.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.getHeader("Authorization") == "Bearer fmu1-token") {
                        MockResponse().setHeader("Content-Type", "application/json").setBody(SESSION_JSON)
                    } else {
                        MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(ANONYMOUS_SESSION_JSON)
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
            assertEquals("admin@masto.top", session.username)
            // First hit arrives header-less and gets the anonymous session, the retry authenticates.
            assertEquals(2, target.requestCount)
        } finally {
            target.shutdown()
        }
    }

    @Test fun fetchSession_doesNotReplayWhenASameOriginRedirectOnlyChangesThePath() = runBlocking {
        // The ordinary autodiscovery hop, /.well-known/jmap → /jmap/session on the SAME origin:
        // OkHttp keeps the Authorization header across it, so there is nothing to re-authenticate
        // and the credentials must not be sent a third time.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/jmap/session"))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(SESSION_JSON),
        )

        val session = client.fetchSession(
            server.url("/.well-known/jmap").toString(),
            BearerAuth("fmu1-token"),
        )

        assertEquals("acc1", session.mailAccountId())
        // Exactly the redirect and the hop it points at — no replay of the target.
        assertEquals(2, server.requestCount)
        assertEquals("/.well-known/jmap", server.takeRequest().path)
        val followed = server.takeRequest()
        assertEquals("/jmap/session", followed.path)
        assertEquals("Bearer fmu1-token", followed.getHeader("Authorization"))
    }

    @Test fun fetchSession_doesNotFollowARedirectThatChangesScheme() = runBlocking {
        // followSslRedirects(false) governs EVERY scheme change, both ways (OkHttp's
        // buildRedirectRequest compares the two schemes for equality), so an http→https hop is
        // refused exactly like the https→http downgrade the guard exists for — which lets the
        // plaintext mock server prove it without any TLS test dependency.
        //
        // The target is 127.0.0.1:1: no DNS, no handshake, connection refused with the network
        // cable out. If the guard ever goes away, OkHttp follows the hop and the call dies on a
        // ConnectException instead of surfacing the 302 — that swap of exception type is what
        // this test watches. ⛔ Not server.requestCount: the mock never hears about the second
        // hop either way, so counting it would assert 1 in both worlds and prove nothing.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://127.0.0.1:1/jmap/session"),
        )

        val failure = assertThrows(JmapException::class.java) {
            runBlocking {
                client.fetchSession(
                    server.url("/.well-known/jmap").toString(),
                    BearerAuth("fmu1-token"),
                )
            }
        }

        // The redirect is reported as the failure, not followed: the status is pinned on the
        // field that carries it, rather than on a substring of the message.
        assertEquals(302, failure.httpCode)
    }

    @Test fun defaultHttpClient_refusesSchemeChangesButStillFollowsRedirects() {
        // The two halves of the discovery posture, pinned on the client app code actually gets
        // (JmapClient()'s public constructor builds this one).
        assertFalse(JmapClient.defaultHttpClient().followSslRedirects)
        assertTrue(JmapClient.defaultHttpClient().followRedirects)
    }

    // ---- the decision itself, run on its arguments (issue #137) ----
    //
    // What made the header fall off is the ORIGIN triplet (scheme + host + port) changing — that
    // is OkHttp's own rule (canReuseConnectionFor). The response's status code says nothing about
    // it, and a session with no account is not a symptom either: an account that genuinely has no
    // mailbox answers exactly the same.

    @Test fun redirectDroppedAuthorization_isTrueWhenTheHostChanged() {
        assertTrue(
            JmapClient.redirectDroppedAuthorization(
                "https://example.com/.well-known/jmap".toHttpUrl(),
                "https://api.example.com/jmap/session".toHttpUrl(),
            ),
        )
    }

    @Test fun redirectDroppedAuthorization_isTrueWhenOnlyThePortChanged() {
        // ⛔ The port is part of the triplet: without it two MockWebServers on localhost are
        // "the same origin" and none of this is reproducible — nor is a real host:8443 hop.
        assertTrue(
            JmapClient.redirectDroppedAuthorization(
                "https://example.com/.well-known/jmap".toHttpUrl(),
                "https://example.com:8443/jmap/session".toHttpUrl(),
            ),
        )
    }

    @Test fun redirectDroppedAuthorization_isFalseWhenTheSchemeChanged() {
        // Never replay credentials down to cleartext (followSslRedirects(false) already refuses
        // the hop; this keeps the decision itself from ever asking for one).
        assertFalse(
            JmapClient.redirectDroppedAuthorization(
                "https://example.com/.well-known/jmap".toHttpUrl(),
                "http://example.com/jmap/session".toHttpUrl(),
            ),
        )
    }

    @Test fun redirectDroppedAuthorization_isFalseWhenOnlyThePathChanged() {
        // Same origin: OkHttp carried the header over, the request was authenticated.
        assertFalse(
            JmapClient.redirectDroppedAuthorization(
                "https://example.com/.well-known/jmap".toHttpUrl(),
                "https://example.com/jmap/session".toHttpUrl(),
            ),
        )
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

    /**
     * A search is two method calls in one request, and they do not have to agree: `Email/query`
     * matched three ids, `Email/get` handed back two of them (server cap, or a message destroyed in
     * between). Reporting only what came back would let the caller state "2 results" for an account
     * that held three, so both counts have to leave the client.
     */
    @Test fun searchEmails_reportsWhatTheQueryMatchedApartFromWhatTheGetReturned() = runBlocking {
        server.enqueue(MockResponse().setBody(SEARCH_SHORT_GET_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        val page = client.searchEmails(session, "acc1", SearchQuery(text = "invoice"), 50, BasicAuth("u", "p"))

        assertEquals(3, page.matchedIds)
        assertEquals(2, page.emails.size)
    }

    /** The witness: a server whose get returned everything its query matched reports equal counts. */
    @Test fun searchEmails_reportsEqualCountsWhenTheGetReturnedEverythingTheQueryMatched() = runBlocking {
        server.enqueue(MockResponse().setBody(SEARCH_WHOLE_GET_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        val page = client.searchEmails(session, "acc1", SearchQuery(text = "invoice"), 50, BasicAuth("u", "p"))

        assertEquals(2, page.matchedIds)
        assertEquals(2, page.emails.size)
    }

    /**
     * A query response with no `ids` array at all: the server did not say how many matched, and
     * that must not be reported as "none matched" — a caller reading zero there would present an
     * empty result as the account's whole answer.
     */
    @Test fun searchEmails_leavesTheMatchCountUnknownWhenTheServerGaveNoIds() = runBlocking {
        server.enqueue(MockResponse().setBody(SEARCH_NO_IDS_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        val page = client.searchEmails(session, "acc1", SearchQuery(text = "invoice"), 50, BasicAuth("u", "p"))

        assertNull(page.matchedIds)
        assertEquals(emptyList<Any>(), page.emails)
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

    /**
     * What `Mailbox/get` puts in [JmapException.errorType] is the evidence a caller is entitled to
     * act on, and the sub-account probe of Codeberg #129 discards an account on exactly one value
     * of it. So the two shapes are pinned here, at the only place that can observe them.
     *
     * A JMAP METHOD error (HTTP 200, `["error", {"type": …}]`) carries its type verbatim. This is
     * the answer the bench saw for an account visible only through a read-only calendar share.
     */
    @Test fun getMailboxes_methodErrorCarriesItsTypeAsErrorType() {
        server.enqueue(MockResponse().setBody(FORBIDDEN_ERROR_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        try {
            runBlocking { client.getMailboxes(session, "acc1", BasicAuth("u", "p")) }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertEquals("forbidden", e.errorType)
        }
    }

    /**
     * A TRANSPORT failure carries no error type — and must not be given one. An expiring Bearer
     * token, an anti-abuse guard or a corporate proxy all answer HTTP 403, and a client that turned
     * that into `errorType = "forbidden"` would have the #129 probe delete every sub-account of the
     * login at once, purging their caches, over a passing authentication incident.
     */
    @Test fun getMailboxes_httpFailureCarriesNoErrorType() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        try {
            runBlocking { client.getMailboxes(session, "acc1", BasicAuth("u", "p")) }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertTrue(e.message!!.contains("403"))
            assertNull("an HTTP status is not a JMAP method error", e.errorType)
            assertNull("Mailbox/get does not report the HTTP status either", e.httpCode)
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

    // ---- on-behalf sending seams (issue #31) ----

    @Test fun sendEmail_returnsTheCreatedEmailId() = runBlocking {
        server.enqueue(MockResponse().setBody(SEND_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        val emailId = client.sendEmail(
            session = session,
            accountId = "acc1",
            auth = BasicAuth("u", "p"),
            identityId = "i1",
            from = app.sterna.core.jmap.model.EmailAddress(name = "Jordan", email = "jordan@example.org"),
            to = listOf(app.sterna.core.jmap.model.EmailAddress(email = "dest@example.org")),
            subject = "Hi",
            textBody = "Hello",
            draftMailboxId = "mbDrafts",
            sentMailboxId = "mbSent",
        )

        assertEquals("e123", emailId)
    }

    @Test fun copyEmailToAccount_copiesThenDestroysTheRealIdExplicitly() = runBlocking {
        server.enqueue(MockResponse().setBody(COPY_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        client.copyEmailToAccount(
            session = session,
            auth = BasicAuth("u", "p"),
            fromAccountId = "s",
            toAccountId = "sub",
            emailId = "e123",
            mailboxId = "mbSent",
        )

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("Email/copy"))
        assertTrue(sent.contains("\"fromAccountId\":\"s\""))
        assertTrue(sent.contains("\"accountId\":\"sub\""))
        // Regression guard: the destroy must name the real Email id in a second, explicit
        // Email/set — Stalwart's onSuccessDestroyOriginal targets the creation id and
        // silently leaves the original behind.
        assertTrue(sent.contains("\"destroy\":[\"e123\"]"))
        assertTrue(!sent.contains("onSuccessDestroyOriginal"))
    }

    @Test fun copyEmailToAccount_throwsWhenTheCopyIsRefused() {
        server.enqueue(MockResponse().setBody(COPY_NOTCREATED_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        try {
            runBlocking {
                client.copyEmailToAccount(session, BasicAuth("u", "p"), "s", "sub", "e123", "mbSent")
            }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertTrue(e.message!!.contains("Could not file the sent copy"))
        }
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

    // ---- where the server says a frozen list of ids actually IS (Codeberg #122) ----

    @Test fun mailboxIdsOf_readsEachIdsFoldersAndAsksForNothingElse() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"methodResponses":[["Email/get",{"accountId":"acc1","state":"g1","list":[
                   {"id":"e1","mailboxIds":{"mbTrash":true}},
                   {"id":"e2","mailboxIds":{"mbInbox":true,"mbKeep":true}},
                   {"id":"e4","mailboxIds":{"mbTrash":true,"mbOld":false}}],
                   "notFound":["e3"]},"g0"]]}""",
            ),
        )
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())

        val located = client.mailboxIdsOf(session, "acc1", listOf("e1", "e2", "e3", "e4"), BasicAuth("u", "p"))

        assertEquals(
            mapOf(
                "e1" to setOf("mbTrash"),
                "e2" to setOf("mbInbox", "mbKeep"),
                // A false membership is not a membership.
                "e4" to setOf("mbTrash"),
            ),
            located,
        )
        // notFound is simply absent — the caller reads that as "nothing left to destroy".
        assertEquals(null, located["e3"])
        val sent = server.takeRequest().body.readUtf8()
        assertEquals(listOf("e1", "e2", "e3", "e4"), getIdsOf(sent))
        // The whole point of this call: mailboxIds. And nothing heavier — no preview, no bodies.
        assertTrue(sent.contains("\"properties\":[\"id\",\"mailboxIds\"]"))
    }

    @Test fun mailboxIdsOf_emptyInputSkipsTheNetwork() = runBlocking {
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        assertTrue(client.mailboxIdsOf(session, "acc1", emptyList(), BasicAuth("u", "p")).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun mailboxIdsOf_throwsOnJmapErrorRatherThanGuessing() {
        // The caller DESTROYS what this reports as still in place. A method error that came back
        // as "no rows" would be read as "all these ids moved" — safe here — but the contract is
        // the same as missingEmailIds': never answer a question the server refused.
        server.enqueue(MockResponse().setBody(ERROR_JSON))
        val session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        try {
            runBlocking { client.mailboxIdsOf(session, "acc1", listOf("e1"), BasicAuth("u", "p")) }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertTrue(e.message!!.contains("accountNotFound"))
        }
    }

    @Test fun mailboxIdsOf_splitsIntoRequestsOfAtMostTheAdvertisedGetLimit() = runBlocking {
        // An Empty-trash wave is 500 ids; a server admitting fewer per get refuses the WHOLE call.
        val dispatcher = LocateDispatcher()
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }

        val located = client.mailboxIdsOf(sessionAdvertisingGet(5), "acc1", ids, BasicAuth("u", "p"))

        assertEquals(3, server.requestCount)
        assertEquals(listOf(5, 5, 2), dispatcher.bodies.map { getIdsOf(it).size })
        assertEquals(ids.toSet(), located.keys)
    }

    @Test fun mailboxIdsOf_throwsRatherThanHandingBackThePartOfTheAnswerItGot() {
        // A batch that never answered must not come back as "those ids are gone from the folder"
        // — nor, once the caller inverts it, as anything at all. It throws, the destroy does not
        // run, and the worker retries it.
        val dispatcher = LocateDispatcher(failAt = 2)
        server.dispatcher = dispatcher
        try {
            runBlocking {
                client.mailboxIdsOf(sessionAdvertisingGet(5), "acc1", (1..12).map { "e$it" }, BasicAuth("u", "p"))
            }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertEquals(500, e.httpCode)
        }
        assertEquals(2, server.requestCount)
    }

    /** Answers an `Email/get` by placing every id it was asked for in one folder; [failAt] refuses
     *  the n-th request outright, the way a dropped connection does. */
    private class LocateDispatcher(private val failAt: Int? = null) : Dispatcher() {
        val bodies = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            bodies += body
            if (failAt == bodies.size) return MockResponse().setResponseCode(500)
            return MockResponse().setBody(
                """{"methodResponses":[["Email/get",{"accountId":"acc1","state":"g${bodies.size}",
                   "list":[${
                    getIdsOf(body).joinToString(",") { "{\"id\":\"$it\",\"mailboxIds\":{\"mbTrash\":true}}" }
                }],"notFound":[]},"g0"]]}""",
            )
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

    // ---- Email/get batching ----
    //
    // The Undo of a big bulk move re-fetches every id it moved back. maxObjectsInGet is 500 on the
    // test server and RFC 8620 §5.1 rejects the WHOLE call past it — and the Undo swallows that
    // failure, so the mail comes back on the server and vanishes from the list silently.

    @Test fun getEmailsByIds_splitsIntoRequestsOfAtMostTheAdvertisedGetLimit() = runBlocking {
        val dispatcher = EchoGetDispatcher()
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }

        val emails = client.getEmailsByIds(sessionAdvertisingGet(5), "acc1", ids, BasicAuth("u", "p"))

        assertEquals(3, server.requestCount)
        assertEquals(listOf(5, 5, 2), dispatcher.bodies.map { getIdsOf(it).size })
        assertEquals(ids, dispatcher.bodies.flatMap { getIdsOf(it) })
        // Concatenated, in order, nothing lost.
        assertEquals(ids, emails.map { it.id })
        // Still the list (no-body) property set, in every request.
        assertTrue(dispatcher.bodies.all { it.contains("\"preview\"") && !it.contains("\"bodyValues\"") })
    }

    @Test fun getEmailsByIds_throwsRatherThanReturningWhatItManagedToFetch() {
        // A read has no partial credit: the Undo overwrites the cache with what comes back, so
        // half an answer is worse than none — it would look like a successful restore of half.
        val dispatcher = EchoGetDispatcher(failAt = 2)
        server.dispatcher = dispatcher
        try {
            runBlocking {
                client.getEmailsByIds(sessionAdvertisingGet(5), "acc1", (1..12).map { "e$it" }, BasicAuth("u", "p"))
            }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertEquals(500, e.httpCode)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun getEmailsWithBody_splitsIntoRequestsOfAtMostTheAdvertisedGetLimit() = runBlocking {
        val dispatcher = EchoGetDispatcher()
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }

        val emails = client.getEmailsWithBody(sessionAdvertisingGet(5), "acc1", ids, BasicAuth("u", "p"))

        assertEquals(3, server.requestCount)
        assertEquals(listOf(5, 5, 2), dispatcher.bodies.map { getIdsOf(it).size })
        assertEquals(ids, emails.map { it.id })
        // Every request still asks for the bodies, not just the first.
        assertTrue(dispatcher.bodies.all { it.contains("\"fetchHTMLBodyValues\":true") })
    }

    @Test fun missingEmailIds_unionsTheNotFoundOfEveryBatch() = runBlocking {
        val dispatcher = EchoGetDispatcher(missing = setOf("e2", "e7", "e12"))
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }

        val missing = client.missingEmailIds(sessionAdvertisingGet(5), "acc1", ids, BasicAuth("u", "p"))

        assertEquals(3, server.requestCount)
        assertEquals(setOf("e2", "e7", "e12"), missing)
        // Still the ids-only existence check in every request, never a header re-download.
        assertTrue(dispatcher.bodies.all { it.contains("\"properties\":[\"id\"]") })
    }

    @Test fun missingEmailIds_throwsRatherThanReturningAPartialUnion() {
        // ⛔ THE dangerous one: the caller DELETES the rows this returns. A batch that never
        // answered must never come back as "those messages are gone".
        val dispatcher = EchoGetDispatcher(failAt = 2, missing = setOf("e2"))
        server.dispatcher = dispatcher
        try {
            runBlocking {
                client.missingEmailIds(sessionAdvertisingGet(5), "acc1", (1..12).map { "e$it" }, BasicAuth("u", "p"))
            }
            throw AssertionError("expected JmapException — a partial union prunes live mail")
        } catch (e: JmapException) {
            assertEquals(500, e.httpCode)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun get_emptyListAndUnderTheLimitAreUnchanged() = runBlocking {
        val dispatcher = EchoGetDispatcher()
        server.dispatcher = dispatcher
        val session = sessionAdvertisingGet(5)
        val auth = BasicAuth("u", "p")

        assertTrue(client.getEmailsByIds(session, "acc1", emptyList(), auth).isEmpty())
        assertTrue(client.getEmailsWithBody(session, "acc1", emptyList(), auth).isEmpty())
        assertTrue(client.missingEmailIds(session, "acc1", emptyList(), auth).isEmpty())
        assertEquals(0, server.requestCount)

        val ids = listOf("e1", "e2", "e3")
        assertEquals(ids, client.getEmailsByIds(session, "acc1", ids, auth).map { it.id })
        assertEquals(1, server.requestCount)
        assertEquals(ids, getIdsOf(dispatcher.bodies.single()))
    }

    // ---- Email/set batching: no request may carry more ids than the server accepts ----
    //
    // A single `Email/set` carrying a whole select-all is rejected in ONE block past the server's
    // maxObjectsInSet (Stalwart: 500), and nothing in the app used to read that number.

    /**
     * Answers every `Email/set` by echoing the ids it carried (as `updated` or `destroyed`), with
     * a per-request `newState`, so a test can tell WHICH request confirmed WHAT. [failAt] makes
     * the n-th request an HTTP 500 — a transport failure, not a per-id rejection.
     */
    private class EchoSetDispatcher(
        private val failAt: Int? = null,
        /** Ids the server refuses, mapped to their SetError type, whichever batch they land in. */
        private val rejections: Map<String, String> = emptyMap(),
    ) : Dispatcher() {
        val bodies = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            bodies += body
            if (failAt == bodies.size) return MockResponse().setResponseCode(500)
            val destroying = body.contains("\"destroy\"")
            val (refused, ids) = setIdsOf(body).partition { it in rejections }
            val ok = if (destroying) {
                "\"destroyed\":[${ids.joinToString(",") { "\"$it\"" }}]"
            } else {
                "\"updated\":{${ids.joinToString(",") { "\"$it\":null" }}}"
            }
            val key = if (destroying) "notDestroyed" else "notUpdated"
            val bad = refused.joinToString(",") { "\"$it\":{\"type\":\"${rejections[it]}\"}" }
            return MockResponse().setBody(
                """{"methodResponses":[["Email/set",
                   {"accountId":"acc1","newState":"s${bodies.size}",$ok,"$key":{$bad}},"s0"]]}""",
            )
        }
    }

    /** Answers every `Email/get` with one row per requested id, minus the [missing] ones. */
    private class EchoGetDispatcher(
        private val failAt: Int? = null,
        private val missing: Set<String> = emptySet(),
    ) : Dispatcher() {
        val bodies = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            bodies += body
            if (failAt == bodies.size) return MockResponse().setResponseCode(500)
            val (gone, found) = getIdsOf(body).partition { it in missing }
            return MockResponse().setBody(
                """{"methodResponses":[["Email/get",{"accountId":"acc1","state":"g${bodies.size}",
                   "list":[${found.joinToString(",") { "{\"id\":\"$it\"}" }}],
                   "notFound":[${gone.joinToString(",") { "\"$it\"" }}]},"g0"]]}""",
            )
        }
    }

    private fun sessionAdvertising(maxObjectsInSet: Int?): JmapSession =
        sessionAdvertising("maxObjectsInSet", maxObjectsInSet)

    private fun sessionAdvertisingGet(maxObjectsInGet: Int?): JmapSession =
        sessionAdvertising("maxObjectsInGet", maxObjectsInGet)

    private fun sessionAdvertising(property: String, limit: Int?): JmapSession = JmapSession(
        apiUrl = server.url("/jmap/api/").toString(),
        capabilities = buildMap {
            put(Jmap.MAIL_CAPABILITY, buildJsonObject { })
            if (limit != null) put(Jmap.CORE_CAPABILITY, buildJsonObject { put(property, limit) })
        },
    )

    @Test fun move_splitsIntoRequestsOfAtMostTheAdvertisedLimit() = runBlocking {
        val dispatcher = EchoSetDispatcher()
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }

        val result = client.move(sessionAdvertising(5), "acc1", ids, "mbArchive", BasicAuth("u", "p"))

        assertEquals(3, server.requestCount)
        val perRequest = dispatcher.bodies.map { setIdsOf(it) }
        assertEquals(listOf(5, 5, 2), perRequest.map { it.size })
        // Every id sent exactly once, in order, across the three requests.
        assertEquals(ids, perRequest.flatten())
        // Aggregated: the union of what the three requests confirmed, not the last one's.
        assertEquals(ids.toSet(), result.done)
        assertTrue(result.failed.isEmpty())
        // The newest state we hold: the LAST batch's. An older one would merely re-read wider.
        assertEquals("s3", result.newState)
        // Each request is a well-formed move of its own ids.
        assertTrue(dispatcher.bodies.all { it.contains("\"mailboxIds\":{\"mbArchive\":true}") })
    }

    @Test fun destroy_splitsIntoRequestsOfAtMostTheAdvertisedLimit() = runBlocking {
        val dispatcher = EchoSetDispatcher()
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }

        val result = client.destroy(sessionAdvertising(5), "acc1", ids, BasicAuth("u", "p"))

        assertEquals(3, server.requestCount)
        val perRequest = dispatcher.bodies.map { setIdsOf(it) }
        assertEquals(listOf(5, 5, 2), perRequest.map { it.size })
        assertEquals(ids, perRequest.flatten())
        assertEquals(ids.toSet(), result.done)
        assertEquals("s3", result.newState)
        assertTrue(dispatcher.bodies.all { it.contains("\"destroy\":[") })
    }

    @Test fun setSeenAll_splitsIntoRequestsOfAtMostTheAdvertisedLimit() = runBlocking {
        val dispatcher = EchoSetDispatcher()
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }

        val result = client.setSeenAll(sessionAdvertising(5), "acc1", ids, true, BasicAuth("u", "p"))

        assertEquals(3, server.requestCount)
        assertEquals(listOf(5, 5, 2), dispatcher.bodies.map { setIdsOf(it).size })
        assertEquals(ids.toSet(), result.done)
    }

    @Test fun move_withoutAnAdvertisedLimitFallsBackToAHundredPerRequest() = runBlocking {
        val dispatcher = EchoSetDispatcher()
        server.dispatcher = dispatcher
        val ids = (1..250).map { "e$it" }

        val result = client.move(sessionAdvertising(null), "acc1", ids, "mbArchive", BasicAuth("u", "p"))

        assertEquals(3, server.requestCount)
        assertEquals(listOf(100, 100, 50), dispatcher.bodies.map { setIdsOf(it).size })
        assertEquals(ids.toSet(), result.done)
    }

    @Test fun move_stopsAtTheFirstTransportFailureAndFailsEveryUntriedId() = runBlocking {
        val dispatcher = EchoSetDispatcher(failAt = 2)
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }

        val result = client.move(sessionAdvertising(5), "acc1", ids, "mbArchive", BasicAuth("u", "p"))

        // Stopped: batch 3 was never sent. On a dead connection every further batch would only
        // grind through postWithRetry's backoff.
        assertEquals(2, server.requestCount)
        assertEquals(ids.take(5).toSet(), result.done)
        // The failing batch AND the batch never attempted — not silently dropped.
        assertEquals(ids.drop(5).toSet(), result.failed.keys)
        // ⛔ Never "notFound": the repository prunes the local row of a notFound id, so marking an
        // unreachable batch that way would delete live messages from the cache.
        assertTrue(result.failed.values.none { it == "notFound" })
        assertEquals(setOf(Jmap.SET_ERROR_TRANSPORT), result.failed.values.toSet())
    }

    @Test fun destroy_throwsWhenABatchFailsMidway() {
        val dispatcher = EchoSetDispatcher(failAt = 2)
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }
        try {
            runBlocking { client.destroy(sessionAdvertising(5), "acc1", ids, BasicAuth("u", "p")) }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertEquals(500, e.httpCode)
        }
        // The destroy worker retries a confirmed destroy; it must never read a partial result as
        // "done". And we still stop rather than hammer the dead connection.
        assertEquals(2, server.requestCount)
    }

    @Test fun setSeenAll_throwsWhenABatchFailsMidway() {
        // Like destroy, and for a caller-contract reason: "Mark all read" keeps the notifications
        // it would otherwise dismiss ONLY if setReadAll threw (audit B5). Swallowing a transport
        // failure here would dismiss the notifications of mail nothing ever marked read.
        val dispatcher = EchoSetDispatcher(failAt = 2)
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }
        try {
            runBlocking { client.setSeenAll(sessionAdvertising(5), "acc1", ids, true, BasicAuth("u", "p")) }
            throw AssertionError("expected JmapException")
        } catch (e: JmapException) {
            assertEquals(500, e.httpCode)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun setBatched_emptyListSkipsTheNetworkEntirely() = runBlocking {
        val session = sessionAdvertising(5)
        val auth = BasicAuth("u", "p")
        assertTrue(client.move(session, "acc1", emptyList(), "mb", auth).done.isEmpty())
        assertTrue(client.destroy(session, "acc1", emptyList(), auth).done.isEmpty())
        assertTrue(client.setSeenAll(session, "acc1", emptyList(), true, auth).done.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun move_reportsThePerIdRejectionsOfEveryBatch() = runBlocking {
        // The aggregation of per-id SetErrors, which nothing exercised: the repository keys the
        // ghost prune off `notFound` and reports the rest as failures. Batch 2 of 3 rejects two of
        // its five ids, for two DIFFERENT reasons.
        val dispatcher = EchoSetDispatcher(rejections = mapOf("e6" to "notFound", "e8" to "forbidden"))
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }

        val result = client.move(sessionAdvertising(5), "acc1", ids, "mbArchive", BasicAuth("u", "p"))

        assertEquals(3, server.requestCount)
        // The other two batches keep their whole credit, and so do batch 2's three good ids.
        assertEquals(ids.toSet() - setOf("e6", "e8"), result.done)
        // ⛔ The TYPE survives aggregation: `notFound` is what makes the repository prune a ghost
        // row, and anything else must NOT be read that way.
        assertEquals(mapOf("e6" to "notFound", "e8" to "forbidden"), result.failed)
    }

    @Test fun destroy_reportsThePerIdRejectionsOfEveryBatch() = runBlocking {
        val dispatcher = EchoSetDispatcher(rejections = mapOf("e7" to "notFound"))
        server.dispatcher = dispatcher
        val ids = (1..12).map { "e$it" }

        val result = client.destroy(sessionAdvertising(5), "acc1", ids, BasicAuth("u", "p"))

        assertEquals(ids.toSet() - setOf("e7"), result.done)
        assertEquals(mapOf("e7" to "notFound"), result.failed)
    }

    @Test fun move_underTheLimitIsStillOneRequestCarryingEveryId() = runBlocking {
        val dispatcher = EchoSetDispatcher()
        server.dispatcher = dispatcher
        val ids = listOf("e1", "e2", "e3", "e4")

        val result = client.move(sessionAdvertising(5), "acc1", ids, "mbArchive", BasicAuth("u", "p"))

        assertEquals(1, server.requestCount)
        assertEquals(ids, setIdsOf(dispatcher.bodies.single()))
        assertEquals(ids.toSet(), result.done)
        assertEquals("s1", result.newState)
    }

    // ---- A thread bigger than one server page ----
    //
    // Thread/get + a back-referenced Email/get in ONE request asked for every message of the
    // thread with no bound at all. Past maxObjectsInGet the server refuses the get whole, so
    // expanding a long conversation returned NOTHING — the only one of these three siblings that
    // loses something the reader can see.

    /**
     * A server holding one thread of [threadSize] messages. Answers `Thread/get`, and answers
     * `Email/get` either from an explicit id list or from a `#ids` back-reference — refusing,
     * in both cases, any get that would carry more than [maxObjectsInGet] ids.
     */
    private class ThreadDispatcher(
        private val threadSize: Int,
        private val maxObjectsInGet: Int,
    ) : Dispatcher() {
        /** The method name of each request's FIRST call, in order. */
        val calls = mutableListOf<String>()

        /** The ids each explicit `Email/get` asked for, in order. */
        val getBatches = mutableListOf<List<String>>()

        private val all = (1..threadSize).map { "e$it" }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            val methodCalls = Json.parseToJsonElement(body).jsonObject["methodCalls"]!!.jsonArray
            val first = methodCalls[0].jsonArray
            calls += first[0].jsonPrimitive.content
            val responses = mutableListOf<String>()
            if (first[0].jsonPrimitive.content == "Thread/get") {
                responses += """["Thread/get",{"accountId":"acc1","state":"t1",
                    "list":[{"id":"th1","emailIds":[${all.joinToString(",") { "\"$it\"" }}]}],
                    "notFound":[]},"t0"]"""
                // The old shape: a second call whose ids the SERVER resolves from the first.
                val chained = methodCalls.getOrNull(1)?.jsonArray
                if (chained != null) {
                    responses += emailGet(all, "g0")
                }
            } else {
                val ids = getIdsOf(body)
                getBatches += ids
                responses += emailGet(ids, "g0", withMailboxIds = "\"mailboxIds\"" in body)
            }
            return MockResponse().setBody("""{"methodResponses":[${responses.joinToString(",")}]}""")
        }

        /**
         * One row per id. A real server returns ONLY the properties the request asked for, and
         * this one does too for `mailboxIds` — otherwise a test could not tell a request that
         * asks for it from one that does not, and the property could be dropped unnoticed.
         */
        private fun emailGet(ids: List<String>, callId: String, withMailboxIds: Boolean = true): String =
            if (ids.size > maxObjectsInGet) {
                """["error",{"type":"requestTooLarge"},"$callId"]"""
            } else {
                val mailboxes = if (withMailboxIds) ",\"mailboxIds\":{\"mbA\":true}" else ""
                """["Email/get",{"accountId":"acc1","state":"g1","list":[${
                    ids.joinToString(",") { "{\"id\":\"$it\"$mailboxes}" }
                }],"notFound":[]},"$callId"]"""
            }
    }

    @Test fun getThreadEmails_splitsTheGetInsteadOfLettingTheServerRefuseTheWholeThread() = runBlocking {
        // 700 messages in one thread, a server admitting 500 per get. Asking for all 700 in a
        // back-reference gets the whole get refused and the expansion shows nothing.
        val dispatcher = ThreadDispatcher(threadSize = 700, maxObjectsInGet = 500)
        server.dispatcher = dispatcher

        val emails = client.getThreadEmails(sessionAdvertisingGet(500), "acc1", "th1", BasicAuth("u", "p"))

        // One Thread/get, then the get split across the advertised limit — never one request.
        assertEquals(3, server.requestCount)
        assertEquals(listOf("Thread/get", "Email/get", "Email/get"), dispatcher.calls)
        assertEquals(listOf(500, 200), dispatcher.getBatches.map { it.size })
        assertEquals((1..700).map { "e$it" }, emails.map { it.id })
    }

    @Test fun getThreadEmails_stillAsksForWhatPlacesAMemberInAFolder() = runBlocking {
        // A thread that fits is still two requests, and still reads mailboxIds: the caller
        // persists each member under its real folder and SKIPS any member without one, so
        // dropping that property would make an expansion fetch everything and keep nothing.
        val dispatcher = ThreadDispatcher(threadSize = 3, maxObjectsInGet = 500)
        server.dispatcher = dispatcher

        val emails = client.getThreadEmails(sessionAdvertisingGet(500), "acc1", "th1", BasicAuth("u", "p"))

        assertEquals(2, server.requestCount)
        assertEquals(listOf("e1", "e2", "e3"), dispatcher.getBatches.single())
        assertEquals(listOf("e1", "e2", "e3"), emails.map { it.id })
        assertEquals(mapOf("mbA" to true), emails.first().mailboxIds)
    }

    @Test fun getThreadEmails_asksNothingMoreWhenTheThreadIsEmpty() = runBlocking {
        val dispatcher = ThreadDispatcher(threadSize = 0, maxObjectsInGet = 500)
        server.dispatcher = dispatcher

        val emails = client.getThreadEmails(sessionAdvertisingGet(500), "acc1", "th1", BasicAuth("u", "p"))

        assertEquals(1, server.requestCount)
        assertTrue(emails.isEmpty())
    }

    // ---- The windowed folder query: a window bigger than one server page ----
    //
    // "Messages to sync = All" is 1000 messages; Stalwart advertises maxObjectsInGet = 500. The
    // folder query sends Email/query + a BACK-REFERENCED Email/get in one request, so the get's
    // id list is whatever the query matched: over the limit the server rejects the whole get
    // (RFC 8620 §5.1), the call throws, no sync cursor is stored, and the next refresh takes the
    // same failing path. The folder never syncs again.

    /**
     * A mailbox of [folderSize] messages (`e1` newest … `eN` oldest) answering `Email/query` +
     * back-referenced `Email/get`, and — this is the point — REFUSING the get with
     * `requestTooLarge` when the query matched more than [maxObjectsInGet] ids, exactly as the
     * bench probe recorded on Stalwart.
     *
     * [failAt] turns the n-th request into an HTTP 500 (a transport failure mid-walk);
     * [anchorNotFoundAt] makes the n-th request's anchor unknown to the server.
     */
    private class FolderDispatcher(
        private val folderSize: Int,
        private val maxObjectsInGet: Int,
        private val failAt: Int? = null,
        private val anchorNotFoundAt: Int? = null,
        /**
         * Whether that `anchorNotFound` also SHORTENS the folder.
         *
         * True is the ordinary case: the anchor was destroyed, so everything past it shifted down
         * by one and the recovery at `count - 1` lands exactly on the message that followed it.
         *
         * False is the case the recovery's deliberate under-shoot exists for: the anchor left the
         * QUERY while a message arriving at the top kept the list the same length, so `count - 1`
         * lands on a row the previous page already walked. The walk must then OVERLAP — which is
         * free only because it de-duplicates.
         */
        private val anchorNotFoundShortensTheFolder: Boolean = true,
        /** Ids the query lists but the get does NOT return (destroyed between the two calls). */
        private val omittedByGet: Set<String> = emptySet(),
    ) : Dispatcher() {
        /** The `Email/query` arguments of every request, in order. */
        val queries = mutableListOf<JsonObject>()

        /**
         * The folder as the SERVER holds it, and it changes under the walk: an `anchorNotFound`
         * means the anchor has LEFT this list, so [anchorNotFoundAt] really removes it. Without
         * that, every later position still lines up with the original list and a test cannot tell
         * a correct recovery from one that skips the message the anchor was in front of.
         */
        private val all = (1..folderSize).mapTo(mutableListOf()) { "e$it" }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            val args = Json.parseToJsonElement(body).jsonObject["methodCalls"]!!
                .jsonArray[0].jsonArray[1].jsonObject
            queries += args
            val n = queries.size
            if (failAt == n) return MockResponse().setResponseCode(500)
            val anchor = args["anchor"]?.jsonPrimitive?.content
            val start = if (anchor != null) {
                if (anchorNotFoundAt == n) {
                    if (anchorNotFoundShortensTheFolder) all.remove(anchor)
                    return methodError("anchorNotFound")
                }
                val at = all.indexOf(anchor)
                if (at < 0) return methodError("anchorNotFound")
                at + (args["anchorOffset"]?.jsonPrimitive?.int ?: 0)
            } else {
                args["position"]?.jsonPrimitive?.int ?: 0
            }
            val matched = all.drop(start).take(args["limit"]!!.jsonPrimitive.int)
            val ids = matched.joinToString(",") { "\"$it\"" }
            val (gone, returned) = matched.partition { it in omittedByGet }
            // The query always answers; only the get is refused. That asymmetry is the defect.
            val get = if (matched.size > maxObjectsInGet) {
                """["error",{"type":"requestTooLarge"},"g0"]"""
            } else {
                """["Email/get",{"accountId":"acc1","state":"e$n",
                   "list":[${returned.joinToString(",") { "{\"id\":\"$it\"}" }}],
                   "notFound":[${gone.joinToString(",") { "\"$it\"" }}]},"g0"]"""
            }
            return MockResponse().setBody(
                """{"methodResponses":[
                     ["Email/query",{"accountId":"acc1","queryState":"q$n","position":$start,
                      "ids":[$ids]},"q0"],
                     $get]}""",
            )
        }

        private fun methodError(type: String) =
            MockResponse().setBody("""{"methodResponses":[["error",{"type":"$type"},"q0"]]}""")
    }

    /** The `limit` each request's `Email/query` carried, in order. */
    private fun FolderDispatcher.limits(): List<Int> = queries.map { it["limit"]!!.jsonPrimitive.int }

    /** The `anchor` each request's `Email/query` carried (null = an absolute position). */
    private fun FolderDispatcher.anchors(): List<String?> =
        queries.map { it["anchor"]?.jsonPrimitive?.content }

    /**
     * What a walk handed off, page by page, and WHEN — [requestsAtHandOff] is how many requests
     * had gone out at the instant each page arrived.
     *
     * The instant is the half that cannot be got any other way: a walk that accumulated everything
     * and called back once per page at the very end would produce the same [pages], and only "page
     * 1 arrived while exactly one request had been sent" tells the two apart.
     */
    private class Handed {
        val pages = mutableListOf<List<String>>()
        val requestsAtHandOff = mutableListOf<Int>()
        val ids: List<String> get() = pages.flatten()
    }

    private fun collect(into: Handed): suspend (List<Email>) -> Unit = { page ->
        into.pages += page.map { it.id }
        into.requestsAtHandOff += server.requestCount
    }

    @Test fun queryEmailsWindow_splitsAWindowBiggerThanTheServerPageIntoTwoRequests() = runBlocking {
        // ⭐ The lot's central case: 700 messages, a server admitting 500 per get, the "All"
        // window (1000). Two requests, and every message handed over exactly once — the caller
        // reconciles the mailbox against the ids of the WHOLE walk, so a walk that forgot the
        // first request's ids would delete the folder down to the last page.
        val dispatcher = FolderDispatcher(folderSize = 700, maxObjectsInGet = 500)
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 1000, pageSize = 500, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        assertEquals(2, server.requestCount)
        assertEquals(listOf(500, 500), dispatcher.limits())
        // Anchored on the previous page's last id, never on an absolute position: mail landing
        // at the top between the two requests would otherwise skip or duplicate a page.
        assertEquals(listOf(null, "e500"), dispatcher.anchors())
        assertEquals(700, walk.ids.size)
        assertEquals((1..700).map { "e$it" }, walk.ids)
        assertEquals((1..700).map { "e$it" }, handed.ids)
        // The FIRST response's cursors: a queryState only describes the query it came from.
        assertEquals("q1", walk.queryState)
        assertEquals("e1", walk.emailState)
    }

    @Test fun queryEmailsWindow_handsEachPageOffAsItLandsAndRetainsNoMessages() = runBlocking {
        // ⭐ The shape this volet is FOR. The walk used to hold every `Email` of the window until
        // the last request came back, because its caller wrote them all at once; twenty fields
        // and eight collections per message is what made "All" a heap problem. It now hands each
        // page over as it lands and keeps one string per message.
        val dispatcher = FolderDispatcher(folderSize = 700, maxObjectsInGet = 500)
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 1000, pageSize = 500, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        assertEquals("one hand-off per network page", listOf(500, 200), handed.pages.map { it.size })
        // The load-bearing assertion: page 1 was handed over while only ONE request had been
        // sent, i.e. BEFORE the walk knew what page 2 held. Accumulating and calling back at the
        // end would read [2, 2] here and pass every other assertion in this test.
        assertEquals(listOf(1, 2), handed.requestsAtHandOff)
        assertEquals(700, walk.ids.size)
    }

    @Test fun queryEmailsWindow_asksTheSecondRequestOnlyForWhatTheWindowStillWants() = runBlocking {
        // The window (700) is not a multiple of the page (500): the second request asks for 200,
        // not another 500 — the window is a promise about how much to keep, not a floor.
        val dispatcher = FolderDispatcher(folderSize = 5000, maxObjectsInGet = 500)
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 700, pageSize = 500, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        assertEquals(listOf(500, 200), dispatcher.limits())
        assertEquals(700, walk.ids.size)
        assertEquals(700, handed.ids.size)
    }

    @Test fun queryEmailsWindow_isASingleRequestWhenTheWindowFitsInOnePage() = runBlocking {
        val dispatcher = FolderDispatcher(folderSize = 5000, maxObjectsInGet = 500)
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 50, pageSize = 50, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        assertEquals(1, server.requestCount)
        assertEquals(listOf(50), dispatcher.limits())
        assertEquals(50, walk.ids.size)
        assertEquals(listOf(50), handed.pages.map { it.size })
    }

    @Test fun queryEmailsWindow_stopsWhenTheFolderIsSmallerThanTheWindow() = runBlocking {
        // 300 messages under a 1000 window: one request, and no second round trip per refresh
        // for ever after.
        val dispatcher = FolderDispatcher(folderSize = 300, maxObjectsInGet = 500)
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 1000, pageSize = 500, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        assertEquals(1, server.requestCount)
        assertEquals(300, walk.ids.size)
        assertEquals(300, handed.ids.size)
    }

    @Test fun queryEmailsWindow_stopsOnTheFolderWhenTheWindowIsUnbounded() = runBlocking {
        // ⭐ The same folder under a window bigger than itself. `Int.MAX_VALUE` is the extreme case
        // of that, kept as the OVERFLOW GUARD on `minOf(target - fetched, pageSize)`; no shipped
        // window carries it any more (the largest is 10 000 — `core:data`, `SyncWindowScaleTest`),
        // and `core:jmap` cannot depend on that module to say so.
        //
        // What the wire has to show: the FOLDER ends the walk, not the window. One request, 300
        // ids, and no second round trip per refresh for ever after.
        val dispatcher = FolderDispatcher(folderSize = 300, maxObjectsInGet = 500)
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = Int.MAX_VALUE, pageSize = 500, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        assertEquals(1, server.requestCount)
        assertEquals(listOf(500), dispatcher.limits())
        assertEquals(300, walk.ids.size)
        assertEquals(300, handed.ids.size)
    }

    @Test fun queryEmailsWindow_walksAWholeFolderWhenTheWindowIsUnbounded() = runBlocking {
        // 1 200 messages past the cap this lot removed, on a server admitting 500 per get: three
        // requests, every id handed over exactly once, and every request asking for a full page
        // (`minOf(target - fetched, pageSize)` with target = Int.MAX_VALUE — the subtraction that
        // must not overflow). The caller reconciles the folder against the ids of the WHOLE walk,
        // so a walk that stopped at 1 000 here would DELETE the other 200 from the cache.
        val dispatcher = FolderDispatcher(folderSize = 1200, maxObjectsInGet = 500)
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = Int.MAX_VALUE, pageSize = 500, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        assertEquals(3, server.requestCount)
        assertEquals(listOf(500, 500, 500), dispatcher.limits())
        assertEquals(listOf(null, "e500", "e1000"), dispatcher.anchors())
        assertEquals((1..1200).map { "e$it" }, walk.ids)
        assertEquals((1..1200).map { "e$it" }, handed.ids)
        assertEquals("one hand-off per network page", listOf(500, 500, 200), handed.pages.map { it.size })
        // The first response's cursors, as ever — a queryState describes the query it came from.
        assertEquals("q1", walk.queryState)
    }

    @Test fun queryEmailsWindow_paginatesEvenWhenTheServerAdvertisesNothing() = runBlocking {
        // A silent server falls back to 100 ids per request. Capping WITHOUT paginating would
        // quietly shrink every window on such a server to 100 messages.
        val dispatcher = FolderDispatcher(folderSize = 5000, maxObjectsInGet = 100)
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(null), "acc1", "mbInbox",
            target = 500, pageSize = 100, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        assertEquals(5, server.requestCount)
        assertEquals(500, walk.ids.size)
        assertEquals(listOf(1, 2, 3, 4, 5), handed.requestsAtHandOff)
    }

    @Test fun queryEmailsWindow_keepsWalkingWhenTheGetReturnedFewerObjectsThanTheQueryListed() = runBlocking {
        // A message destroyed between the query and the back-referenced get: 500 ids listed, 499
        // objects returned. Paging on what came BACK would read that as an exhausted folder and
        // abandon the remaining 200 messages of the window, on every refresh.
        val dispatcher = FolderDispatcher(folderSize = 700, maxObjectsInGet = 500, omittedByGet = setOf("e250"))
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 1000, pageSize = 500, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        assertEquals(2, server.requestCount)
        assertEquals(699, walk.ids.size)
        assertEquals("e700", walk.ids.last())
        assertEquals(699, handed.ids.size)
    }

    @Test fun queryEmailsWindow_throwsRatherThanFinishingTheWalkItCouldNotFinish() {
        // ⛔ THE dangerous one, and the reason its name changed with this volet. It used to be
        // "never hand back the half it fetched": the caller reconciled the mailbox with the
        // return value, so half a window would have been read as "the folder holds these 500" and
        // deleted the other 200.
        //
        // The pages are now written as they land, so the half IS in the cache — and the guard
        // moved with it: the failure propagates UNCAUGHT, so the walk never returns, so the
        // caller never reaches its reconcile and nothing is deleted (the write-through order is
        // `MailRepository`'s `fullQueryWriteThrough`, executed by `FullQueryWriteThroughTest`).
        // What this test still pins is the propagation, which is what the whole arrangement rests
        // on: a `catch` added here that returned the accumulated ids would put the deletion back.
        val dispatcher = FolderDispatcher(folderSize = 700, maxObjectsInGet = 500, failAt = 2)
        server.dispatcher = dispatcher
        val handed = Handed()
        try {
            runBlocking {
                client.queryEmailsWindow(
                    sessionAdvertisingGet(500), "acc1", "mbInbox",
                    target = 1000, pageSize = 500, auth = BasicAuth("u", "p"), onPage = collect(handed),
                )
            }
            throw AssertionError("expected JmapException — a walk that swallows a failure deletes mail")
        } catch (e: JmapException) {
            // queryEmailsPage reports the status in the message, not in httpCode (only postJmap
            // fills that in); what matters here is that the failure came out at all.
            assertTrue(e.message.orEmpty().contains("HTTP 500"))
        }
        assertEquals(2, server.requestCount)
        // The first page WAS handed over and has been written; that is the new contract, and the
        // reason nothing may delete on this path.
        assertEquals(listOf(500), handed.pages.map { it.size })
    }

    @Test fun queryEmailsWindow_fallsBackToAPositionWhenTheAnchorHasGone() = runBlocking {
        // The anchor can leave the folder between two requests (deleted, moved). Recover once on
        // an absolute position rather than failing the whole refresh — the same recovery the
        // scroll mediator makes.
        //
        // ⛔ And recover BEHIND where the anchor was, not at it. `anchorNotFound` means the list
        // has lost a row, so every index past the anchor has shifted DOWN by one: asking at the
        // count we had accumulated lands on the message AFTER the one that followed the anchor,
        // and that skipped message is not just missed — the caller reconciles the mailbox with
        // the ids that come back, so it is DELETED from the cache while the server still has it,
        // and no later delta brings it back. Overlapping instead is free: the walk de-duplicates.
        val dispatcher = FolderDispatcher(folderSize = 700, maxObjectsInGet = 500, anchorNotFoundAt = 2)
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 1000, pageSize = 500, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        assertEquals(3, server.requestCount)
        assertEquals(listOf(null, "e500", null), dispatcher.anchors())
        // 499, not 500: e500 (the anchor) is gone from the server's list, so e501 now sits at 499.
        assertEquals(499, dispatcher.queries[2]["position"]!!.jsonPrimitive.int)
        // The message that followed the vanished anchor came through, and was not deleted.
        assertTrue("e501 was skipped by the recovery", "e501" in walk.ids)
        assertEquals(700, walk.ids.size)
    }

    @Test fun queryEmailsWindow_handsTheOverlapOfARecoveryPageOverOnlyOnce() = runBlocking {
        // The recovery restarts one position BEHIND on purpose, and when the folder did not
        // actually shrink under it, that lands on a row the previous page already carried. The
        // walk must hand that repeat over ONCE:
        //  - twice written is only wasteful, but
        //  - twice COUNTED means the walk stops one message short of the window on every refresh,
        //    and the reconcile then deletes that message from the cache — the loss the
        //    under-shoot was chosen to avoid, reintroduced by the accounting.
        val dispatcher = FolderDispatcher(
            folderSize = 700, maxObjectsInGet = 500,
            anchorNotFoundAt = 2, anchorNotFoundShortensTheFolder = false,
        )
        server.dispatcher = dispatcher
        val handed = Handed()

        val walk = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 1000, pageSize = 500, auth = BasicAuth("u", "p"), onPage = collect(handed),
        )

        // The recovery really did overlap, or this test proves nothing: it asked from position
        // 499 of a folder still 700 long, so its 201 ids start at e500 — which page 1 ended on.
        assertEquals(3, server.requestCount)
        assertEquals(499, dispatcher.queries[2]["position"]!!.jsonPrimitive.int)
        assertEquals(listOf(500, 200), handed.pages.map { it.size })
        assertEquals("a message was handed over twice", handed.ids.distinct().size, handed.ids.size)
        assertEquals("the ids the walk kept are not the ones it handed over", walk.ids, handed.ids)
        assertEquals("the repeat was counted against the window", 700, walk.ids.size)
        assertEquals((1..700).map { "e$it" }, walk.ids)
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

        const val SEND_JSON = """
            {
              "methodResponses": [
                ["Email/set", {
                  "accountId": "acc1",
                  "created": { "draft": { "id": "e123", "threadId": "t1", "blobId": "b1" } }
                }, "e0"],
                ["EmailSubmission/set", {
                  "accountId": "acc1",
                  "created": { "sub": { "id": "sub1", "undoStatus": "final" } }
                }, "s0"]
              ]
            }
        """

        const val COPY_JSON = """
            {
              "methodResponses": [
                ["Email/copy", {
                  "fromAccountId": "s",
                  "accountId": "sub",
                  "created": { "copy": { "id": "e999", "threadId": "t9", "blobId": "b9" } }
                }, "c0"],
                ["Email/set", { "accountId": "s", "destroyed": ["e123"] }, "d0"]
              ]
            }
        """

        const val COPY_NOTCREATED_JSON = """
            {
              "methodResponses": [
                ["Email/copy", {
                  "fromAccountId": "s",
                  "accountId": "sub",
                  "notCreated": { "copy": { "type": "notFound" } }
                }, "c0"],
                ["Email/set", { "accountId": "s", "notDestroyed": { "e123": { "type": "notFound" } } }, "d0"]
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

        // What a Stalwart answers to an unauthenticated GET of its session resource: HTTP 200,
        // no user, no account — not a 401 (issue #137).
        const val ANONYMOUS_SESSION_JSON = """
            {
              "capabilities": {
                "urn:ietf:params:jmap:core": {}
              },
              "accounts": {},
              "primaryAccounts": {},
              "username": "",
              "apiUrl": "https://mail.example.com/jmap/api/",
              "state": "s0"
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

        /** Three ids matched, two objects returned — the shape `maxObjectsInGet` produces. */
        const val SEARCH_SHORT_GET_JSON = """
            {
              "methodResponses": [
                ["Email/query", {"accountId":"acc1","queryState":"q1","ids":["e1","e2","e3"]}, "q0"],
                ["Email/get", {
                  "accountId": "acc1",
                  "state": "e1",
                  "notFound": [],
                  "list": [
                    {"id":"e1","subject":"Invoice 1","from":[{"email":"alice@example.com"}]},
                    {"id":"e2","subject":"Invoice 2","from":[{"email":"bob@example.com"}]}
                  ]
                }, "g0"]
              ],
              "sessionState": "abc"
            }
        """

        /** The witness for it: two matched, the same two returned. */
        const val SEARCH_WHOLE_GET_JSON = """
            {
              "methodResponses": [
                ["Email/query", {"accountId":"acc1","queryState":"q1","ids":["e1","e2"]}, "q0"],
                ["Email/get", {
                  "accountId": "acc1",
                  "state": "e1",
                  "notFound": [],
                  "list": [
                    {"id":"e1","subject":"Invoice 1","from":[{"email":"alice@example.com"}]},
                    {"id":"e2","subject":"Invoice 2","from":[{"email":"bob@example.com"}]}
                  ]
                }, "g0"]
              ],
              "sessionState": "abc"
            }
        """

        /** A query response carrying no `ids` at all: the server said nothing about what matched. */
        const val SEARCH_NO_IDS_JSON = """
            {
              "methodResponses": [
                ["Email/query", {"accountId":"acc1","queryState":"q1"}, "q0"],
                ["Email/get", {"accountId":"acc1","state":"e1","notFound":[],"list":[]}, "g0"]
              ],
              "sessionState": "abc"
            }
        """

        const val FORBIDDEN_ERROR_JSON = """
            {
              "methodResponses": [
                ["error", {"type":"forbidden"}, "c0"]
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
