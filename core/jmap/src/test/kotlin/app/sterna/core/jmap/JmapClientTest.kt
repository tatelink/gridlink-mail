package app.sterna.core.jmap

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
        /** Ids the query lists but the get does NOT return (destroyed between the two calls). */
        private val omittedByGet: Set<String> = emptySet(),
    ) : Dispatcher() {
        /** The `Email/query` arguments of every request, in order. */
        val queries = mutableListOf<JsonObject>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            val args = Json.parseToJsonElement(body).jsonObject["methodCalls"]!!
                .jsonArray[0].jsonArray[1].jsonObject
            queries += args
            val n = queries.size
            if (failAt == n) return MockResponse().setResponseCode(500)
            val all = (1..folderSize).map { "e$it" }
            val anchor = args["anchor"]?.jsonPrimitive?.content
            val start = if (anchor != null) {
                val at = all.indexOf(anchor)
                if (at < 0 || anchorNotFoundAt == n) return methodError("anchorNotFound")
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

    @Test fun queryEmailsWindow_splitsAWindowBiggerThanTheServerPageIntoTwoRequests() = runBlocking {
        // ⭐ The lot's central case: 700 messages, a server admitting 500 per get, the "All"
        // window (1000). Two requests, and ONE page handed back — the caller reconciles the
        // mailbox with what it returns, so a page per request would delete the folder down to
        // the last one.
        val dispatcher = FolderDispatcher(folderSize = 700, maxObjectsInGet = 500)
        server.dispatcher = dispatcher

        val page = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 1000, pageSize = 500, auth = BasicAuth("u", "p"),
        )

        assertEquals(2, server.requestCount)
        assertEquals(listOf(500, 500), dispatcher.limits())
        // Anchored on the previous page's last id, never on an absolute position: mail landing
        // at the top between the two requests would otherwise skip or duplicate a page.
        assertEquals(listOf(null, "e500"), dispatcher.anchors())
        assertEquals(700, page.emails.size)
        assertEquals((1..700).map { "e$it" }, page.emails.map { it.id })
        // The FIRST response's cursors: a queryState only describes the query it came from.
        assertEquals("q1", page.queryState)
        assertEquals("e1", page.emailState)
    }

    @Test fun queryEmailsWindow_asksTheSecondRequestOnlyForWhatTheWindowStillWants() = runBlocking {
        // The window (700) is not a multiple of the page (500): the second request asks for 200,
        // not another 500 — the window is a promise about how much to keep, not a floor.
        val dispatcher = FolderDispatcher(folderSize = 5000, maxObjectsInGet = 500)
        server.dispatcher = dispatcher

        val page = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 700, pageSize = 500, auth = BasicAuth("u", "p"),
        )

        assertEquals(listOf(500, 200), dispatcher.limits())
        assertEquals(700, page.emails.size)
    }

    @Test fun queryEmailsWindow_isASingleRequestWhenTheWindowFitsInOnePage() = runBlocking {
        val dispatcher = FolderDispatcher(folderSize = 5000, maxObjectsInGet = 500)
        server.dispatcher = dispatcher

        val page = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 50, pageSize = 50, auth = BasicAuth("u", "p"),
        )

        assertEquals(1, server.requestCount)
        assertEquals(listOf(50), dispatcher.limits())
        assertEquals(50, page.emails.size)
    }

    @Test fun queryEmailsWindow_stopsWhenTheFolderIsSmallerThanTheWindow() = runBlocking {
        // 300 messages under a 1000 window: one request, and no second round trip per refresh
        // for ever after.
        val dispatcher = FolderDispatcher(folderSize = 300, maxObjectsInGet = 500)
        server.dispatcher = dispatcher

        val page = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 1000, pageSize = 500, auth = BasicAuth("u", "p"),
        )

        assertEquals(1, server.requestCount)
        assertEquals(300, page.emails.size)
    }

    @Test fun queryEmailsWindow_paginatesEvenWhenTheServerAdvertisesNothing() = runBlocking {
        // A silent server falls back to 100 ids per request. Capping WITHOUT paginating would
        // quietly shrink every window on such a server to 100 messages.
        val dispatcher = FolderDispatcher(folderSize = 5000, maxObjectsInGet = 100)
        server.dispatcher = dispatcher

        val page = client.queryEmailsWindow(
            sessionAdvertisingGet(null), "acc1", "mbInbox",
            target = 500, pageSize = 100, auth = BasicAuth("u", "p"),
        )

        assertEquals(5, server.requestCount)
        assertEquals(500, page.emails.size)
    }

    @Test fun queryEmailsWindow_keepsWalkingWhenTheGetReturnedFewerObjectsThanTheQueryListed() = runBlocking {
        // A message destroyed between the query and the back-referenced get: 500 ids listed, 499
        // objects returned. Paging on what came BACK would read that as an exhausted folder and
        // abandon the remaining 200 messages of the window, on every refresh.
        val dispatcher = FolderDispatcher(folderSize = 700, maxObjectsInGet = 500, omittedByGet = setOf("e250"))
        server.dispatcher = dispatcher

        val page = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 1000, pageSize = 500, auth = BasicAuth("u", "p"),
        )

        assertEquals(2, server.requestCount)
        assertEquals(699, page.emails.size)
        assertEquals("e700", page.emails.last().id)
    }

    @Test fun queryEmailsWindow_throwsRatherThanHandingBackTheHalfItFetched() {
        // ⛔ THE dangerous one: the caller reconciles the mailbox with what comes back, deleting
        // whatever is missing from it. Half a window would be read as "the folder holds these
        // 500" and delete the other 200; an empty one would erase the folder outright.
        val dispatcher = FolderDispatcher(folderSize = 700, maxObjectsInGet = 500, failAt = 2)
        server.dispatcher = dispatcher
        try {
            runBlocking {
                client.queryEmailsWindow(
                    sessionAdvertisingGet(500), "acc1", "mbInbox",
                    target = 1000, pageSize = 500, auth = BasicAuth("u", "p"),
                )
            }
            throw AssertionError("expected JmapException — a partial window deletes mail")
        } catch (e: JmapException) {
            // queryEmailsPage reports the status in the message, not in httpCode (only postJmap
            // fills that in); what matters here is that the failure came out at all.
            assertTrue(e.message.orEmpty().contains("HTTP 500"))
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun queryEmailsWindow_fallsBackToAPositionWhenTheAnchorHasGone() = runBlocking {
        // The anchor can leave the folder between two requests (deleted, moved). Recover once on
        // an absolute position rather than failing the whole refresh — the same recovery the
        // scroll mediator makes.
        val dispatcher = FolderDispatcher(folderSize = 700, maxObjectsInGet = 500, anchorNotFoundAt = 2)
        server.dispatcher = dispatcher

        val page = client.queryEmailsWindow(
            sessionAdvertisingGet(500), "acc1", "mbInbox",
            target = 1000, pageSize = 500, auth = BasicAuth("u", "p"),
        )

        assertEquals(3, server.requestCount)
        assertEquals(listOf(null, "e500", null), dispatcher.anchors())
        assertEquals(500, dispatcher.queries[2]["position"]!!.jsonPrimitive.int)
        assertEquals(700, page.emails.size)
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
