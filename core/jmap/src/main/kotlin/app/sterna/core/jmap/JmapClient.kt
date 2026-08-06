package app.sterna.core.jmap

import app.sterna.core.jmap.model.CrawlPage
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailChangesResult
import app.sterna.core.jmap.model.EmailIdPage
import app.sterna.core.jmap.model.EmailPage
import app.sterna.core.jmap.model.EmailQueryChangesResult
import app.sterna.core.jmap.model.EmailSetResult
import app.sterna.core.jmap.model.Identity
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.PushSubscription
import app.sterna.core.jmap.model.StateChange
import app.sterna.core.jmap.model.Quota
import app.sterna.core.jmap.model.SearchPage
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.core.jmap.model.SieveScript
import app.sterna.core.jmap.model.UploadedBlob
import app.sterna.core.jmap.model.VacationResponse
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.Closeable
import java.io.IOException
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
import okhttp3.HttpUrl
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
                    // OkHttp drops the Authorization header when a redirect changes origin, so an
                    // autodiscovery redirect (RFC 8620 §2.2, e.g. fastmail.com/.well-known/jmap →
                    // api.fastmail.com/jmap/session) lands unauthenticated. Retry the redirect
                    // target once, re-authenticated.
                    //
                    // ⛔ The trigger is the MECHANICAL fact that dropped the header — the origin
                    // changed ([redirectDroppedAuthorization]) — never the answer we got back. A
                    // 401 is only one of the ways a server refuses an anonymous session request:
                    // Stalwart answers 200 with an empty session instead, which used to make the
                    // account be rejected as "This user has no JMAP mail account." (issue #137).
                    // `retried` keeps it to a single replay, whatever the redirect chain does.
                    val landedAt = response.request.url
                    if (!retried && response.priorResponse != null &&
                        redirectDroppedAuthorization(request.url, landedAt)
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
                queryCount = (methodResponseArgs(body, "Email/query")["ids"] as? JsonArray)?.size ?: 0,
            )
        }
    }

    /**
     * The newest [target] messages of a mailbox, in as many requests as the server's per-request
     * object limit ([pageSize]) needs, handed to [onPage] ONE REQUEST AT A TIME.
     *
     * Nothing outlives the loop iteration that decoded it: the peak is ONE server page of messages,
     * not the window. Not "zero" — a page has to exist to be handed over — but the difference
     * between O(page) and O(window) is the whole point, and it is what a window bigger than a heap
     * needs.
     *
     * ⛔ Why this exists at all: [queryEmailsPage] sends `Email/query` and a back-referenced
     * `Email/get` in ONE request, so the get's id list is produced by the server and the query's
     * `limit` is the only lever on it. Past `maxObjectsInGet` the server rejects the whole get
     * (RFC 8620 §5.1) and the call throws — which is exactly what "Messages to sync = All" (a
     * window of 1000 at the time; the largest one offers 10 000 today) did on a Stalwart
     * advertising 500: the folder's full-query fallback failed, so no sync
     * cursor was ever stored, so the next refresh took the same fallback again. A closed loop; the
     * folder never synced again.
     *
     * ⛔ Why it streams rather than accumulating: it used to hand back ONE page holding every
     * message of the window, because its caller then reconciled the mailbox in ONE
     * `replaceMailbox`, which DELETES what it is not given — a write per network page would have
     * emptied the folder down to the last page every time. That coupling is what capped the window
     * at what a phone can hold. It is broken by moving the DELETE, not by moving the write: the
     * caller now writes each page as it lands and reconciles ONCE at the end against
     * [WindowWalk.ids] (`MailRepository.syncMailbox` → `fullQueryWriteThrough`). What this walk
     * keeps to the end is therefore ids, not messages.
     *
     * ⛔ The safety that used to live in "never hand back a partial page" now lives in the
     * caller's control flow, and it must stay there: a failure mid-walk propagates from here
     * UNCAUGHT, so the reconcile is never reached and nothing is deleted. Pages already handed to
     * [onPage] have been written, and stay written. The worst case is a cache holding MORE than
     * the window, never a cache that lost mail. ⛔ Do not add a `catch` here that returns what was
     * fetched: that partial set would be reconciled as "these are the folder's contents".
     *
     * [onPage] receives ONLY the messages new to this walk — see the de-duplication below — and
     * its own failure propagates for the same reason.
     *
     * Paging is by ANCHOR on the previous page's last id (RFC 8620 §5.5), never by absolute
     * position: mail arriving at the top between two requests shifts every position and would
     * duplicate or skip a page. The one-shot fallback to a position is for the anchor having left
     * the folder mid-walk (`anchorNotFound`), the same recovery the scroll mediator makes.
     *
     * The cursors returned are the FIRST response's: a `queryState` describes the query it came
     * from, and handing back the last page's would let the next delta be computed against a state
     * that never described the whole folder.
     */
    suspend fun queryEmailsWindow(
        session: JmapSession,
        accountId: String,
        mailboxId: String,
        target: Int,
        pageSize: Int,
        auth: JmapAuth,
        onPage: suspend (List<Email>) -> Unit,
    ): WindowWalk = withContext(Dispatchers.IO) {
        // Ids in walk order, plus the set that de-duplicates them: the walk's pages can overlap (a
        // recovery page starts at a position the previous one already covered), and a duplicate
        // would be written twice and counted twice against the window. Ids and not messages —
        // that difference is this function's reason to look like this.
        val ids = ArrayList<String>()
        val seen = HashSet<String>()
        // The first response's two cursor STRINGS, not the first response. Keeping the page itself
        // to read two strings off it at the end pinned up to `pageSize` decoded messages — 500 on
        // Stalwart — alive for the whole walk, which is the very thing this function was rewritten
        // to stop doing. `sawFirst` and not a null check on the strings: the first response is
        // allowed to carry no cursors, and a later one must not then be promoted into its place.
        var sawFirst = false
        var firstQueryState: String? = null
        var firstEmailState: String? = null
        var seenIds = 0
        var limit = nextWindowPageLimit(fetched = 0, target = target, pageSize = pageSize, last = null)
        while (limit != null) {
            // The oldest id accumulated so far, i.e. where the previous request stopped.
            val anchor = ids.lastOrNull()
            val page = try {
                queryEmailsPage(
                    session, accountId, mailboxId, limit, auth,
                    anchorId = anchor,
                    anchorOffset = if (anchor != null) 1 else 0,
                )
            } catch (e: JmapException) {
                // The anchor left the folder mid-walk (deleted, moved elsewhere). Recover ONCE on
                // an absolute position. Any other failure propagates: see above, a partial window
                // deletes mail.
                //
                // ⛔ ONE BEHIND the count accumulated, not at it. `anchorNotFound` says the list
                // has lost a row, so everything past the anchor has shifted down by one: asking
                // at `accumulated.size` lands one message too far and skips the one that followed
                // the anchor. That message would not merely be missed — the caller reconciles the
                // mailbox with what this returns, so it would be DELETED from the cache while the
                // server still holds it, and no later delta would bring it back.
                //
                // Under-shooting is free and over-shooting loses mail, so under-shoot: a repeated
                // message is dropped by the accumulation's putIfAbsent. It corrects for the
                // anchor's own removal, which is what the error reports; a burst of removals in
                // the same instant can still shift further, and that is left to the ghost sweep
                // and the next full query rather than guessed at here.
                if (anchor == null || e.errorType != "anchorNotFound") throw e
                queryEmailsPage(
                    session, accountId, mailboxId, limit, auth,
                    position = (ids.size - 1).coerceAtLeast(0),
                )
            }
            if (!sawFirst) {
                sawFirst = true
                firstQueryState = page.queryState
                firstEmailState = page.emailState
            }
            // `seen.add` is the de-duplication the accumulation used to get from `putIfAbsent`,
            // and it has to happen HERE, before the hand-off: a message the recovery page repeats
            // must not be written twice, and must not count twice against the window.
            val fresh = page.emails.filter { seen.add(it.id) }
            fresh.forEach { ids += it.id }
            // Handed off NOW, while the next request has not been sent: this is the line that
            // turns a window into a stream. Its failure propagates (see the KDoc).
            if (fresh.isNotEmpty()) onPage(fresh)
            seenIds += page.queryCount
            limit = nextWindowPageLimit(
                fetched = ids.size,
                target = target,
                pageSize = pageSize,
                last = WalkedPage(
                    requested = limit,
                    queryCount = page.queryCount,
                    added = fresh.size,
                ),
            )
        }
        WindowWalk(
            ids = ids,
            queryState = firstQueryState,
            emailState = firstEmailState,
            // Every id the walk's queries listed, so a caller can still tell a short GET from an
            // exhausted folder. Not a single query's count — this walk is not a single query.
            queryCount = seenIds,
        )
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

    /** Email/get a specific set of ids with list (no-body) properties, split across as many
     *  requests as [JmapSession.getBatchSize] requires ([getInBatches]) and concatenated. */
    suspend fun getEmailsByIds(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
        getInBatches(session, ids) { batch ->
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
                            putJsonArray("ids") { batch.forEach { add(it) } }
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
            decodeList(postJmap(session, auth, payload), "Email/get", Email.serializer())
        }
    }

    /**
     * The subset of [ids] the server explicitly reports as `notFound` on an ids-only
     * `Email/get` — an authoritative existence check (a point lookup, not a snapshot
     * query, so it cannot be stale the way `Email/queryChanges` deltas can). Used by the
     * sync ghost sweep and returns ONLY ids listed in the response's `notFound` array: a
     * failed or malformed response throws rather than guessing, so a transient error can
     * never be mistaken for "these messages are gone".
     *
     * ⛔ That contract is why the batching here ([getInBatches]) must NOT gather a partial union:
     * the caller DELETES the rows this returns. A batch that never answered would otherwise hand
     * back "those messages are gone" about live mail — so a failing batch throws, exactly as an
     * unsplit call did.
     */
    suspend fun missingEmailIds(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): Set<String> = withContext(Dispatchers.IO) {
        getInBatches(session, ids) { batch ->
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
                            putJsonArray("ids") { batch.forEach { add(it) } }
                            putJsonArray("properties") { add("id") }
                        }
                        add("g0")
                    }
                }
            }
            val args = methodResponseArgs(postJmap(session, auth, payload), "Email/get")
            args["notFound"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        }.toSet()
    }

    /**
     * WHERE the server says [ids] are: each id it returns mapped to its `mailboxIds` set. Ids the
     * server does not return (`notFound`) are simply absent from the map.
     *
     * The point of asking (Codeberg #122): a JMAP id does not move with its message, so a list of
     * ids frozen when the user confirmed a permanent destroy says nothing about where those
     * messages are when the destroy finally runs — which can be days later. Only the server knows,
     * and only `mailboxIds` answers it. [getEmailsByIds] does NOT request that property, so
     * reusing it would hand back rows that cannot answer the question at all.
     *
     * ids-only + `mailboxIds`: no headers, no preview, nothing that would make this a second
     * download of the list. Split like every other get ([getInBatches], the server's
     * `maxObjectsInGet`), and — like [missingEmailIds] — a failed or malformed response THROWS
     * rather than answering a partial union: the caller DESTROYS what this reports as still in
     * place, so "the batch never answered" may never be read as "still there". The destroy that
     * calls this is retried by its worker; guessing here would be irreversible.
     */
    suspend fun mailboxIdsOf(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        getInBatches(session, ids) { batch ->
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
                            putJsonArray("ids") { batch.forEach { add(it) } }
                            putJsonArray("properties") {
                                add("id")
                                add("mailboxIds")
                            }
                        }
                        add("g0")
                    }
                }
            }
            val args = methodResponseArgs(postJmap(session, auth, payload), "Email/get")
            args["list"]?.jsonArray?.mapNotNull { entry ->
                val row = entry.jsonObject
                val id = row["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                // `{mailboxId: true}` per RFC 8621 §4.1.1; a false value would mean "not in it".
                val folders = row["mailboxIds"]?.jsonObject.orEmpty()
                    .filterValues { it.jsonPrimitive.booleanOrNull == true }.keys.toSet()
                id to folders
            }.orEmpty()
        }.toMap()
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
                            // Headers only — responses stay tiny so the crawl reaches even years-old
                            // mail fast. Body search is served by the server's own full-text index.
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

    /**
     * Fetch a single email including recipients and decoded body values.
     *
     * Its own request, deliberately NOT [getEmailsWithBody] with one id. The two look alike and
     * are not interchangeable: this one is the reader opening a message, so it does not go
     * through [postWithRetry] (a rate-limited open must fail now, not after four backoffs while
     * the reader waits at a spinner), and its failure text is shown to the reader as it is. What
     * the two DO share is the pair that must not drift — [EMAIL_BODY_PROPERTIES] and
     * [withUnsubscribeHeaderFallback] — so the message you open and the one prefetched into the
     * cache can never carry different fields.
     */
    suspend fun getEmail(
        session: JmapSession,
        accountId: String,
        emailId: String,
        auth: JmapAuth,
    ): Email = withContext(Dispatchers.IO) {
        withUnsubscribeHeaderFallback(session) { withUnsubscribeHeaders ->
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
                                EMAIL_BODY_PROPERTIES.forEach { add(it) }
                                if (withUnsubscribeHeaders) UNSUBSCRIBE_PROPERTIES.forEach { add(it) }
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
                    // The message is unchanged word for word — it is shown to the reader. Only
                    // the structured code is new, and only so the fallback above can tell a
                    // rejected property (400) from a refusal that must stand (401, 403, 5xx).
                    throw JmapException(
                        "Email/get failed: HTTP ${response.code} ${response.message}",
                        httpCode = response.code,
                    )
                }
                decodeList(body, "Email/get", Email.serializer()).firstOrNull()
                    ?: throw JmapException("Email not found: $emailId")
            }
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
    ): List<app.sterna.core.jmap.model.EmailHeader> = withContext(Dispatchers.IO) {
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
     * Fetch several messages WITH their bodies (for prefetching the top of the inbox into the
     * local cache), split across as many Email/get requests as [JmapSession.getBatchSize] requires
     * ([getInBatches]). Same properties as [getEmail] — literally the same list,
     * [EMAIL_BODY_PROPERTIES], so a prefetched body cannot come out poorer than an opened one —
     * but many ids at once; does NOT mark anything read.
     *
     * [withUnsubscribeHeaderFallback] sits INSIDE the batching, not around it: a server that
     * rejects the two header properties then replays the one batch that met the refusal, not
     * every batch already answered, and the verdict it records spares the batches that follow.
     */
    suspend fun getEmailsWithBody(
        session: JmapSession,
        accountId: String,
        ids: List<String>,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
        getInBatches(session, ids) { batch ->
            withUnsubscribeHeaderFallback(session) { withUnsubscribeHeaders ->
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
                                putJsonArray("ids") { batch.forEach { add(it) } }
                                putJsonArray("properties") {
                                    EMAIL_BODY_PROPERTIES.forEach { add(it) }
                                    if (withUnsubscribeHeaders) UNSUBSCRIBE_PROPERTIES.forEach { add(it) }
                                }
                                put("fetchHTMLBodyValues", true)
                                put("fetchTextBodyValues", true)
                            }
                            add("g0")
                        }
                    }
                }
                decodeList(postJmap(session, auth, payload), "Email/get", Email.serializer())
            }
        }
    }

    /**
     * Run a body-bearing `Email/get` that asks for the two `header:List-Unsubscribe*` properties,
     * and — if the server rejects them — run it once more without.
     *
     * These two calls are the ONLY ones that ask for them (RFC 8621 §4.1.3 lets any header be
     * requested by name): a folder page fetches dozens of rows every time it is scrolled and must
     * not pay for a header only the open message uses.
     *
     * **Why this exists at all.** A server that does not know a requested property may reject the
     * WHOLE `Email/get`. That would not mean "no unsubscribe button": it would mean no message
     * opens at all, on that account, for as long as the app is installed. Stalwart was measured
     * to accept them (and to answer null for a header a message does not carry); Cyrus, Fastmail
     * and James were not. A privacy nicety must not be able to break reading mail.
     *
     * **Which refusals count** ([isPropertyRejection]): the method-level `invalidArguments` /
     * `unknownMethod` of RFC 8620 §3.6.2, and a plain **HTTP 400** — a server that validates the
     * request before dispatching the method answers the second way, and the first version of this
     * fallback missed exactly that case. ⛔ Nothing else. A 401/403 is a credentials problem, a
     * 429 is a rate limit, a 5xx is the server being ill: replaying any of them would double the
     * traffic and hide a real failure behind a second one.
     *
     * The verdict is remembered per API URL for the life of the process, so the next message does
     * not spend a round trip rediscovering it — per URL and not globally, because a phone holds
     * accounts on several servers and one strict server must not cost the others their banner.
     */
    private suspend fun <T> withUnsubscribeHeaderFallback(
        session: JmapSession,
        call: suspend (withUnsubscribeHeaders: Boolean) -> T,
    ): T {
        val ask = session.apiUrl !in serversRefusingUnsubscribeHeaders
        return try {
            call(ask)
        } catch (e: JmapException) {
            if (!ask || !isPropertyRejection(e)) throw e
            serversRefusingUnsubscribeHeaders.add(session.apiUrl)
            call(false)
        }
    }

    /**
     * Every message of a thread (lightweight, no body) — `Thread/get`, then `Email/get` on the ids
     * it named, split across as many requests as [JmapSession.getBatchSize] requires
     * ([getInBatches]).
     *
     * ⛔ TWO requests, not one chained pair. This used to back-reference the thread's whole
     * membership into a single `Email/get` (a `resultOf` path down to each thread's `emailIds`),
     * which put the id list beyond any reach of ours: past `maxObjectsInGet` the server refuses
     * the get WHOLE (RFC 8620 §5.1) and
     * expanding a long conversation returned nothing at all. Splitting a back-reference is
     * impossible — the ids only exist server-side — so the reference has to go.
     *
     * The property set is this path's own, and `mailboxIds` in it is not decoration:
     * [MailRepository.fetchThreadMembers] files each member under the folder it names and SKIPS
     * any member without one. Reusing [getEmailsByIds] verbatim would have dropped it, and the
     * expansion would have fetched every message and persisted none.
     */
    suspend fun getThreadEmails(
        session: JmapSession,
        accountId: String,
        threadId: String,
        auth: JmapAuth,
    ): List<Email> = withContext(Dispatchers.IO) {
        val ids = threadEmailIds(session, accountId, threadId, auth)
        getInBatches(session, ids) { batch ->
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
                            putJsonArray("ids") { batch.forEach { add(it) } }
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
            decodeList(postJmap(session, auth, payload), "Email/get", Email.serializer())
        }
    }

    /**
     * The email ids a thread is made of, in the server's order (RFC 8621 §3) — the first half of
     * [getThreadEmails]. An unknown thread answers an empty list rather than throwing: a
     * conversation whose thread the server no longer knows is not an error, and the caller then
     * simply keeps what the cache gave it.
     */
    private suspend fun threadEmailIds(
        session: JmapSession,
        accountId: String,
        threadId: String,
        auth: JmapAuth,
    ): List<String> {
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
            }
        }
        val list = methodResponseArgs(postJmap(session, auth, payload), "Thread/get")["list"]
            ?.jsonArray ?: return emptyList()
        return list.flatMap { thread ->
            thread.jsonObject["emailIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
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
     * Set or clear the \$seen keyword on many emails — over [postWithRetry], like the bulk [move] —
     * so "Mark all read" doesn't cost one round trip per message. Split into requests of at most
     * [JmapSession.setBatchSize] ids ([setInBatches]); returns the aggregated per-id outcome (no-op
     * for an empty list).
     *
     * ⛔ THROWS on a transport failure, like [destroy] and unlike [move]: "Mark all read" dismisses
     * the notifications of the mail it marked only when this call came back without throwing. Turn
     * a dead connection into a quiet partial result and it would clear the notifications of mail
     * that is still unread. The batches already confirmed keep their local effect — the caller
     * commits per batch.
     */
    suspend fun setSeenAll(
        session: JmapSession,
        accountId: String,
        emailIds: List<String>,
        seen: Boolean,
        auth: JmapAuth,
    ): EmailSetResult = setInBatches(session, emailIds, rethrowTransportFailure = true) { batch ->
        emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                batch.forEach { id ->
                    putJsonObject(id) {
                        if (seen) put("keywords/\$seen", true) else put("keywords/\$seen", JsonNull)
                    }
                }
            }
        }
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
     * Move many emails so each belongs to exactly [targetMailboxId] (Codeberg #29) — over
     * [postWithRetry], so each request still backs off on the server's rate limit. Split into
     * requests of at most [JmapSession.setBatchSize] ids ([setInBatches]); returns the aggregated
     * per-id outcome (no-op for an empty list).
     *
     * Does NOT surface a transport failure: the ids of the failing batch and of every batch after
     * it come back in [EmailSetResult.failed] under [Jmap.SET_ERROR_TRANSPORT], so a caller that
     * used to turn the whole exception into "everything failed" now keeps the credit of the
     * batches that did go through.
     */
    suspend fun move(
        session: JmapSession,
        accountId: String,
        emailIds: List<String>,
        targetMailboxId: String,
        auth: JmapAuth,
    ): EmailSetResult = setInBatches(session, emailIds, rethrowTransportFailure = false) { batch ->
        emailSet(session, auth) {
            put("accountId", accountId)
            putJsonObject("update") {
                batch.forEach { id ->
                    putJsonObject(id) { putJsonObject("mailboxIds") { put(targetMailboxId, true) } }
                }
            }
        }
    }

    /**
     * Permanently delete many emails (Codeberg #29), split into requests of at most
     * [JmapSession.setBatchSize] ids ([setInBatches]). Returns the aggregated per-id outcome
     * (no-op for an empty list).
     *
     * ⛔ Unlike [move], this THROWS on a transport failure, including one that hits the third batch
     * of five: the destroy worker must RETRY a user-confirmed destroy, never abandon it. The
     * consequence is that the batches already destroyed do not go through the caller's local
     * bookkeeping on that run — harmless, because the replay is idempotent: the worker resends the
     * whole list, an already-destroyed id comes back `notFound`, and the caller counts that as a
     * success.
     */
    suspend fun destroy(
        session: JmapSession,
        accountId: String,
        emailIds: List<String>,
        auth: JmapAuth,
    ): EmailSetResult = setInBatches(session, emailIds, rethrowTransportFailure = true) { batch ->
        emailSet(session, auth) {
            put("accountId", accountId)
            putJsonArray("destroy") { batch.forEach { add(it) } }
        }
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

    /** Save a plain-text draft in the Drafts mailbox (no submission).
     *  [attachments] are uploaded blobs referenced by the draft, so re-saving an edited draft
     *  keeps the files it carried instead of shedding them (#63).
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
                    putJsonObject("bodyValues") {
                        putJsonObject("body") { put("value", textBody) }
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

    /**
     * Run [ids] through [oneBatch] in requests of at most [JmapSession.getBatchSize] ids, and
     * CONCATENATE the answers in order. The read-side twin of [setInBatches] — same reason it
     * lives here (this layer holds the session, hence the server's `maxObjectsInGet`), and RFC 8620
     * §5.1 has the server reject the WHOLE call past that limit.
     *
     * ⛔ One deliberate difference: there is NO partial credit here, and no `catch`. A failing batch
     * propagates, so the caller sees the failure it saw when the call was unsplit. Half an answer
     * would be worse than none — the callers overwrite the cache with what comes back
     * ([MailRepository.restoreAll]) or DELETE the rows it names ([missingEmailIds]). Partial credit
     * is a write-side idea; it does not transfer to a read.
     */
    private suspend fun <T> getInBatches(
        session: JmapSession,
        ids: List<String>,
        oneBatch: suspend (List<String>) -> List<T>,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(session.getBatchSize()).flatMap { oneBatch(it) }
    }

    /**
     * Run [emailIds] through [oneBatch] in requests of at most [JmapSession.setBatchSize] ids, and
     * aggregate the answers into ONE [EmailSetResult] — so callers keep the bookkeeping they had
     * when a bulk action was a single `Email/set`.
     *
     * Why this lives here and not in the repository: this is the only layer holding the session,
     * hence the server's `maxObjectsInSet`, and three call sites ([move]'s bulk form used by an
     * Undo and by the unarchive-on-reply path, [destroy], [setSeenAll]) reach the client directly.
     * A server rejects a `Email/set` carrying more ids than it advertises IN ONE BLOCK, so an
     * unsplit select-all fails wholesale — the very symptom this exists for.
     *
     * Aggregation:
     * - `done` and `failed` are the UNIONS over the batches;
     * - `newState` is the LAST batch's. Recording an older state is safe (the next delta simply
     *   re-reads wider); recording one NEWER than our local writes is the bug.
     *
     * On a transport failure (HTTP/network — [JmapException], [IOException]) we STOP: on a dead
     * connection every remaining batch would only grind through [postWithRetry]'s backoff. The
     * failing batch and every batch never attempted are reported, so nothing disappears silently.
     * [rethrowTransportFailure] picks which contract the caller has: `false` returns the partial
     * result with those ids marked [Jmap.SET_ERROR_TRANSPORT]; `true` rethrows (see [destroy]).
     *
     * ⛔ [Jmap.SET_ERROR_TRANSPORT] is deliberately not `"notFound"`: the repository prunes the
     * local row of a `notFound` id, so labelling an unreachable batch that way would delete live
     * messages out of the cache.
     */
    private suspend fun setInBatches(
        session: JmapSession,
        emailIds: List<String>,
        rethrowTransportFailure: Boolean,
        oneBatch: suspend (List<String>) -> JsonObject,
    ): EmailSetResult {
        if (emailIds.isEmpty()) return EmailSetResult(null, emptySet(), emptyMap())
        val batches = emailIds.chunked(session.setBatchSize())
        val done = mutableSetOf<String>()
        val failed = mutableMapOf<String, String>()
        var newState: String? = null
        batches.forEachIndexed { index, batch ->
            val args = try {
                oneBatch(batch)
            } catch (e: Exception) {
                if (e !is JmapException && e !is IOException) throw e
                if (rethrowTransportFailure) throw e
                // This batch AND every batch we will now not send.
                batches.drop(index).flatten().forEach { failed[it] = Jmap.SET_ERROR_TRANSPORT }
                return EmailSetResult(newState, done, failed)
            }
            val result = emailSetResult(args)
            done += result.done
            failed += result.failed
            result.newState?.let { newState = it }
        }
        return EmailSetResult(newState, done, failed)
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
            //
            // The limit error travels structured too, and for a precise reason: RFC 8620 §3.6.1
            // spells "too many requests" as an HTTP 400, the same status a server uses to reject
            // a property it does not know. Without this tag the unsubscribe-header fallback would
            // read a busy minute as "this server refuses the property" and drop the banner for
            // the rest of the process (see [isPropertyRejection]).
            throw JmapException(
                "JMAP request failed: HTTP $code $message",
                httpCode = code,
                errorType = JMAP_ERROR_LIMIT.takeIf { code == 400 && body.contains(it) },
            )
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

    /**
     * API URLs whose server rejected the `header:List-Unsubscribe*` properties, so the next
     * `Email/get` does not spend a round trip discovering it again. Per API URL rather than one
     * global flag: a phone can hold accounts on several servers, and one strict server must not
     * cost the others their unsubscribe banner. Memory only — a server that gains support gets it
     * back at the next app start.
     */
    private val serversRefusingUnsubscribeHeaders: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    companion object {
        /** How often the server should ping the EventSource connection, in seconds. */
        private const val PING_SECONDS = 90L

        /**
         * The properties of a message fetched WITH its body, shared by [getEmail] (opening one)
         * and [getEmailsWithBody] (prefetching many). One list for the two, so a body served from
         * the cache can never be poorer than the same body fetched on open — the kind of
         * difference that shows up as a field that is there on some messages and not others.
         */
        internal val EMAIL_BODY_PROPERTIES = listOf(
            "id", "blobId", "threadId", "subject", "preview", "receivedAt",
            "from", "to", "cc", "bcc", "messageId", "inReplyTo", "references",
            "hasAttachment", "keywords",
            "htmlBody", "textBody", "attachments", "bodyValues",
        )

        /**
         * The two header properties the reader's unsubscribe banner is built from (RFC 8621
         * §4.1.3 header form). Named here once so the request and its fallback cannot disagree.
         */
        internal val UNSUBSCRIBE_PROPERTIES = listOf(
            "header:List-Unsubscribe:asText",
            "header:List-Unsubscribe-Post:asText",
        )

        /**
         * Method-level errors that mean "I do not accept this argument" (RFC 8620 §3.6.2), i.e.
         * the ones a rejected property arrives as.
         */
        private val PROPERTY_REJECTION_ERRORS = setOf("invalidArguments", "unknownMethod")

        /**
         * Whether [e] is a server refusing a property we asked for — the only failure the
         * unsubscribe-header fallback replays (see [withUnsubscribeHeaderFallback]).
         *
         * Two shapes, because servers answer in two places: a method-level error inside a
         * perfectly good HTTP 200, and a flat **HTTP 400** from a request validator that never
         * reached the method. ⛔ Not 401/403 (credentials), not 429 (rate limit), not 5xx (the
         * server is ill) — replaying those doubles the traffic and buries the real reason. Nor
         * RFC 8620's `error:limit`, which is *also* an HTTP 400 and is likewise a rate limit.
         */
        private fun isPropertyRejection(e: JmapException): Boolean = when {
            e.errorType in PROPERTY_REJECTION_ERRORS -> true
            e.errorType == JMAP_ERROR_LIMIT -> false
            else -> e.httpCode == 400
        }

        /** RFC 8620 §3.6.1 request-level limit error (e.g. Stalwart's maxConcurrentRequests). */
        private const val JMAP_ERROR_LIMIT = "urn:ietf:params:jmap:error:limit"

        /** Retries for the transient limit/429 error, with a linear backoff step. */
        private const val LIMIT_RETRY_MAX = 4
        private const val LIMIT_RETRY_BASE_MS = 120L

        /**
         * Whether the redirect that took [requested] to [landedAt] cost us the `Authorization`
         * header — i.e. whether the request that actually reached the server was anonymous.
         *
         * The rule is OkHttp's own (`canReuseConnectionFor`, applied in `followUpRequest`): the
         * header is stripped when the **origin** changes, and an origin is the triplet
         * **scheme + host + port**. A different path on the same origin keeps it.
         *
         * ⛔ The port is part of the triplet, and not for the sake of completeness: `host:443` →
         * `host:8443` is a real deployment, and without it two MockWebServers on `localhost`
         * would count as the same origin and nothing here would be testable.
         *
         * ⛔ Different schemes answer **false**, so the replay can never resend credentials down
         * to cleartext after an https→http hop (`followSslRedirects(false)` refuses that hop
         * already; this keeps the decision from ever asking for it).
         *
         * ⛔ What this deliberately does NOT look at: the response's **status code**, and whether
         * the session that came back has an account. A Stalwart behind a cross-origin redirect
         * answers the anonymous request with **200 + an empty session** (`"username":""`,
         * `"accounts":{}`) rather than 401 (issue #137) — and a user who genuinely has no
         * mailbox answers exactly the same, so neither signal can decide anything.
         */
        internal fun redirectDroppedAuthorization(requested: HttpUrl, landedAt: HttpUrl): Boolean {
            if (requested.scheme != landedAt.scheme) return false
            return requested.host != landedAt.host || requested.port != landedAt.port
        }

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
