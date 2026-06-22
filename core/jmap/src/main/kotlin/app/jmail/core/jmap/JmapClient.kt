package app.jmail.core.jmap

import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.EmailAddress
import app.jmail.core.jmap.model.EmailBodyPart
import app.jmail.core.jmap.model.EmailChangesResult
import app.jmail.core.jmap.model.EmailPage
import app.jmail.core.jmap.model.EmailQueryChangesResult
import app.jmail.core.jmap.model.Identity
import app.jmail.core.jmap.model.JmapSession
import app.jmail.core.jmap.model.Mailbox
import app.jmail.core.jmap.model.StateChange
import app.jmail.core.jmap.model.UploadedBlob
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
                    throw JmapException("Session request failed: HTTP ${response.code} ${response.message}")
                }
                runCatching { json.decodeFromString<JmapSession>(body) }
                    .getOrElse { throw JmapException("Could not parse JMAP session", it) }
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
        query: String,
        limit: Int,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
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
                        putJsonObject("filter") { put("text", query) }
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
    suspend fun setKeyword(
        session: JmapSession,
        accountId: String,
        emailId: String,
        keyword: String,
        value: Boolean,
        auth: JmapAuth,
    ) = emailSet(session, auth) {
        put("accountId", accountId)
        putJsonObject("update") {
            putJsonObject(emailId) {
                if (value) put("keywords/$keyword", true) else put("keywords/$keyword", JsonNull)
            }
        }
    }

    /** Convenience for the \$seen keyword. */
    suspend fun setSeen(session: JmapSession, accountId: String, emailId: String, seen: Boolean, auth: JmapAuth) =
        setKeyword(session, accountId, emailId, "\$seen", seen, auth)

    /** Move an email so it belongs to exactly [targetMailboxId] (archive, trash, etc.). */
    suspend fun move(
        session: JmapSession,
        accountId: String,
        emailId: String,
        targetMailboxId: String,
        auth: JmapAuth,
    ) = emailSet(session, auth) {
        put("accountId", accountId)
        putJsonObject("update") {
            putJsonObject(emailId) {
                putJsonObject("mailboxIds") { put(targetMailboxId, true) }
            }
        }
    }

    /** Create a mailbox (e.g. an Archive folder) and return its new id (RFC 8621 §2.5). */
    suspend fun createMailbox(
        session: JmapSession,
        accountId: String,
        name: String,
        role: String?,
        auth: JmapAuth,
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

    /** Permanently destroy an email (used when there is no Trash mailbox). */
    suspend fun destroy(session: JmapSession, accountId: String, emailId: String, auth: JmapAuth) =
        emailSet(session, auth) {
            put("accountId", accountId)
            putJsonArray("destroy") { add(emailId) }
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
        subject: String,
        textBody: String,
        draftMailboxId: String,
    ) = emailSet(session, auth) {
        put("accountId", accountId)
        putJsonObject("create") {
            putJsonObject("draft") {
                putJsonArray("from") { addJsonObject { addAddress(from) } }
                putJsonArray("to") { to.forEach { addJsonObject { addAddress(it) } } }
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

    /** Run an Email/set call with the given argument object, surfacing JMAP errors. */
    private suspend fun emailSet(
        session: JmapSession,
        auth: JmapAuth,
        args: JsonObjectBuilder.() -> Unit,
    ) = withContext(Dispatchers.IO) {
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
            Unit
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
            .build()
    }
}

/** Write a JMAP EmailAddress object ({name?, email}) into the current JSON object. */
private fun JsonObjectBuilder.addAddress(address: EmailAddress) {
    address.name?.let { put("name", it) }
    put("email", address.email)
}
