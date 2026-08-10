package app.gridlink.core.jmap

import app.gridlink.core.jmap.model.ContactCardGroup
import app.gridlink.core.jmap.model.ContactCardWrite
import app.gridlink.core.jmap.model.CrawlPage
import app.gridlink.core.jmap.model.Email
import app.gridlink.core.jmap.model.EmailAddress
import app.gridlink.core.jmap.model.EmailBodyPart
import app.gridlink.core.jmap.model.EmailChangesResult
import app.gridlink.core.jmap.model.EmailIdPage
import app.gridlink.core.jmap.model.EmailPage
import app.gridlink.core.jmap.model.EmailQueryChangesResult
import app.gridlink.core.jmap.model.EmailSetResult
import app.gridlink.core.jmap.model.Identity
import app.gridlink.core.jmap.model.JmapAddressBook
import app.gridlink.core.jmap.model.JmapSession
import app.gridlink.core.jmap.model.Mailbox
import app.gridlink.core.jmap.model.PushSubscription
import app.gridlink.core.jmap.model.StateChange
import app.gridlink.core.jmap.model.Quota
import app.gridlink.core.jmap.model.SearchPage
import app.gridlink.core.jmap.model.SearchQuery
import app.gridlink.core.jmap.model.SieveScript
import app.gridlink.core.jmap.model.UploadedBlob
import app.gridlink.core.jmap.model.VacationResponse
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.Closeable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal JMAP client: fetch the Session resource and run method calls.
 * Pure JVM (no Android), so it is unit-testable with MockWebServer.
 */
class JmapClient internal constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    /** Public constructor for app code — uses a default OkHttp client. */
    constructor() : this(defaultHttpClient(), DefaultJson)

    /** GET the Session resource and parse it (RFC 8620 §2). */
    suspend fun fetchSession(sessionUrl: String, auth: JmapAuth): JmapSession =
        withContext(Dispatchers.IO) {
            var url = sessionUrl
            var retried = false
            while (true) {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", auth.authorizationHeader())
                    .header("Accept", "application/json")
                    .get()
                    .build()
                val session = httpClient.newCall(request).execute().use { response ->
                    // OkHttp drops the Authorization header when a redirect crosses hosts, so
                    // an autodiscovery redirect (RFC 8620 §2.2, e.g. fastmail.com/.well-known/jmap
                    // → api.fastmail.com/jmap/session) lands unauthenticated and 401s. Retry the
                    // redirect target once, re-authenticated. The scheme check forbids a cleartext
                    // downgrade (followSslRedirects(false) already refuses scheme switches).
                    val landedAt = response.request.url
                    if (!retried && response.code == 401 && response.priorResponse != null &&
                        landedAt.toString() != url && url.startsWith("${landedAt.scheme}://")
                    ) {
                        retried = true
                        url = landedAt.toString()
                        return@use null
                    }
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw JmapException(
                            "Session request failed: HTTP ${response.code} ${response.message}",
                            httpCode = response.code,
                        )
                    }
                    runCatching { json.decodeFromString<JmapSession>(body) }
                        .getOrElse { throw JmapException("Could not parse JMAP session", it) }
                }
                if (session != null) return@withContext upgradeSessionUrls(session, url)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("unreachable")
        }

    /** Fetch all mailboxes for an account via a single Mailbox/get call (RFC 8621 §2.1). */
    suspend fun getMailboxes(session: JmapSession, accountId: String, auth: JmapAuth): List<Mailbox> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.MAIL_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Mailbox/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull) // null = all mailboxes
                        }
                        add("c0")
                    }
                }
            }
            val request = Request.Builder()
                .url(session.apiUrl)
                .header("Authorization", auth.authorizationHeader())
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw JmapException("Mailbox/get failed: HTTP ${response.code} ${response.message}")
                }
                decodeList(body, "Mailbox/get", Mailbox.serializer())
            }
        }

    /**
     * Fetch the most recent emails in a mailbox: a batched Email/query +
     * Email/get, where Email/get back-references the query result (RFC 8620 §3.7).
     */
    suspend fun queryEmailsPage(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        limit: Int,
        auth: JmapAuth,
        position: Int = 0,
        calculateTotal: Boolean = false,
        // Stable paging: anchor on a known email id and start [anchorOffset] after
        // it, instead of an absolute [position] that shifts when new mail arrives.
        anchorId: String? = null,
        anchorOffset: Int = 0,
        // Only unread messages (notKeyword $seen) — lets "Mark all read" resolve its
        // targets server-side instead of from the cached window.
        unseenOnly: Boolean = false,
    ): EmailPage = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/query")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("filter") {
                            put("inMailbox", mailboxId)
                            if (unseenOnly) put("notKeyword", "\$seen")
                        }
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                        // Always uncollapsed — every message, not one representative per
                        // thread: the local cache is WYSIWYG (a folder's full contents,
                        // collapsed into conversations at display time only), and a collapsed
                        // query once made "empty Trash" destroy only thread representatives.
                        put("collapseThreads", false)
                        if (anchorId != null) {
                            put("anchor", anchorId)
                            put("anchorOffset", anchorOffset)
                        } else {
                            put("position", position)
                        }
                        put("limit", limit)
                        if (calculateTotal) put("calculateTotal", true)
                    }
                    add("q0")
                }
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("#ids") {
                            put("resultOf", "q0")
                            put("name", "Email/query")
                            put("path", "/ids")
                        }
                        putJsonArray("properties") {
                            listOf(
                                "id", "threadId", "subject", "preview",
                                "receivedAt", "from", "to", "hasAttachment", "keywords",
                            ).forEach { add(it) }
                        }
                    }
                    add("g0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Email/query failed: HTTP ${response.code} ${response.message}")
            }
            EmailPage(
                emails = decodeList(body, "Email/get", Email.serializer()),
                queryState = methodResponseArgs(body, "Email/query")["queryState"]?.jsonPrimitive?.contentOrNull,
                emailState = methodResponseArgs(body, "Email/get")["state"]?.jsonPrimitive?.contentOrNull,
                total = methodResponseArgs(body, "Email/query")["total"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    /**
     * Ids-only page of a mailbox query: a lone Email/query, no chained Email/get — for
     * resolving bulk-action targets (e.g. "Mark all read"), where fetching headers for
     * thousands of messages would be pure waste.
     */
    suspend fun queryEmailIds(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        limit: Int,
        auth: JmapAuth,
        position: Int = 0,
        calculateTotal: Boolean = false,
        unseenOnly: Boolean = false,
    ): EmailIdPage = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/query")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("filter") {
                            put("inMailbox", mailboxId)
                            if (unseenOnly) put("notKeyword", "\$seen")
                        }
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                        // Always uncollapsed — see [queryEmailsPage]: bulk targets must
                        // cover every message, never just thread representatives.
                        put("collapseThreads", false)
                        put("position", position)
                        put("limit", limit)
                        if (calculateTotal) put("calculateTotal", true)
                    }
                    add("q0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Email/query failed: HTTP ${response.code} ${response.message}")
            }
            val args = methodResponseArgs(body, "Email/query")
            EmailIdPage(
                ids = (args["ids"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                total = args["total"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    suspend fun queryEmails(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        limit: Int,
        auth: JmapAuth,
    ): List<Email> = queryEmailsPage(session, accountId, mailboxId, limit, auth).emails

    /**
     * Email/queryChanges for the folder sync query. Its arguments (filter, sort,
     * collapseThreads) MUST mirror [queryEmailsPage]'s exactly: a queryState is only
     * comparable against the same query (RFC 8620 §5.6).
     */
    suspend fun emailQueryChanges(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        sinceQueryState: String,
        maxChanges: Int,
        auth: JmapAuth,
    ): EmailQueryChangesResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/queryChanges")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("filter") { put("inMailbox", mailboxId) }
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                        put("collapseThreads", false)
                        put("sinceQueryState", sinceQueryState)
                        put("maxChanges", maxChanges)
                    }
                    add("qc0")
                }
            }
        }
        val body = postJmap(session, auth, payload)
        val args = methodResponseArgsOrNull(body, "Email/queryChanges")
            ?: return@withContext EmailQueryChangesResult(null, emptyList(), emptyList(), calculated = false)
        EmailQueryChangesResult(
            newQueryState = args["newQueryState"]?.jsonPrimitive?.contentOrNull,
            removed = args["removed"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            added = args["added"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull } ?: emptyList(),
            calculated = true,
        )
    }

    /** Email/changes for property-level deltas (created/updated/destroyed). */
    suspend fun emailChanges(
        session: JmapSession,
        accountId: String,
        sinceState: String,
        maxChanges: Int,
        auth: JmapAuth,
    ): EmailChangesResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/changes")
                    addJsonObject {
                        put("accountId", accountId)
                        put("sinceState", sinceState)
                        put("maxChanges", maxChanges)
                    }
                    add("c0")
                }
            }
        }
        val body = postJmap(session, auth, payload)
        val args = methodResponseArgsOrNull(body, "Email/changes")
            ?: return@withContext EmailChangesResult(null, emptyList(), emptyList(), emptyList(), hasMoreChanges = false, calculated = false)
        fun ids(key: String) = args[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        EmailChangesResult(
            newState = args["newState"]?.jsonPrimitive?.contentOrNull,
            created = ids("created"),
            updated = ids("updated"),
            destroyed = ids("destroyed"),
            hasMoreChanges = args["hasMoreChanges"]?.jsonPrimitive?.booleanOrNull ?: false,
            calculated = true,
        )
    }

    /** Email/get a specific set of ids with list (no-body) properties. */
    suspend fun getEmailsByIds(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonArray("ids") { ids.forEach { add(it) } }
                        putJsonArray("properties") {
                            listOf(
                                "id", "threadId", "subject", "preview",
                                "receivedAt", "from", "to", "hasAttachment", "keywords",
                            ).forEach { add(it) }
                        }
                    }
                    add("g0")
                }
            }
        }
        val body = postJmap(session, auth, payload)
        decodeList(body, "Email/get", Email.serializer())
    }

    /**
     * The subset of [ids] the server explicitly reports as `notFound` on an ids-only
     * `Email/get` — an authoritative existence check (a point lookup, not a snapshot
     * query, so it cannot be stale the way `Email/queryChanges` deltas can). Used by the
     * sync ghost sweep and returns ONLY ids listed in the response's `notFound` array: a
     * failed or malformed response throws rather than guessing, so a transient error can
     * never be mistaken for "these messages are gone".
     */
    suspend fun missingEmailIds(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): Set<String> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptySet()
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonArray("ids") { ids.forEach { add(it) } }
                        putJsonArray("properties") { add("id") }
                    }
                    add("g0")
                }
            }
        }
        val body = postJmap(session, auth, payload)
        val args = methodResponseArgs(body, "Email/get")
        args["notFound"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
            ?: emptySet()
    }

    /**
     * Full-text search across the account (Email/query `text` filter + Email/get).
     *
     * Returns both counts — see [SearchPage]: the caller has to know whether the query hit its cap
     * and whether the get brought back everything the query matched before it may put a number on
     * screen.
     */
    suspend fun searchEmails(
        session: JmapSession,
        accountId: String,
        query: SearchQuery,
        limit: Int,
        auth: JmapAuth,
        excludeMailboxIds: List<String> = emptyList(),
    ): SearchPage = withContext(Dispatchers.IO) {
        val filter = searchFilter(query, excludeMailboxIds)
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/query")
                    addJsonObject {
                        put("accountId", accountId)
                        put("filter", filter)
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                        put("limit", limit)
                    }
                    add("q0")
                }
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("#ids") {
                            put("resultOf", "q0")
                            put("name", "Email/query")
                            put("path", "/ids")
                        }
                        putJsonArray("properties") {
                            listOf(
                                "id", "threadId", "subject", "preview", "receivedAt",
                                "from", "hasAttachment", "keywords", "mailboxIds",
                            ).forEach { add(it) }
                        }
                    }
                    add("g0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Search failed: HTTP ${response.code} ${response.message}")
            }
            // mailboxId is left null: the caller (core:data) resolves each hit's folder
            // deterministically from the returned mailboxIds map — picking the map's
            // arbitrary first key here could route a multi-mailbox hit to Trash.
            SearchPage(
                emails = decodeList(body, "Email/get", Email.serializer()),
                // What the QUERY matched, kept apart from what the GET returned — same reason the
                // crawl keeps both (see [SearchPage]). Absent `ids` stays null rather than 0.
                matchedIds = methodResponseArgs(body, "Email/query")["ids"]?.jsonArray?.size,
            )
        }
    }

    /**
     * Crawl message headers (no filter) for the local search index: `Email/query` the whole account
     * newest-first from [position], then `Email/get` the lightweight header fields for those ids.
     * Bodies are not fetched here. Returns up to [limit] emails (fewer at the end of the mailbox).
     */
    suspend fun crawlHeaders(
        session: JmapSession,
        accountId: String,
        position: Int,
        limit: Int,
        auth: JmapAuth,
        excludeMailboxIds: List<String> = emptyList(),
    ): CrawlPage = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/query")
                    addJsonObject {
                        put("accountId", accountId)
                        // Don't index Trash/Junk into the local search index (same exclusion the
                        // server search and the IMAP walk apply), so a deleted message never
                        // surfaces in as-you-type results. A message still filed elsewhere is kept.
                        if (excludeMailboxIds.isNotEmpty()) putJsonObject("filter") {
                            putJsonArray("inMailboxOtherThan") { excludeMailboxIds.forEach { add(it) } }
                        }
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                        put("position", position)
                        put("limit", limit)
                    }
                    add("q0")
                }
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("#ids") {
                            put("resultOf", "q0")
                            put("name", "Email/query")
                            put("path", "/ids")
                        }
                        putJsonArray("properties") {
                            // Headers plus `preview` — responses stay tiny so the crawl reaches even
                            // years-old mail fast. Whole bodies are never fetched here; a real body
                            // search is served by the server's own full-text index. `preview` is the
                            // exception because the server already computed it, it costs a couple of
                            // hundred bytes a message, and it is the only body text the local index
                            // can offer offline (see EmailFtsEntity, schema v22).
                            listOf(
                                "id", "threadId", "subject", "preview", "receivedAt",
                                "from", "hasAttachment", "keywords", "mailboxIds",
                            ).forEach { add(it) }
                        }
                    }
                    add("g0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Index crawl failed: HTTP ${response.code} ${response.message}")
            }
            val queryCount = methodResponseArgs(body, "Email/query")["ids"]?.jsonArray?.size ?: 0
            CrawlPage(
                emails = decodeList(body, "Email/get", Email.serializer()),
                queryCount = queryCount,
            )
        }
    }

    /** Fetch a single email including recipients and decoded body values. */
    suspend fun getEmail(
        session: JmapSession,
        accountId: String,
        emailId: String,
        auth: JmapAuth,
    ): Email = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonArray("ids") { add(emailId) }
                        putJsonArray("properties") { EMAIL_BODY_PROPERTIES.forEach { add(it) } }
                        put("fetchHTMLBodyValues", true)
                        put("fetchTextBodyValues", true)
                    }
                    add("g0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Email/get failed: HTTP ${response.code} ${response.message}")
            }
            decodeList(body, "Email/get", Email.serializer()).firstOrNull()
                ?: throw JmapException("Email not found: $emailId")
        }
    }

    /**
     * Fetch just the raw header fields of a message, in original order with duplicates kept
     * (RFC 8621 §4.1.3 `headers` property). Cheap: no blob download, no body values — for the
     * reader's "view headers" action (issue #60). Returns an empty list if the id is not found.
     */
    suspend fun getEmailHeaders(
        session: JmapSession,
        accountId: String,
        emailId: String,
        auth: JmapAuth,
    ): List<app.gridlink.core.jmap.model.EmailHeader> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonArray("ids") { add(emailId) }
                        putJsonArray("properties") { add("id"); add("headers") }
                    }
                    add("g0")
                }
            }
        }
        val body = postJmap(session, auth, payload)
        decodeList(body, "Email/get", Email.serializer()).firstOrNull()?.headers ?: emptyList()
    }

    /**
     * Fetch several messages WITH their bodies in one Email/get (for prefetching the top of
     * the inbox into the local cache). Same properties as [getEmail] but many ids at once;
     * does NOT mark anything read.
     */
    suspend fun getEmailsWithBody(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonArray("ids") { ids.forEach { add(it) } }
                        putJsonArray("properties") { EMAIL_BODY_PROPERTIES.forEach { add(it) } }
                        put("fetchHTMLBodyValues", true)
                        put("fetchTextBodyValues", true)
                    }
                    add("g0")
                }
            }
        }
        val body = postJmap(session, auth, payload)
        decodeList(body, "Email/get", Email.serializer())
    }

    /** Fetch all emails in a thread (lightweight, no body) via Thread/get + Email/get (RFC 8621 §3). */
    suspend fun getThreadEmails(
        session: JmapSession,
        accountId: String,
        threadId: String,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Thread/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonArray("ids") { add(threadId) }
                    }
                    add("t0")
                }
                addJsonArray {
                    add("Email/get")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("#ids") {
                            put("resultOf", "t0")
                            put("name", "Thread/get")
                            put("path", "/list/*/emailIds")
                        }
                        putJsonArray("properties") {
                            listOf(
                                "id", "threadId", "subject", "preview", "receivedAt",
                                "from", "to", "hasAttachment", "keywords", "mailboxIds",
                            ).forEach { add(it) }
                        }
                    }
                    add("g0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Thread/get failed: HTTP ${response.code} ${response.message}")
            }
            decodeList(body, "Email/get", Email.serializer())
        }
    }

    /** Set or clear a keyword (e.g. "${'$'}seen", "${'$'}flagged") on an email. */
    /** Returns the new `Email/set` state so the caller can advance its sync cursor. */
    suspend fun setKeyword(
        session: JmapSession,
        accountId: String,
        emailId: String,
        keyword: String,
        value: Boolean,
        auth: JmapAuth,
    ): String? {
        val args = emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                putJsonObject(emailId) {
                    if (value) put("keywords/$keyword", true) else put("keywords/$keyword", JsonNull)
                }
            }
        }
        val result = emailSetResult(args)
        // errorType carries the per-id SetError type (RFC 8620 §5.3) so the repository can
        // tell an authoritative `notFound` (the id no longer exists — prune the cached row)
        // from other rejections, without parsing the human-readable message.
        result.failed[emailId]?.let { throw JmapException("Server rejected the keyword change ($it)", errorType = it) }
        return result.newState
    }

    /** Convenience for the \$seen keyword. Returns the new `Email/set` state. */
    suspend fun setSeen(session: JmapSession, accountId: String, emailId: String, seen: Boolean, auth: JmapAuth): String? =
        setKeyword(session, accountId, emailId, "\$seen", seen, auth)

    /**
     * Set or clear the \$seen keyword on many emails in ONE `Email/set` — over [postWithRetry],
     * like the bulk [move] — so "Mark all read" doesn't cost one round trip per message.
     * Returns the per-id outcome (no-op for an empty list).
     */
    suspend fun setSeenAll(
        session: JmapSession,
        accountId: String,
        emailIds: List<String>,
        seen: Boolean,
        auth: JmapAuth,
    ): EmailSetResult {
        if (emailIds.isEmpty()) return EmailSetResult(null, emptySet(), emptyMap())
        val args = emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                emailIds.forEach { id ->
                    putJsonObject(id) {
                        if (seen) put("keywords/\$seen", true) else put("keywords/\$seen", JsonNull)
                    }
                }
            }
        }
        return emailSetResult(args)
    }

    /** Move an email so it belongs to exactly [targetMailboxId]. Returns the new state.
     *  Throws when the server rejects the update (per-id `notUpdated`). */
    suspend fun move(
        session: JmapSession,
        accountId: String,
        emailId: String,
        targetMailboxId: String,
        auth: JmapAuth,
    ): String? {
        val args = emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                putJsonObject(emailId) {
                    putJsonObject("mailboxIds") { put(targetMailboxId, true) }
                }
            }
        }
        val result = emailSetResult(args)
        // errorType = the per-id SetError type, so callers can react to `notFound` (see setKeyword).
        result.failed[emailId]?.let { throw JmapException("Server rejected the move ($it)", errorType = it) }
        return result.newState
    }

    /**
     * Move many emails so each belongs to exactly [targetMailboxId], in ONE `Email/set`
     * (Codeberg #29) — over [postWithRetry], so a big batch is one request that still backs
     * off on the server's rate limit. Returns the per-id outcome (no-op for an empty list).
     */
    suspend fun move(
        session: JmapSession,
        accountId: String,
        emailIds: List<String>,
        targetMailboxId: String,
        auth: JmapAuth,
    ): EmailSetResult {
        if (emailIds.isEmpty()) return EmailSetResult(null, emptySet(), emptyMap())
        val args = emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                emailIds.forEach { id ->
                    putJsonObject(id) { putJsonObject("mailboxIds") { put(targetMailboxId, true) } }
                }
            }
        }
        return emailSetResult(args)
    }

    /** Permanently delete many emails in one `Email/set` (Codeberg #29). Returns the per-id
     *  outcome (no-op for an empty list). */
    suspend fun destroy(session: JmapSession, accountId: String, emailIds: List<String>, auth: JmapAuth): EmailSetResult {
        if (emailIds.isEmpty()) return EmailSetResult(null, emptySet(), emptyMap())
        val args = emailSet(session, auth) {
            put("accountId", accountId)
            putJsonArray("destroy") { emailIds.forEach { add(it) } }
        }
        return emailSetResult(args)
    }

    /** Create a mailbox (e.g. an Archive folder) and return its new id (RFC 8621 §2.5). */
    suspend fun createMailbox(
        session: JmapSession,
        accountId: String,
        name: String,
        role: String?,
        auth: JmapAuth,
        parentId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Mailbox/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("create") {
                            putJsonObject("new") {
                                put("name", name)
                                if (role != null) put("role", role)
                                if (parentId != null) put("parentId", parentId)
                            }
                        }
                    }
                    add("m0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "Mailbox/set")
        val created = args["created"]?.jsonObject?.get("new")?.jsonObject
        if (created != null) {
            return@withContext created["id"]?.jsonPrimitive?.content
                ?: throw JmapException("Mailbox create returned no id")
        }
        val type = args["notCreated"]?.jsonObject?.get("new")?.jsonObject
            ?.get("type")?.jsonPrimitive?.content
        throw JmapException("Couldn't create the '$name' folder" + (type?.let { " ($it)" } ?: ""))
    }

    /** Rename a mailbox (Mailbox/set update of `name`). */
    suspend fun renameMailbox(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        name: String,
        auth: JmapAuth,
    ) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") { add(Jmap.CORE_CAPABILITY); add(Jmap.MAIL_CAPABILITY) }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Mailbox/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("update") { putJsonObject(mailboxId) { put("name", name) } }
                    }
                    add("m0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "Mailbox/set")
        if (args["updated"]?.jsonObject?.containsKey(mailboxId) != true) {
            throw JmapException("Couldn't rename the folder")
        }
    }

    /**
     * Move a mailbox under a new parent (Mailbox/set update of `parentId`).
     *
     * 🔴 A null [parentId] is written as an explicit JSON null, not omitted. Omitting the key leaves
     * the mailbox exactly where it is, so "drag this folder back out to the top level" would return
     * success and do nothing at all. RFC 8621 §2 defines `parentId: null` as the top level, and that
     * is what has to go on the wire.
     *
     * Gated server-side by the same `myRights.mayRename` as a rename: §2 defines that right as "may
     * change the name **or parentId**". The UI checks it before offering the drag at all.
     */
    suspend fun moveMailbox(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        parentId: String?,
        auth: JmapAuth,
    ) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") { add(Jmap.CORE_CAPABILITY); add(Jmap.MAIL_CAPABILITY) }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Mailbox/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("update") {
                            putJsonObject(mailboxId) {
                                if (parentId == null) put("parentId", JsonNull) else put("parentId", parentId)
                            }
                        }
                    }
                    add("m0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "Mailbox/set")
        if (args["updated"]?.jsonObject?.containsKey(mailboxId) != true) {
            val type = args["notUpdated"]?.jsonObject?.get(mailboxId)?.jsonObject
                ?.get("type")?.jsonPrimitive?.content
            throw JmapException("Couldn't move the folder" + (type?.let { " ($it)" } ?: ""))
        }
    }

    /** Delete a mailbox (Mailbox/set destroy). */
    suspend fun deleteMailbox(session: JmapSession, accountId: String, mailboxId: String, auth: JmapAuth) =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") { add(Jmap.CORE_CAPABILITY); add(Jmap.MAIL_CAPABILITY) }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Mailbox/set")
                        addJsonObject {
                            put("accountId", accountId)
                            putJsonArray("destroy") { add(mailboxId) }
                            // Allow removing a folder that still has messages in it.
                            put("onDestroyRemoveEmails", true)
                        }
                        add("m0")
                    }
                }
            }
            val args = methodResponseArgs(postJmap(session, auth, payload), "Mailbox/set")
            val destroyed = args["destroyed"]?.jsonArray?.any { it.jsonPrimitive.content == mailboxId } == true
            if (!destroyed) throw JmapException("Couldn't delete the folder")
        }

    /** Fetch the identities (from-addresses) the user may send as (RFC 8621 §6). */
    suspend fun getIdentities(session: JmapSession, accountId: String, auth: JmapAuth): List<Identity> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.SUBMISSION_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Identity/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull)
                        }
                        add("i0")
                    }
                }
            }
            val request = Request.Builder()
                .url(session.apiUrl)
                .header("Authorization", auth.authorizationHeader())
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw JmapException("Identity/get failed: HTTP ${response.code} ${response.message}")
                }
                decodeList(body, "Identity/get", Identity.serializer())
            }
        }

    /**
     * Fetch the account's VacationResponse singleton (RFC 8621 §8), or null if
     * the server does not advertise the vacationresponse capability.
     */
    suspend fun getVacationResponse(session: JmapSession, accountId: String, auth: JmapAuth): VacationResponse? =
        withContext(Dispatchers.IO) {
            if (!session.capabilities.containsKey(Jmap.VACATION_CAPABILITY)) return@withContext null
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.VACATION_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("VacationResponse/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull) // null = the singleton
                        }
                        add("v0")
                    }
                }
            }
            val body = postJmap(session, auth, payload)
            decodeList(body, "VacationResponse/get", VacationResponse.serializer()).firstOrNull()
                ?: VacationResponse()
        }

    /**
     * Update the account's VacationResponse singleton (RFC 8621 §8). Sends all
     * editable fields, writing explicit nulls to clear the dates / message. The
     * server keeps the auto-reply server-side, so it works while the phone is off.
     */
    suspend fun setVacationResponse(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        vacation: VacationResponse,
    ): VacationResponse = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.VACATION_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("VacationResponse/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("update") {
                            putJsonObject("singleton") {
                                put("isEnabled", vacation.isEnabled)
                                put("fromDate", vacation.fromDate)
                                put("toDate", vacation.toDate)
                                put("subject", vacation.subject)
                                put("textBody", vacation.textBody)
                                put("htmlBody", vacation.htmlBody)
                            }
                        }
                    }
                    add("v0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "VacationResponse/set")
        if (args["updated"]?.jsonObject?.containsKey("singleton") != true) {
            val type = args["notUpdated"]?.jsonObject?.get("singleton")?.jsonObject
                ?.get("type")?.jsonPrimitive?.content
            throw JmapException("Couldn't save the auto-reply" + (type?.let { " ($it)" } ?: ""))
        }
        vacation.copy(id = "singleton")
    }

    /**
     * Fetch the account's Quota objects (RFC 9425), or an empty list if the
     * server doesn't advertise the quota capability.
     */
    suspend fun getQuotas(session: JmapSession, accountId: String, auth: JmapAuth): List<Quota> =
        withContext(Dispatchers.IO) {
            if (!session.capabilities.containsKey(Jmap.QUOTA_CAPABILITY)) return@withContext emptyList()
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.QUOTA_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("Quota/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull) // null = all quotas for the account
                        }
                        add("q0")
                    }
                }
            }
            decodeList(postJmap(session, auth, payload), "Quota/get", Quota.serializer())
        }

    /**
     * Fetch the account's Sieve scripts (RFC 9661), or an empty list if the
     * server doesn't advertise the sieve capability.
     */
    suspend fun getSieveScripts(session: JmapSession, accountId: String, auth: JmapAuth): List<SieveScript> =
        withContext(Dispatchers.IO) {
            if (!session.capabilities.containsKey(Jmap.SIEVE_CAPABILITY)) return@withContext emptyList()
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.SIEVE_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("SieveScript/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull)
                        }
                        add("s0")
                    }
                }
            }
            decodeList(postJmap(session, auth, payload), "SieveScript/get", SieveScript.serializer())
        }

    /** Ask the server to validate an uploaded Sieve blob; returns null if valid, else the error text. */
    suspend fun validateSieve(session: JmapSession, accountId: String, blobId: String, auth: JmapAuth): String? =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.SIEVE_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("SieveScript/validate")
                        addJsonObject {
                            put("accountId", accountId)
                            put("blobId", blobId)
                        }
                        add("s0")
                    }
                }
            }
            val args = methodResponseArgs(postJmap(session, auth, payload), "SieveScript/validate")
            when (val err = args["error"]) {
                null, JsonNull -> null
                else -> err.jsonObject["description"]?.jsonPrimitive?.contentOrNull
                    ?: err.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    ?: err.toString()
            }
        }

    /**
     * Create or update the named Sieve script from an already-uploaded blob and
     * make it the active script. Pass [existingId] to update in place, or null to
     * create. Throws [JmapException] if the server rejects the write.
     */
    suspend fun saveSieveScript(
        session: JmapSession,
        accountId: String,
        name: String,
        blobId: String,
        existingId: String?,
        auth: JmapAuth,
    ) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.SIEVE_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("SieveScript/set")
                    addJsonObject {
                        put("accountId", accountId)
                        if (existingId != null) {
                            putJsonObject("update") {
                                putJsonObject(existingId) { put("blobId", blobId) }
                            }
                            put("onSuccessActivateScript", existingId)
                        } else {
                            putJsonObject("create") {
                                putJsonObject("new") {
                                    put("name", name)
                                    put("blobId", blobId)
                                }
                            }
                            put("onSuccessActivateScript", "#new")
                        }
                    }
                    add("s0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "SieveScript/set")
        if (existingId != null) {
            if (args["updated"]?.jsonObject?.containsKey(existingId) != true) {
                val type = args["notUpdated"]?.jsonObject?.get(existingId)?.jsonObject
                    ?.get("description")?.jsonPrimitive?.content
                throw JmapException("Couldn't save filters" + (type?.let { " ($it)" } ?: ""))
            }
        } else if (args["created"]?.jsonObject?.get("new") == null) {
            val type = args["notCreated"]?.jsonObject?.get("new")?.jsonObject
                ?.get("description")?.jsonPrimitive?.content
            throw JmapException("Couldn't save filters" + (type?.let { " ($it)" } ?: ""))
        }
    }

    /** True when the session speaks JMAP for Contacts (RFC 9610), at either level it can. */
    fun supportsContacts(session: JmapSession): Boolean =
        session.capabilities.containsKey(Jmap.CONTACTS_CAPABILITY) ||
            session.accounts.values.any { Jmap.CONTACTS_CAPABILITY in it.accountCapabilities }

    /**
     * Fetch the account's address books (RFC 9610 §2), or an empty list if the
     * server doesn't advertise the contacts capability.
     */
    suspend fun getAddressBooks(session: JmapSession, accountId: String, auth: JmapAuth): List<JmapAddressBook> =
        withContext(Dispatchers.IO) {
            if (!supportsContacts(session)) return@withContext emptyList()
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.CONTACTS_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("AddressBook/get")
                        addJsonObject {
                            put("accountId", accountId)
                            put("ids", JsonNull)
                        }
                        add("ab0")
                    }
                }
            }
            decodeList(postJmap(session, auth, payload), "AddressBook/get", JmapAddressBook.serializer())
        }

    /**
     * Find the JMAP id of the ContactCard carrying this vCard UID, or null if the server
     * doesn't know it. The UID is the bridge between the CardDAV mirror this app syncs
     * and the JMAP objects it writes: rows arrive over DAV keyed by href, but a JMAP
     * update needs the server's own ContactCard id, and the UID is the one identifier
     * both sides store verbatim.
     */
    suspend fun queryContactCardId(session: JmapSession, accountId: String, uid: String, auth: JmapAuth): String? =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") {
                    add(Jmap.CORE_CAPABILITY)
                    add(Jmap.CONTACTS_CAPABILITY)
                }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("ContactCard/query")
                        addJsonObject {
                            put("accountId", accountId)
                            putJsonObject("filter") { put("uid", uid) }
                        }
                        add("cq0")
                    }
                }
            }
            methodResponseArgs(postJmap(session, auth, payload), "ContactCard/query")["ids"]
                ?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
        }

    /**
     * Create a ContactCard (RFC 9610 §3) in the given address book. Returns the
     * server-assigned card id, or throws [JmapException] with the server's reason.
     */
    suspend fun createContactCard(
        session: JmapSession,
        accountId: String,
        addressBookId: String,
        card: ContactCardWrite,
        auth: JmapAuth,
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.CONTACTS_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("ContactCard/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("create") {
                            putJsonObject("new") {
                                put("@type", "Card")
                                put("version", "1.0")
                                put("uid", card.uid)
                                putJsonObject("addressBookIds") { put(addressBookId, true) }
                                // A create takes the same group shapes as a patch, minus the
                                // nulls: an absent property and a removed one are the same thing
                                // on an object that does not exist yet.
                                ContactCardGroup.entries.forEach { group ->
                                    val value = contactCardGroupValue(group, card)
                                    if (value != JsonNull) put(contactCardGroupProperty(group), value)
                                }
                            }
                        }
                    }
                    add("cs0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "ContactCard/set")
        // Safe casts, not .jsonObject: RFC 8620 §5.3 makes `created` nullable and a refusing
        // server (Stalwart included) sends an explicit null, which .jsonObject throws on.
        (args["created"] as? JsonObject)?.get("new")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: throw JmapException(
                "Couldn't save the contact" + (setErrorDetail(args, "notCreated", "new")?.let { " ($it)" } ?: ""),
            )
    }

    /**
     * Update a ContactCard, patching ONLY the [touched] groups (RFC 8620 §5.3: an
     * update names the properties it changes and the server keeps the rest). This is
     * the whole reason the JMAP path is preferred over re-uploading a vCard: a photo,
     * a postal address, or an email label this app has no model for survives an edit
     * because the patch never mentions it.
     */
    suspend fun updateContactCard(
        session: JmapSession,
        accountId: String,
        cardId: String,
        card: ContactCardWrite,
        touched: Set<ContactCardGroup>,
        auth: JmapAuth,
    ): Unit = withContext(Dispatchers.IO) {
        if (touched.isEmpty()) return@withContext
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.CONTACTS_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("ContactCard/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("update") {
                            putJsonObject(cardId) {
                                touched.forEach { group ->
                                    // JsonNull removes the property outright — how a cleared
                                    // note or an emptied phone list is said in patch language.
                                    put(contactCardGroupProperty(group), contactCardGroupValue(group, card))
                                }
                            }
                        }
                    }
                    add("cs0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "ContactCard/set")
        // `updated` is nullable the same way `created` is; see createContactCard.
        if ((args["updated"] as? JsonObject)?.containsKey(cardId) != true) {
            throw JmapException(
                "Couldn't save the contact" + (setErrorDetail(args, "notUpdated", cardId)?.let { " ($it)" } ?: ""),
            )
        }
    }

    /** A SetError's human line: description when the server wrote one, else its type. */
    private fun setErrorDetail(args: JsonObject, listKey: String, id: String): String? =
        (args[listKey] as? JsonObject)?.get(id)?.jsonObject?.let {
            it["description"]?.jsonPrimitive?.contentOrNull
                ?: it["type"]?.jsonPrimitive?.contentOrNull
        }

    /**
     * Send a plain-text email: create a draft (Email/set) and submit it
     * (EmailSubmission/set) in one request, moving it to Sent on success.
     * Returns the created message's Email id (null if the server omitted it),
     * so an on-behalf send can re-file the Sent copy across accounts.
     */
    suspend fun sendEmail(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        identityId: String,
        from: EmailAddress,
        to: List<EmailAddress>,
        cc: List<EmailAddress> = emptyList(),
        bcc: List<EmailAddress> = emptyList(),
        subject: String,
        textBody: String,
        htmlBody: String? = null,
        draftMailboxId: String,
        sentMailboxId: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        attachments: List<EmailBodyPart> = emptyList(),
    ): String? = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
                add(Jmap.SUBMISSION_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("create") {
                            putJsonObject("draft") {
                                putJsonArray("from") { addJsonObject { addAddress(from) } }
                                putJsonArray("to") { to.forEach { addJsonObject { addAddress(it) } } }
                                if (cc.isNotEmpty()) {
                                    putJsonArray("cc") { cc.forEach { addJsonObject { addAddress(it) } } }
                                }
                                if (bcc.isNotEmpty()) {
                                    putJsonArray("bcc") { bcc.forEach { addJsonObject { addAddress(it) } } }
                                }
                                put("subject", subject)
                                if (inReplyTo.isNotEmpty()) {
                                    putJsonArray("inReplyTo") { inReplyTo.forEach { add(it) } }
                                }
                                if (references.isNotEmpty()) {
                                    putJsonArray("references") { references.forEach { add(it) } }
                                }
                                addAttachments(attachments)
                                putJsonObject("keywords") { put("\$draft", true); put("\$seen", true) }
                                putJsonObject("mailboxIds") { put(draftMailboxId, true) }
                                putJsonArray("textBody") {
                                    addJsonObject { put("partId", "textbody"); put("type", "text/plain") }
                                }
                                if (htmlBody != null) {
                                    putJsonArray("htmlBody") {
                                        addJsonObject { put("partId", "htmlbody"); put("type", "text/html") }
                                    }
                                }
                                putJsonObject("bodyValues") {
                                    putJsonObject("textbody") { put("value", textBody) }
                                    if (htmlBody != null) putJsonObject("htmlbody") { put("value", htmlBody) }
                                }
                            }
                        }
                    }
                    add("e0")
                }
                addJsonArray {
                    add("EmailSubmission/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("create") {
                            putJsonObject("sub") {
                                put("emailId", "#draft")
                                put("identityId", identityId)
                            }
                        }
                        putJsonObject("onSuccessUpdateEmail") {
                            putJsonObject("#sub") {
                                put("mailboxIds/$sentMailboxId", true)
                                put("mailboxIds/$draftMailboxId", JsonNull)
                                put("keywords/\$draft", JsonNull)
                            }
                        }
                    }
                    add("s0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Send failed: HTTP ${response.code} ${response.message}")
            }
            val emailArgs = methodResponseArgs(body, "Email/set")
            (emailArgs["notCreated"] as? JsonObject)?.get("draft")?.let {
                throw JmapException("Could not create the message: $it")
            }
            val subArgs = methodResponseArgs(body, "EmailSubmission/set")
            (subArgs["notCreated"] as? JsonObject)?.get("sub")?.let {
                throw JmapException("Could not send the message: $it")
            }
            createdEmailId(emailArgs)
        }
    }

    /** The Email id minted for the "draft" creation in an Email/set or Email/import response. */
    private fun createdEmailId(args: JsonObject): String? =
        (args["created"] as? JsonObject)?.get("draft")?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull

    /**
     * Send a message whose raw RFC 5322 bytes were built CLIENT-side (PGP/MIME:
     * multipart/signed or multipart/encrypted — the structured Email/set body
     * assembly cannot carry the protocol=/micalg= parameters). Uploads the raw
     * message as a blob, then one request: Email/import into Drafts +
     * EmailSubmission/set referencing it, moving it to Sent on success.
     * Verified working against Stalwart.
     */
    suspend fun importAndSendEmail(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        identityId: String,
        rawMessage: ByteArray,
        draftMailboxId: String,
        sentMailboxId: String,
    ): String? = withContext(Dispatchers.IO) {
        val blobId = uploadBlob(session, accountId, rawMessage, "message/rfc822", auth).blobId
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
                add(Jmap.SUBMISSION_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/import")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("emails") {
                            putJsonObject("draft") {
                                put("blobId", blobId)
                                putJsonObject("mailboxIds") { put(draftMailboxId, true) }
                                putJsonObject("keywords") { put("\$draft", true); put("\$seen", true) }
                            }
                        }
                    }
                    add("i0")
                }
                addJsonArray {
                    add("EmailSubmission/set")
                    addJsonObject {
                        put("accountId", accountId)
                        putJsonObject("create") {
                            putJsonObject("sub") {
                                put("emailId", "#draft")
                                put("identityId", identityId)
                            }
                        }
                        putJsonObject("onSuccessUpdateEmail") {
                            putJsonObject("#sub") {
                                put("mailboxIds/$sentMailboxId", true)
                                put("mailboxIds/$draftMailboxId", JsonNull)
                                put("keywords/\$draft", JsonNull)
                            }
                        }
                    }
                    add("s0")
                }
            }
        }
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Send failed: HTTP ${response.code} ${response.message}")
            }
            val importArgs = methodResponseArgs(body, "Email/import")
            (importArgs["notCreated"] as? JsonObject)?.get("draft")?.let {
                throw JmapException("Could not import the message: $it")
            }
            val subArgs = methodResponseArgs(body, "EmailSubmission/set")
            (subArgs["notCreated"] as? JsonObject)?.get("sub")?.let {
                throw JmapException("Could not send the message: $it")
            }
            createdEmailId(importArgs)
        }
    }

    /**
     * Re-file a message across accounts of one session: Email/copy it into [toAccountId]'s
     * [mailboxId], then destroy the original in [fromAccountId]. Used after an on-behalf send
     * (issue #31) to move the Sent copy from the login's account into the delegated sub-account's
     * own Sent mailbox. The destroy is an explicit second method call: Stalwart's
     * `onSuccessDestroyOriginal` targets the creation id instead of the copied id (verified),
     * so the spec'd one-step form silently leaves the original behind. Throws [JmapException]
     * if the copy fails; a failed destroy is reported by the same exception AFTER the copy
     * stands, so callers treating this as best-effort never lose the message.
     */
    suspend fun copyEmailToAccount(
        session: JmapSession,
        auth: JmapAuth,
        fromAccountId: String,
        toAccountId: String,
        emailId: String,
        mailboxId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/copy")
                    addJsonObject {
                        put("fromAccountId", fromAccountId)
                        put("accountId", toAccountId)
                        putJsonObject("create") {
                            putJsonObject("copy") {
                                put("id", emailId)
                                putJsonObject("mailboxIds") { put(mailboxId, true) }
                                putJsonObject("keywords") { put("\$seen", true) }
                            }
                        }
                    }
                    add("c0")
                }
                addJsonArray {
                    add("Email/set")
                    addJsonObject {
                        put("accountId", fromAccountId)
                        putJsonArray("destroy") { add(emailId) }
                    }
                    add("d0")
                }
            }
        }
        val body = postJmap(session, auth, payload)
        val copyArgs = methodResponseArgs(body, "Email/copy")
        (copyArgs["notCreated"] as? JsonObject)?.get("copy")?.let {
            throw JmapException("Could not file the sent copy: $it")
        }
        val destroyArgs = methodResponseArgs(body, "Email/set")
        (destroyArgs["notDestroyed"] as? JsonObject)?.get(emailId)?.let {
            throw JmapException("Sent copy filed, but the original wasn't removed: $it")
        }
    }

    /** Save a draft in the Drafts mailbox (no submission).
     *  [attachments] are uploaded blobs referenced by the draft, so re-saving an edited draft
     *  keeps the files it carried instead of shedding them (#63).
     *  [htmlBody] adds a text/html alternative, mirroring [sendEmail]: a draft saved without it
     *  loses whatever formatting it had the moment it is reopened, because the marks are read back
     *  out of that part and nowhere else.
     *  Returns the created draft's server id, so an edit can later replace it (#63). */
    suspend fun saveDraft(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        from: EmailAddress,
        to: List<EmailAddress>,
        cc: List<EmailAddress> = emptyList(),
        bcc: List<EmailAddress> = emptyList(),
        subject: String,
        textBody: String,
        draftMailboxId: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        attachments: List<EmailBodyPart> = emptyList(),
        htmlBody: String? = null,
    ): String? {
        val args = emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("create") {
                putJsonObject("draft") {
                    putJsonArray("from") { addJsonObject { addAddress(from) } }
                    // A draft may legitimately have no recipient yet (#69). Emit "to" only when
                    // there is one — an empty "to": [] is rejected on create by strict servers
                    // (e.g. Stalwart), which is what blocked saving a recipient-less draft.
                    if (to.isNotEmpty()) putJsonArray("to") { to.forEach { addJsonObject { addAddress(it) } } }
                    if (cc.isNotEmpty()) putJsonArray("cc") { cc.forEach { addJsonObject { addAddress(it) } } }
                    if (bcc.isNotEmpty()) putJsonArray("bcc") { bcc.forEach { addJsonObject { addAddress(it) } } }
                    put("subject", subject)
                    // Keep a reply draft threaded, so sending it later still joins its conversation.
                    if (inReplyTo.isNotEmpty()) putJsonArray("inReplyTo") { inReplyTo.forEach { add(it) } }
                    if (references.isNotEmpty()) putJsonArray("references") { references.forEach { add(it) } }
                    addAttachments(attachments)
                    putJsonObject("keywords") { put("\$draft", true); put("\$seen", true) }
                    putJsonObject("mailboxIds") { put(draftMailboxId, true) }
                    putJsonArray("textBody") {
                        addJsonObject { put("partId", "body"); put("type", "text/plain") }
                    }
                    if (htmlBody != null) {
                        putJsonArray("htmlBody") {
                            addJsonObject { put("partId", "htmlbody"); put("type", "text/html") }
                        }
                    }
                    putJsonObject("bodyValues") {
                        putJsonObject("body") { put("value", textBody) }
                        if (htmlBody != null) putJsonObject("htmlbody") { put("value", htmlBody) }
                    }
                }
            }
        }
        (args["notCreated"] as? JsonObject)?.get("draft")?.let {
            throw JmapException("Could not save the draft: $it")
        }
        return ((args["created"] as? JsonObject)?.get("draft") as? JsonObject)
            ?.get("id")?.jsonPrimitive?.contentOrNull
    }

    /**
     * Download a blob (attachment) via the session downloadUrl template, refusing anything past
     * [maxBytes]. The whole response is buffered — there is no framing to stream against — so the
     * ceiling is what keeps one message from dictating the app's memory: the announced
     * Content-Length is checked first, and the read itself stops at the ceiling for a server that
     * announces nothing (or lies).
     */
    suspend fun downloadBlob(
        session: JmapSession,
        accountId: String,
        blobId: String,
        type: String?,
        name: String?,
        auth: JmapAuth,
        maxBytes: Long = DownloadLimits.ATTACHMENT_MAX_BYTES,
    ): ByteArray = withContext(Dispatchers.IO) {
        val template = session.downloadUrl ?: throw JmapException("Server has no downloadUrl")
        val url = template
            .replace("{accountId}", accountId)
            .replace("{blobId}", blobId)
            .replace("{type}", encodePathSegment(type ?: "application/octet-stream"))
            .replace("{name}", encodePathSegment(name ?: "attachment"))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader())
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw JmapException("Download failed: HTTP ${response.code} ${response.message}")
            }
            val declared = response.header("Content-Length")?.toLongOrNull()
            if (declared != null && declared > maxBytes) {
                throw ContentTooLargeException(
                    "Download is $declared bytes, over the $maxBytes limit.",
                    bytes = declared,
                    maxBytes = maxBytes,
                )
            }
            val body = response.body ?: return@use ByteArray(0)
            readAtMost(body.byteStream(), maxBytes)
        }
    }

    /** Read [stream] fully, or refuse as soon as it goes past [maxBytes]. */
    private fun readAtMost(stream: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw ContentTooLargeException(
                    "Download exceeds the $maxBytes limit.",
                    bytes = -1,
                    maxBytes = maxBytes,
                )
            }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    /** Upload bytes as a blob via the session uploadUrl template; returns its blobId. */
    suspend fun uploadBlob(
        session: JmapSession,
        accountId: String,
        bytes: ByteArray,
        type: String?,
        auth: JmapAuth,
    ): UploadedBlob = withContext(Dispatchers.IO) {
        val template = session.uploadUrl ?: throw JmapException("Server has no uploadUrl")
        val url = template.replace("{accountId}", accountId)
        val mediaType = (type ?: "application/octet-stream").toMediaTypeOrNull()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader())
            .post(bytes.toRequestBody(mediaType))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Upload failed: HTTP ${response.code} ${response.message}")
            }
            val obj = json.parseToJsonElement(body).jsonObject
            UploadedBlob(
                blobId = obj["blobId"]?.jsonPrimitive?.contentOrNull
                    ?: throw JmapException("Upload response had no blobId"),
                type = obj["type"]?.jsonPrimitive?.contentOrNull ?: (type ?: "application/octet-stream"),
                size = obj["size"]?.jsonPrimitive?.longOrNull ?: bytes.size.toLong(),
            )
        }
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Epoch-millis as a JMAP UTCDate (e.g. "2026-06-23T00:00:00Z"). */
    private fun utcDate(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()

    // ---- PushSubscription (RFC 8620 §7.2) ----------------------------------------------
    // Session-level: subscriptions belong to the credential, not to an account, so these
    // calls carry NO accountId and only need the core capability.

    /** All push subscriptions this credential holds on the server. */
    suspend fun getPushSubscriptions(session: JmapSession, auth: JmapAuth): List<PushSubscription> =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                putJsonArray("using") { add(Jmap.CORE_CAPABILITY) }
                putJsonArray("methodCalls") {
                    addJsonArray {
                        add("PushSubscription/get")
                        addJsonObject { put("ids", JsonNull) }
                        add("c0")
                    }
                }
            }
            decodeList(postJmap(session, auth, payload), "PushSubscription/get", PushSubscription.serializer())
        }

    /**
     * Create a push subscription pointing at [subscription].url (the UnifiedPush
     * endpoint). Returns it with the server-assigned id and the (possibly capped)
     * expires. The server then POSTs a PushVerification to the endpoint; confirm it
     * with [verifyPushSubscription] before any StateChange flows.
     */
    suspend fun createPushSubscription(
        session: JmapSession,
        auth: JmapAuth,
        subscription: PushSubscription,
    ): PushSubscription = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") { add(Jmap.CORE_CAPABILITY) }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("PushSubscription/set")
                    addJsonObject {
                        putJsonObject("create") {
                            // Built by hand: server-set fields (id) must be absent, not null.
                            putJsonObject("sub") {
                                put("deviceClientId", subscription.deviceClientId)
                                put("url", subscription.url)
                                subscription.keys?.let { keys ->
                                    putJsonObject("keys") {
                                        put("p256dh", keys.p256dh)
                                        put("auth", keys.auth)
                                    }
                                }
                                subscription.expires?.let { put("expires", it) }
                                subscription.types?.let { types ->
                                    putJsonArray("types") { types.forEach { add(it) } }
                                }
                            }
                        }
                    }
                    add("c0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "PushSubscription/set")
        val created = args["created"]?.jsonObject?.get("sub")?.jsonObject
            ?: throw JmapException(
                "Couldn't create the push subscription" +
                    (args["notCreated"]?.jsonObject?.get("sub")?.let { ": $it" } ?: ""),
            )
        subscription.copy(
            id = created["id"]?.jsonPrimitive?.contentOrNull ?: subscription.id,
            expires = created["expires"]?.jsonPrimitive?.contentOrNull ?: subscription.expires,
        )
    }

    /** Confirm a subscription with the verificationCode received through the endpoint. */
    suspend fun verifyPushSubscription(
        session: JmapSession,
        auth: JmapAuth,
        subscriptionId: String,
        verificationCode: String,
    ): Unit = withContext(Dispatchers.IO) {
        val args = updatePushSubscription(session, auth, subscriptionId) {
            put("verificationCode", verificationCode)
        }
        if (args["updated"]?.jsonObject?.containsKey(subscriptionId) != true) {
            throw JmapException("Couldn't verify the push subscription")
        }
    }

    /**
     * Push the subscription's expiry out to [expires] (UTCDate). Returns the value the
     * server applied, which it MAY have capped below the request (RFC 8620 §7.2).
     */
    suspend fun updatePushSubscriptionExpires(
        session: JmapSession,
        auth: JmapAuth,
        subscriptionId: String,
        expires: String,
    ): String = withContext(Dispatchers.IO) {
        val args = updatePushSubscription(session, auth, subscriptionId) { put("expires", expires) }
        val updated = args["updated"]?.jsonObject
        if (updated?.containsKey(subscriptionId) != true) {
            throw JmapException("Couldn't renew the push subscription")
        }
        // A non-null updated value carries the properties the server changed differently.
        updated[subscriptionId]?.let { it as? JsonObject }
            ?.get("expires")?.jsonPrimitive?.contentOrNull
            ?: expires
    }

    /** Destroy a subscription (sign-out / endpoint rotation). Already-gone is success. */
    suspend fun destroyPushSubscription(
        session: JmapSession,
        auth: JmapAuth,
        subscriptionId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("using") { add(Jmap.CORE_CAPABILITY) }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("PushSubscription/set")
                    addJsonObject { putJsonArray("destroy") { add(subscriptionId) } }
                    add("c0")
                }
            }
        }
        val args = methodResponseArgs(postJmap(session, auth, payload), "PushSubscription/set")
        val destroyed = args["destroyed"]?.jsonArray?.any { it.jsonPrimitive.content == subscriptionId } == true
        val notFound = args["notDestroyed"]?.jsonObject?.get(subscriptionId)
            ?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull == "notFound"
        if (!destroyed && !notFound) throw JmapException("Couldn't delete the push subscription")
    }

    /** Shared PushSubscription/set update envelope; returns the method response args. */
    private suspend fun updatePushSubscription(
        session: JmapSession,
        auth: JmapAuth,
        subscriptionId: String,
        patch: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val payload = buildJsonObject {
            putJsonArray("using") { add(Jmap.CORE_CAPABILITY) }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("PushSubscription/set")
                    addJsonObject {
                        putJsonObject("update") { putJsonObject(subscriptionId, patch) }
                    }
                    add("c0")
                }
            }
        }
        return methodResponseArgs(postJmap(session, auth, payload), "PushSubscription/set")
    }

    /**
     * Open a long-lived JMAP push connection (EventSource/SSE, RFC 8620 §7.3),
     * invoking [onStateChange] for each StateChange. Returns a Closeable to stop it.
     */
    fun openEventSource(
        session: JmapSession,
        auth: JmapAuth,
        onStateChange: (StateChange) -> Unit,
        onClosed: () -> Unit,
    ): Closeable {
        val template = session.eventSourceUrl
            ?: throw JmapException("Server does not advertise an eventSourceUrl")
        val url = template
            .replace("{types}", "Email,Mailbox")
            .replace("{closeafter}", "no")
            .replace("{ping}", PING_SECONDS.toString())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "text/event-stream")
            .build()
        // The server pings every PING_SECONDS; a read timeout a bit longer than that
        // turns a silently-dropped connection into onFailure so the caller can reconnect.
        val sseClient = httpClient.newBuilder()
            .readTimeout(PING_SECONDS + 30L, TimeUnit.SECONDS)
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { json.decodeFromString<StateChange>(data) }.getOrNull()?.let(onStateChange)
            }

            override fun onClosed(eventSource: EventSource) = onClosed()

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) = onClosed()
        }
        val eventSource = EventSources.createFactory(sseClient).newEventSource(request, listener)
        return Closeable { eventSource.cancel() }
    }

    /** Run an Email/set call with the given argument object, surfacing JMAP errors.
     *  Returns the response args (which carry `newState`, `updated`, `destroyed`, …). */
    private suspend fun emailSet(
        session: JmapSession,
        auth: JmapAuth,
        args: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val payload = buildJsonObject {
            putJsonArray("using") {
                add(Jmap.CORE_CAPABILITY)
                add(Jmap.MAIL_CAPABILITY)
            }
            putJsonArray("methodCalls") {
                addJsonArray {
                    add("Email/set")
                    addJsonObject(args)
                    add("s0")
                }
            }
        }
        return methodResponseArgs(postWithRetry(session.apiUrl, auth.authorizationHeader(), payload), "Email/set")
    }

    /** Per-id outcome of an Email/set response (updated/destroyed vs notUpdated/notDestroyed). */
    private fun emailSetResult(args: JsonObject): EmailSetResult {
        val done = buildSet {
            (args["updated"] as? JsonObject)?.keys?.let { addAll(it) }
            (args["destroyed"] as? JsonArray)?.forEach { add(it.jsonPrimitive.content) }
        }
        val failed = buildMap {
            for (key in listOf("notUpdated", "notCreated", "notDestroyed")) {
                (args[key] as? JsonObject)?.forEach { (id, err) ->
                    put(id, (err as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull ?: "unknown")
                }
            }
        }
        return EmailSetResult(args["newState"]?.jsonPrimitive?.contentOrNull, done, failed)
    }

    /** POST a JMAP request body and return the response text, throwing on HTTP failure. */
    private suspend fun postJmap(session: JmapSession, auth: JmapAuth, payload: JsonObject): String =
        postWithRetry(session.apiUrl, auth.authorizationHeader(), payload)

    /**
     * POST a JMAP request, retrying on the server's transient request-level limit
     * (`urn:ietf:params:jmap:error:limit`, HTTP 400) or a 429. Stalwart caps concurrent
     * requests per account (`maxConcurrentRequests`), so a bulk action firing many requests
     * while push/sync traffic is in flight can be rejected even though each request is valid;
     * the in-flight slots free up within milliseconds, so a short backoff lets it through
     * (RFC 8620 §3.6.1 sanctions retrying `error:limit`). Non-limit failures throw immediately.
     */
    private suspend fun postWithRetry(url: String, authHeader: String, payload: JsonObject): String {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val (code, message, body) = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { r ->
                    Triple(r.code, r.message, r.body?.string().orEmpty())
                }
            }
            if (code in 200..299) return body
            val transient = code == 429 || (code == 400 && body.contains(JMAP_ERROR_LIMIT))
            if (transient && attempt < LIMIT_RETRY_MAX) {
                attempt++
                delay(LIMIT_RETRY_BASE_MS * attempt)
                continue
            }
            // Status only, never a slice of the response: this message reaches logcat, the UI,
            // and the persisted outbox error, and the body is server-controlled text that can
            // carry mail content. The HTTP code is the diagnostic; it also travels structured
            // in [JmapException.httpCode] so callers can act on it without parsing prose.
            throw JmapException("JMAP request failed: HTTP $code $message", httpCode = code)
        }
    }

    /** Like [methodResponseArgs] but returns null for an error/missing response instead of throwing. */
    private fun methodResponseArgsOrNull(body: String, expectedMethod: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val responses = root["methodResponses"]?.jsonArray ?: return null
        for (entry in responses) {
            val call = entry.jsonArray
            if (call[0].jsonPrimitive.content == expectedMethod) return call[1].jsonObject
        }
        return null
    }

    /** Find the args of a named method response, throwing on a JMAP-level error. */
    private fun methodResponseArgs(body: String, expectedMethod: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw JmapException("Could not parse JMAP response", it) }
        val responses = root["methodResponses"]?.jsonArray
            ?: throw JmapException("Response missing methodResponses")
        for (entry in responses) {
            val call = entry.jsonArray
            val name = call[0].jsonPrimitive.content
            val args = call[1].jsonObject
            if (name == "error") {
                val type = args["type"]?.jsonPrimitive?.content ?: "unknown"
                throw JmapException("JMAP method error: $type", errorType = type)
            }
            if (name == expectedMethod) return args
        }
        throw JmapException("No $expectedMethod response found")
    }

    /**
     * Decode the `list` of a method response. A deserialization failure is re-thrown WITHOUT its
     * cause and without any excerpt: kotlinx.serialization puts a slice of the offending JSON in
     * its message, which for an `Email/get` is mail content (subject, sender, preview), and that
     * message ends up in logcat, on screen, and persisted in the outbox error column. The method
     * name is enough to place the failure.
     */
    private fun <T> decodeList(body: String, method: String, serializer: KSerializer<T>): List<T> {
        val list = methodResponseArgs(body, method)["list"]?.jsonArray ?: return emptyList()
        return try {
            list.map { json.decodeFromJsonElement(serializer, it) }
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and decodeFromJsonElement
            // reports a structurally wrong element the same way.
            throw JmapException("Could not decode the $method response")
        }
    }

    companion object {
        /** How often the server should ping the EventSource connection, in seconds. */
        private const val PING_SECONDS = 90L

        /** RFC 8620 §3.6.1 request-level limit error (e.g. Stalwart's maxConcurrentRequests). */
        private const val JMAP_ERROR_LIMIT = "urn:ietf:params:jmap:error:limit"

        /** Retries for the transient limit/429 error, with a linear backoff step. */
        private const val LIMIT_RETRY_MAX = 4
        private const val LIMIT_RETRY_BASE_MS = 120L

        /**
         * Defensively upgrade the session-advertised URLs from http:// to https:// when the
         * session itself was fetched over an https [sessionUrl]. A TLS reverse proxy that
         * doesn't honour X-Forwarded-Proto often advertises http:// apiUrl/downloadUrl/
         * uploadUrl/eventSourceUrl even though it served the session over https. Since we
         * reached the same host securely, those URLs are reachable over TLS too, so the
         * http:// scheme is a proxy artifact, not a real downgrade. Rewriting it keeps the
         * TLS requirement intact (we never touch cleartext on the wire) and transparently
         * fixes the common misconfiguration. If the session was somehow fetched over http
         * (impossible in the https-only autodiscovery flow), leave everything unchanged.
         */
        internal fun upgradeSessionUrls(session: JmapSession, sessionUrl: String): JmapSession {
            if (!sessionUrl.startsWith("https://", ignoreCase = true)) return session
            return session.copy(
                apiUrl = upgradeScheme(session.apiUrl)!!,
                downloadUrl = upgradeScheme(session.downloadUrl),
                uploadUrl = upgradeScheme(session.uploadUrl),
                eventSourceUrl = upgradeScheme(session.eventSourceUrl),
            )
        }

        /** Rewrite a leading `http://` to `https://`; leave null and already-https URLs untouched. */
        private fun upgradeScheme(url: String?): String? =
            if (url != null && url.startsWith("http://", ignoreCase = true)) {
                "https://" + url.substring("http://".length)
            } else {
                url
            }

        /**
         * The properties a full single-message fetch asks for: everything the reader draws.
         *
         * 🔴 One list, used by BOTH [getEmail] and [getEmailsWithBody], because they were two
         * copies of the same list and a property added to one of them silently did not apply to a
         * message that arrived through the other. The prefetch caches what it fetches, so a reader
         * opening a prefetched message would have been reading a different Email shape than one
         * opening a cold message, which is the kind of difference that looks like a random bug.
         *
         * The last two are not JMAP properties at all: RFC 8621 §4.1.3 lets a client name any
         * header, and these two are what the Unsubscribe action is built on (RFC 2369 / RFC 8058).
         * `asText` rather than `asURLs` — see [app.gridlink.core.jmap.model.Email.listUnsubscribe].
         */
        internal val EMAIL_BODY_PROPERTIES: List<String> = listOf(
            "id", "blobId", "threadId", "subject", "preview", "receivedAt",
            "from", "to", "cc", "bcc", "messageId", "inReplyTo", "references",
            "hasAttachment", "keywords",
            "htmlBody", "textBody", "attachments", "bodyValues",
            "header:List-Unsubscribe:asText",
            "header:List-Unsubscribe-Post:asText",
        )

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            // JMAP fields like cc/to/replyTo are `Type[]|null`; coerce an explicit
            // null to the property default (e.g. emptyList) instead of failing.
            coerceInputValues = true
        }

        internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Discovery relies on following 3xx redirects, but never down to cleartext: a
            // same-host HTTPS→HTTP redirect re-attaches the Authorization header, so an active
            // attacker could harvest credentials in plaintext. Refuse the TLS downgrade.
            .followSslRedirects(false)
            .build()
    }
}

/**
 * Build the `Email/query` filter for a [SearchQuery] (RFC 8621 §4.4.1): one condition per
 * non-empty field, AND-combined. `recipient` becomes a nested OR over `to` and `cc`, so a
 * message that only carries the address in copy still matches; the OR object is one condition
 * of the outer AND, never flattened into it (that would turn the whole query into an OR).
 * A single condition is emitted on its own, without the AND wrapper.
 *
 * Pure and separate from the request so the combination is unit-testable; callers must have
 * checked [SearchQuery.isEmpty] first (an empty filter would match the whole account).
 */
internal fun searchFilter(query: SearchQuery, excludeMailboxIds: List<String> = emptyList()): JsonObject {
    val conditions = mutableListOf<JsonObjectBuilder.() -> Unit>()
    if (query.text.isNotBlank()) conditions.add { put("text", query.text.trim()) }
    if (query.from.isNotBlank()) conditions.add { put("from", query.from.trim()) }
    if (query.recipient.isNotBlank()) {
        val recipient = query.recipient.trim()
        conditions.add {
            put("operator", "OR")
            putJsonArray("conditions") {
                addJsonObject { put("to", recipient) }
                addJsonObject { put("cc", recipient) }
            }
        }
    }
    if (query.subject.isNotBlank()) conditions.add { put("subject", query.subject.trim()) }
    if (query.hasAttachment) conditions.add { put("hasAttachment", true) }
    // RFC 8621 §4.4.1 `hasKeyword`, with the IMAP `\Flagged` flag under its JMAP name `$flagged`
    // (RFC 8621 §4.1.1) — escaped here because Kotlin would otherwise read it as a template.
    if (query.flagged) conditions.add { put("hasKeyword", "\$flagged") }
    query.afterMillis?.let { ms -> conditions.add { put("after", jmapUtcDate(ms)) } }
    query.beforeMillis?.let { ms -> conditions.add { put("before", jmapUtcDate(ms)) } }
    // Exclude Trash/Junk exactly as the IMAP walk skips them (parity across servers): a message
    // living ONLY in an excluded mailbox drops out, while one still filed elsewhere (Inbox and
    // Trash) is kept via its other mailbox. `inMailboxOtherThan` is one condition of the outer AND.
    if (excludeMailboxIds.isNotEmpty()) conditions.add {
        putJsonArray("inMailboxOtherThan") { excludeMailboxIds.forEach { add(it) } }
    }
    return buildJsonObject {
        if (conditions.size == 1) {
            conditions[0]()
        } else {
            put("operator", "AND")
            putJsonArray("conditions") {
                conditions.forEach { condition -> addJsonObject(condition) }
            }
        }
    }
}

/** The Card property a [ContactCardGroup] patches, by RFC 9553's own names. */
private fun contactCardGroupProperty(group: ContactCardGroup): String = when (group) {
    ContactCardGroup.NAME -> "name"
    ContactCardGroup.ORGANIZATION -> "organizations"
    ContactCardGroup.TITLE -> "titles"
    ContactCardGroup.EMAILS -> "emails"
    ContactCardGroup.PHONES -> "phones"
    ContactCardGroup.NOTE -> "notes"
    ContactCardGroup.PHOTO -> "media"
    // Custom fields travel as RFC 9555 vCardProps — jCard entries the server converts back into
    // vCard properties — so the CardDAV mirror of the same card carries the same X-GRIDLINK-FIELD
    // lines the DAV write path produces, and the app's vCard reader parses both paths' output
    // identically. A vendor-extension JSContact property would be invisible to that mirror.
    ContactCardGroup.CUSTOM -> "vCardProps"
}

/**
 * The JSContact value for a group, or [JsonNull] when the group is empty. In an update
 * patch the null REMOVES the property (a cleared note has to be said, not left unsaid);
 * a create just omits the property, which means the same thing on a card being born.
 *
 * NAME is never null: `name.full` is what every client renders, so it always travels,
 * and the components ride along only when there is a person-shaped name to carry —
 * an organization card is full-only, which is exactly how Stalwart stores its own.
 */
private fun contactCardGroupValue(group: ContactCardGroup, card: ContactCardWrite): JsonElement = when (group) {
    ContactCardGroup.NAME -> buildJsonObject {
        put("full", card.fullName)
        if (card.given.isNotBlank() || card.family.isNotBlank()) {
            putJsonArray("components") {
                if (card.family.isNotBlank()) {
                    addJsonObject {
                        put("kind", "surname")
                        put("value", card.family)
                    }
                }
                if (card.given.isNotBlank()) {
                    addJsonObject {
                        put("kind", "given")
                        put("value", card.given)
                    }
                }
            }
        }
    }
    ContactCardGroup.ORGANIZATION -> card.organization?.takeIf { it.isNotBlank() }?.let { org ->
        buildJsonObject { putJsonObject("o1") { put("name", org) } }
    } ?: JsonNull
    ContactCardGroup.TITLE -> card.title?.takeIf { it.isNotBlank() }?.let { title ->
        buildJsonObject {
            putJsonObject("t1") {
                put("name", title)
                put("kind", "title")
            }
        }
    } ?: JsonNull
    ContactCardGroup.EMAILS -> card.emails.filter { it.isNotBlank() }.ifEmpty { null }?.let { list ->
        buildJsonObject {
            list.forEachIndexed { i, address ->
                putJsonObject("e$i") { put("address", address) }
            }
        }
    } ?: JsonNull
    ContactCardGroup.PHONES -> card.phones.filter { it.isNotBlank() }.ifEmpty { null }?.let { list ->
        buildJsonObject {
            list.forEachIndexed { i, number ->
                putJsonObject("p$i") { put("number", number) }
            }
        }
    } ?: JsonNull
    ContactCardGroup.NOTE -> card.note?.takeIf { it.isNotBlank() }?.let { note ->
        buildJsonObject { putJsonObject("n1") { put("note", note) } }
    } ?: JsonNull
    // A Media object (RFC 9553 §2.6.4) with the photo as a data URI. A data URI rather than a
    // JMAP blob upload: it is one request instead of two, RFC 9553 sanctions it explicitly, and
    // RFC 9555 converts it straight into the vCard PHOTO the DAV mirror serves back.
    ContactCardGroup.PHOTO -> card.photo?.let { photo ->
        buildJsonObject {
            putJsonObject("m1") {
                put("kind", "photo")
                put("mediaType", photo.mediaType)
                put("uri", photo.dataUri)
            }
        }
    } ?: JsonNull
    // jCard entries (RFC 7095 via RFC 9555 §3.2): [name, params, type, value]. The value is the
    // structured [label, value] pair, exactly what X-GRIDLINK-FIELD:label;value spells in vCard.
    ContactCardGroup.CUSTOM -> card.customFields.ifEmpty { null }?.let { fields ->
        buildJsonArray {
            fields.forEach { field ->
                addJsonArray {
                    add("x-gridlink-field")
                    add(buildJsonObject {})
                    add("text")
                    addJsonArray {
                        add(field.label)
                        add(field.value)
                    }
                }
            }
        }
    } ?: JsonNull
}

/** JMAP UTCDate (RFC 8620 §1.4) from epoch millis. */
private fun jmapUtcDate(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()

/** Write a JMAP EmailAddress object ({name?, email}) into the current JSON object. */
private fun JsonObjectBuilder.addAddress(address: EmailAddress) {
    address.name?.let { put("name", it) }
    put("email", address.email)
}

/**
 * Write the `attachments` array of an Email/set create from already-uploaded blobs. Shared by
 * the send and the draft-save paths, so a draft carries exactly the same parts the same message
 * would carry if it were sent (#63 — a re-saved draft must not silently shed its files).
 * Parts without a blobId are skipped: the server can only reference uploaded blobs.
 */
private fun JsonObjectBuilder.addAttachments(attachments: List<EmailBodyPart>) {
    val blobs = attachments.filter { it.blobId != null }
    if (blobs.isEmpty()) return
    putJsonArray("attachments") {
        blobs.forEach { att ->
            addJsonObject {
                put("blobId", att.blobId)
                put("type", att.type ?: "application/octet-stream")
                att.name?.let { put("name", it) }
                // Inline (cid) images keep their Content-ID + inline disposition so the server
                // assembles multipart/related and the htmlBody's `cid:` refs resolve.
                put("disposition", att.disposition ?: "attachment")
                att.cid?.trim()?.trim('<', '>')
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("cid", it) }
                if (att.size > 0) put("size", att.size)
            }
        }
    }
}
