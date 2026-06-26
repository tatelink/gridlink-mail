package app.sterna.core.jmap

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailChangesResult
import app.sterna.core.jmap.model.EmailPage
import app.sterna.core.jmap.model.EmailQueryChangesResult
import app.sterna.core.jmap.model.Identity
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.StateChange
import app.sterna.core.jmap.model.Quota
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.core.jmap.model.SieveScript
import app.sterna.core.jmap.model.UploadedBlob
import app.sterna.core.jmap.model.VacationResponse
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.addJsonObject
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
            val request = Request.Builder()
                .url(sessionUrl)
                .header("Authorization", auth.authorizationHeader())
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw JmapException(
                        "Session request failed: HTTP ${response.code} ${response.message}",
                        httpCode = response.code,
                    )
                }
                val session = runCatching { json.decodeFromString<JmapSession>(body) }
                    .getOrElse { throw JmapException("Could not parse JMAP session", it) }
                upgradeSessionUrls(session, sessionUrl)
            }
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
                        putJsonObject("filter") { put("inMailbox", mailboxId) }
                        putJsonArray("sort") {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        }
                        put("collapseThreads", true)
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
                                "receivedAt", "from", "hasAttachment", "keywords",
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

    suspend fun queryEmails(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        limit: Int,
        auth: JmapAuth,
    ): List<Email> = queryEmailsPage(session, accountId, mailboxId, limit, auth).emails

    /** Email/queryChanges for the inbox-style (collapsed) query. */
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
                        put("collapseThreads", true)
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
                                "receivedAt", "from", "hasAttachment", "keywords",
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

    /** Full-text search across the account (Email/query `text` filter + Email/get). */
    suspend fun searchEmails(
        session: JmapSession,
        accountId: String,
        query: SearchQuery,
        limit: Int,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
        // One JMAP filter-condition per non-empty field, AND-combined.
        val conditions = mutableListOf<JsonObjectBuilder.() -> Unit>()
        if (query.text.isNotBlank()) conditions.add { put("text", query.text.trim()) }
        if (query.from.isNotBlank()) conditions.add { put("from", query.from.trim()) }
        if (query.subject.isNotBlank()) conditions.add { put("subject", query.subject.trim()) }
        if (query.hasAttachment) conditions.add { put("hasAttachment", true) }
        query.afterMillis?.let { ms -> conditions.add { put("after", utcDate(ms)) } }
        query.beforeMillis?.let { ms -> conditions.add { put("before", utcDate(ms)) } }

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
                            if (conditions.size == 1) {
                                conditions[0]()
                            } else {
                                put("operator", "AND")
                                putJsonArray("conditions") {
                                    conditions.forEach { cond -> addJsonObject(cond) }
                                }
                            }
                        }
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
                                "from", "hasAttachment", "keywords",
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
            decodeList(body, "Email/get", Email.serializer())
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
                        putJsonArray("properties") {
                            listOf(
                                "id", "threadId", "subject", "preview", "receivedAt",
                                "from", "to", "cc", "messageId", "references",
                                "hasAttachment", "keywords",
                                "htmlBody", "textBody", "attachments", "bodyValues",
                            ).forEach { add(it) }
                        }
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
                        putJsonArray("properties") {
                            listOf(
                                "id", "threadId", "subject", "preview", "receivedAt",
                                "from", "to", "cc", "messageId", "references",
                                "hasAttachment", "keywords",
                                "htmlBody", "textBody", "attachments", "bodyValues",
                            ).forEach { add(it) }
                        }
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
                                "from", "hasAttachment", "keywords",
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
        return args["newState"]?.jsonPrimitive?.contentOrNull
    }

    /** Convenience for the \$seen keyword. Returns the new `Email/set` state. */
    suspend fun setSeen(session: JmapSession, accountId: String, emailId: String, seen: Boolean, auth: JmapAuth): String? =
        setKeyword(session, accountId, emailId, "\$seen", seen, auth)

    /** Move an email so it belongs to exactly [targetMailboxId]. Returns the new state. */
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
        return args["newState"]?.jsonPrimitive?.contentOrNull
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

    /** Permanently destroy an email (used when there is no Trash mailbox). */
    /** Permanently delete an email. Returns the new `Email/set` state. */
    suspend fun destroy(session: JmapSession, accountId: String, emailId: String, auth: JmapAuth): String? =
        emailSet(session, auth) {
            put("accountId", accountId)
            putJsonArray("destroy") { add(emailId) }
        }["newState"]?.jsonPrimitive?.contentOrNull

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

    /**
     * Send a plain-text email: create a draft (Email/set) and submit it
     * (EmailSubmission/set) in one request, moving it to Sent on success.
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
    ) = withContext(Dispatchers.IO) {
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
                                if (attachments.isNotEmpty()) {
                                    putJsonArray("attachments") {
                                        attachments.forEach { att ->
                                            addJsonObject {
                                                put("blobId", att.blobId)
                                                put("type", att.type ?: "application/octet-stream")
                                                att.name?.let { put("name", it) }
                                                put("disposition", "attachment")
                                                if (att.size > 0) put("size", att.size)
                                            }
                                        }
                                    }
                                }
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
            Unit
        }
    }

    /** Save a plain-text draft in the Drafts mailbox (no submission). */
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
    ) = emailSet(session, auth) {
        put("accountId", accountId)
        putJsonObject("create") {
            putJsonObject("draft") {
                putJsonArray("from") { addJsonObject { addAddress(from) } }
                putJsonArray("to") { to.forEach { addJsonObject { addAddress(it) } } }
                if (cc.isNotEmpty()) putJsonArray("cc") { cc.forEach { addJsonObject { addAddress(it) } } }
                if (bcc.isNotEmpty()) putJsonArray("bcc") { bcc.forEach { addJsonObject { addAddress(it) } } }
                put("subject", subject)
                putJsonObject("keywords") { put("\$draft", true); put("\$seen", true) }
                putJsonObject("mailboxIds") { put(draftMailboxId, true) }
                putJsonArray("textBody") {
                    addJsonObject { put("partId", "body"); put("type", "text/plain") }
                }
                putJsonObject("bodyValues") {
                    putJsonObject("body") { put("value", textBody) }
                }
            }
        }
    }

    /** Download a blob (attachment) via the session downloadUrl template. */
    suspend fun downloadBlob(
        session: JmapSession,
        accountId: String,
        blobId: String,
        type: String?,
        name: String?,
        auth: JmapAuth,
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
            response.body?.bytes() ?: ByteArray(0)
        }
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
    ): JsonObject = withContext(Dispatchers.IO) {
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
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw JmapException("Email/set failed: HTTP ${response.code} ${response.message}")
            }
            methodResponseArgs(response.body?.string().orEmpty(), "Email/set")
        }
    }

    /** POST a JMAP request body and return the response text, throwing on HTTP failure. */
    private fun postJmap(session: JmapSession, auth: JmapAuth, payload: JsonObject): String {
        val request = Request.Builder()
            .url(session.apiUrl)
            .header("Authorization", auth.authorizationHeader())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("JMAP request failed: HTTP ${response.code} ${response.message}")
            }
            return body
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
                throw JmapException("JMAP method error: $type")
            }
            if (name == expectedMethod) return args
        }
        throw JmapException("No $expectedMethod response found")
    }

    private fun <T> decodeList(body: String, method: String, serializer: KSerializer<T>): List<T> {
        val list = methodResponseArgs(body, method)["list"]?.jsonArray ?: return emptyList()
        return list.map { json.decodeFromJsonElement(serializer, it) }
    }

    companion object {
        /** How often the server should ping the EventSource connection, in seconds. */
        private const val PING_SECONDS = 90L

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

/** Write a JMAP EmailAddress object ({name?, email}) into the current JSON object. */
private fun JsonObjectBuilder.addAddress(address: EmailAddress) {
    address.name?.let { put("name", it) }
    put("email", address.email)
}
