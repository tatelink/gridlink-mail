package app.gridlink.core.dav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Base64
import java.util.concurrent.TimeUnit

/** How a DAV request authenticates. Basic only, which is what Stalwart wants over TLS. */
data class DavCredentials(val username: String, val password: String) {
    fun authorizationHeader(): String {
        val token = Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        return "Basic $token"
    }
}

/** What kind of DAV collection is being talked to. The two protocols differ only in names. */
enum class DavKind(
    internal val wellKnown: String,
    internal val namespace: String,
    internal val prefix: String,
    internal val homeSet: PropKey,
    internal val dataProp: PropKey,
    internal val collectionType: String,
    internal val dataElement: String,
    /** The `Content-Type` a PUT into this kind of collection must carry. */
    internal val contentType: String,
) {
    CALENDAR(
        wellKnown = "/.well-known/caldav",
        namespace = "urn:ietf:params:xml:ns:caldav",
        prefix = "C",
        homeSet = PropKey.CALENDAR_HOME_SET,
        dataProp = PropKey.CALENDAR_DATA,
        collectionType = "urn:ietf:params:xml:ns:caldav|calendar",
        dataElement = "calendar-data",
        contentType = "text/calendar; charset=utf-8",
    ),
    ADDRESS_BOOK(
        wellKnown = "/.well-known/carddav",
        namespace = "urn:ietf:params:xml:ns:carddav",
        prefix = "C",
        homeSet = PropKey.ADDRESSBOOK_HOME_SET,
        dataProp = PropKey.ADDRESS_DATA,
        collectionType = "urn:ietf:params:xml:ns:carddav|addressbook",
        dataElement = "address-data",
        contentType = "text/vcard; charset=utf-8",
    ),
}

/** One calendar or address book on the server. */
data class DavCollection(
    /** Absolute URL, with a trailing slash. This is the collection's identity everywhere. */
    val url: String,
    val kind: DavKind,
    /** The server's label, or null when it has none and the UI must name it itself. */
    val displayName: String?,
    /** `#RRGGBB` if the server carries a colour. Stalwart does not, so this is normally null. */
    val color: String?,
)

/** One item (a `.ics` or a `.vcf`) as a sync reported it. */
data class DavItem(
    /** Path, percent-decoded, as returned by the server. Stable identity for the row. */
    val href: String,
    val etag: String?,
    /** The raw iCalendar or vCard text, or null when the server sent only the etag. */
    val data: String?,
)

/**
 * The outcome of one collection sync.
 *
 * [changed] and [removed] are what to apply. [token] is what to send next time, and
 * [fullResync] says the incremental path was refused and everything in [changed] is the complete
 * contents, so anything not named in it is gone.
 */
data class DavSyncResult(
    val changed: List<DavItem>,
    val removed: List<String>,
    val token: String?,
    val fullResync: Boolean,
)

/** Where a written item landed, as the server sees it. */
data class DavWriteResult(
    /** Absolute URL of the created resource. */
    val url: String,
    /**
     * The same resource as a percent-DECODED path.
     *
     * 🔴 This, not [url], is what a local mirror must key on. Every row this app stores came from a
     * MultiStatus `<D:href>`, which [MultiStatus] hands back decoded, so a row keyed on anything
     * else (the absolute URL, or the still-encoded path) does not match the row the very next sync
     * creates for the same item. The visible symptom is the new event appearing twice, which is a
     * miserable bug to chase because both copies are real and correct.
     */
    val href: String,
    /** The server's etag for the new item, when it sent one. Needed later to update or delete. */
    val etag: String?,
)

/**
 * A file fetched off a DAV server: the bytes, and what the server said they are.
 *
 * Not a data class on purpose — a generated `equals` over a [ByteArray] compares identity and would
 * quietly answer false for two copies of the same file, and nothing here wants to compare downloads.
 */
class DavDownload(val bytes: ByteArray, val contentType: String?)

class DavException(message: String, val code: Int? = null) : Exception(message)

/**
 * A CalDAV/CardDAV client: enough to discover a user's collections, keep a local mirror of them up
 * to date, and add new items to them.
 *
 * Reading is the whole of [discover] and [sync]. Writing is [create] and [update]: add an item, or
 * replace one under `If-Match` on its stored etag, where the 412 that says somebody else got there
 * first is surfaced as its own failure for the caller to resolve (in practice: resync, then show
 * the user the fresher card). Delete still deliberately does not exist here.
 *
 * ## The shape of a sync
 * [discover] finds the collections once (well-known → principal → home set → list). [sync] then
 * runs per collection, handing back the server's `sync-token` to store and quote next time. The
 * first call passes a null token and gets everything; later calls get only the delta.
 *
 * ## 🔴 A sync-token is per collection and comes only from that collection's own REPORT
 * Stalwart reports one server-wide counter as the `sync-token` of every collection, so the tokens
 * of two different calendars are byte-identical. Storing the token read off a collection LISTING
 * and quoting it as a starting point would tell the server the app is current for a collection it
 * has never read, and that collection stays empty forever with nothing logged. Only
 * [DavSyncResult.token], which came back from the sync REPORT of that exact collection, is safe to
 * persist. The listing's token is deliberately not exposed by this class.
 *
 * ## 🔴 No `<D:limit>` on a sync REPORT
 * RFC 6578 lets a server answer an over-large sync with `507 Insufficient Storage`. Stalwart does
 * that AND still appends a sync-token, which is a trap: storing that token records "caught up" over
 * a response that was explicitly truncated, and every item past the cut silently never arrives.
 * This client never asks for a limit, so the case cannot occur. If a server truncates anyway, the
 * 507 is detected and turned into a full resync rather than a partial one.
 */
class DavClient internal constructor(
    private val http: OkHttpClient,
) {
    /**
     * The constructor callers outside this module use.
     *
     * okhttp is an implementation detail of `:core:dav` and stays one: exposing an `OkHttpClient`
     * parameter would put okhttp on the API surface of every module that wants a calendar, and the
     * transport is not something they have an opinion about. Tests inside the module use the
     * internal constructor to point it at a MockWebServer.
     */
    constructor() : this(defaultHttpClient())

    /**
     * Find every calendar or address book [credentials] can read on [serverUrl].
     *
     * [serverUrl] is whatever the user typed at setup (`mail.example.com`, or a full URL). The
     * `/.well-known` hop is what makes that enough: the collections are rarely at the host root and
     * asking a user to know their DAV path is asking them to read an RFC.
     */
    suspend fun discover(
        serverUrl: String,
        credentials: DavCredentials,
        kind: DavKind,
    ): List<DavCollection> = withContext(Dispatchers.IO) {
        val base = baseUrl(serverUrl)
        // Each hop below can be answered by the previous one: a server is allowed to hand back the
        // home set straight from /.well-known. Each step therefore checks before asking again,
        // rather than walking the full chain on principle.
        val start = base.resolve(kind.wellKnown) ?: throw DavException("Bad server URL: $serverUrl")

        val principalProps = propfind(
            url = start,
            credentials = credentials,
            depth = 0,
            props = listOf(PropKey.CURRENT_USER_PRINCIPAL, kind.homeSet),
        )
        val principal = principalProps.responses.firstNotNullOfOrNull {
            it.prop(PropKey.CURRENT_USER_PRINCIPAL)
        }

        val homeFromPrincipal = principalProps.responses.firstNotNullOfOrNull { it.prop(kind.homeSet) }
        val home = when {
            homeFromPrincipal != null -> homeFromPrincipal
            principal != null -> propfind(
                url = resolve(start, principal),
                credentials = credentials,
                depth = 0,
                props = listOf(kind.homeSet),
            ).responses.firstNotNullOfOrNull { it.prop(kind.homeSet) }
            else -> null
        } ?: throw DavException("Server did not report a ${kind.name.lowercase()} home collection")

        collections(resolve(start, home), credentials, kind)
    }

    /** List the collections directly inside [homeUrl]. */
    private suspend fun collections(
        homeUrl: HttpUrl,
        credentials: DavCredentials,
        kind: DavKind,
    ): List<DavCollection> {
        val result = propfind(
            url = homeUrl,
            credentials = credentials,
            depth = 1,
            props = listOf(PropKey.DISPLAY_NAME, PropKey.CALENDAR_COLOR),
            includeResourceType = true,
        )
        return result.responses
            // The home collection itself comes back in a Depth:1 listing, as a plain
            // `<D:collection>` with no calendar/addressbook type. Without this filter the app
            // would sync the container as if it were a calendar and find nothing in it.
            .filter { it.isType(kind.collectionType) }
            .map { response ->
                DavCollection(
                    url = resolve(homeUrl, response.href).toString(),
                    kind = kind,
                    displayName = response.prop(PropKey.DISPLAY_NAME)?.takeIf { it.isNotBlank() },
                    color = response.prop(PropKey.CALENDAR_COLOR)?.takeIf { it.isNotBlank() },
                )
            }
    }

    /**
     * Bring one collection up to date.
     *
     * Pass the [token] stored from the previous [DavSyncResult] for this same collection, or null
     * for a first sync. A server that rejects the token (it expired, or the collection was rebuilt)
     * answers 403/409, which is not an error: it means "start over", and this retries once without
     * the token and marks the result [DavSyncResult.fullResync].
     */
    suspend fun sync(
        collectionUrl: String,
        credentials: DavCredentials,
        kind: DavKind,
        token: String?,
    ): DavSyncResult = withContext(Dispatchers.IO) {
        val url = collectionUrl.toHttpUrlOrNull()
            ?: throw DavException("Bad collection URL: $collectionUrl")
        try {
            read(url, credentials, kind, token, fullResync = token == null)
        } catch (e: DavException) {
            // 403 with <valid-sync-token>, or a 409, is the server saying the token is no longer
            // usable. RFC 6578 §3.2 makes recovering by re-syncing from scratch the expected
            // client behaviour, so it is handled here rather than surfaced as a failure the user
            // could do nothing about.
            if (token != null && (e.code == 403 || e.code == 409)) {
                read(url, credentials, kind, token = null, fullResync = true)
            } else {
                throw e
            }
        }
    }

    /**
     * Create one new item in a collection.
     *
     * [fileName] names the resource inside [collectionUrl] and by convention is `<uid>.ics` or
     * `<uid>.vcf`. It only has to be unique in the collection; the UID inside [data] is the real
     * identity, and keeping the two the same is what makes a server's own web UI show a sane
     * filename instead of a random one.
     *
     * ## 🔴 `If-None-Match: *` is what makes this a create and not a silent overwrite
     * Without it a PUT to a path that already exists replaces whatever was there, and since the
     * path is derived from a UID, a UID collision would quietly destroy an existing appointment.
     * With it the server answers **412 Precondition Failed** instead, and nothing is lost. That is
     * why the 412 below is reported as a distinct message rather than folded into "write failed":
     * the two mean completely different things to whoever has to decide what happens next.
     *
     * PUT answers with a plain body or none at all, never a MultiStatus, so this cannot go through
     * [execute] and does its own response handling.
     */
    suspend fun create(
        collectionUrl: String,
        fileName: String,
        credentials: DavCredentials,
        kind: DavKind,
        data: String,
    ): DavWriteResult = withContext(Dispatchers.IO) {
        val collection = collectionUrl.toHttpUrlOrNull()
            ?: throw DavException("Bad collection URL: $collectionUrl")
        // addPathSegment, not string concatenation: the segment is encoded exactly once, and a
        // collection URL that arrived without its trailing slash cannot swallow its last segment.
        val target = collection.newBuilder().addPathSegment(fileName).build()
        val request = Request.Builder()
            .url(target)
            .put(data.toRequestBody(kind.contentType.toMediaType()))
            .header("If-None-Match", "*")
            .header("Authorization", credentials.authorizationHeader())
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 412) {
                throw DavException("An item with that id already exists", 412)
            }
            if (!response.isSuccessful) throw DavException(errorFor(response), response.code)
            DavWriteResult(
                url = target.toString(),
                // pathSegments is the decoded form, which is the point. See DavWriteResult.href.
                href = "/" + target.pathSegments.joinToString("/"),
                // A server MAY omit the etag (RFC 4791 §5.3.4) when it changed what it stored, e.g.
                // added a VTIMEZONE. Null is honest: the next sync will fetch the item and record
                // whatever the server actually kept, which beats storing an etag that is a guess.
                etag = response.header("ETag")?.trim('"', ' ')?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Replace an existing item, guarded by the etag the last sync stored for it.
     *
     * ## 🔴 `If-Match: <etag>` is what makes this an edit and not a blind overwrite
     * The mirror of [create]'s `If-None-Match: *`: the PUT only lands if the server still holds
     * the version this device edited. If another client changed the item since the last sync, the
     * server answers **412 Precondition Failed** and nothing is lost — the caller's move is to
     * resync and let the user edit the fresher copy, not to retry. That is why the 412 gets its
     * own message here instead of folding into "write failed".
     *
     * ⚠️ When [etag] is null (a server that omitted it on create, and no sync has recorded one
     * since) the PUT goes unconditional, because `If-Match` with nothing to match is not a request
     * that can be made. The window is real but small, and refusing the edit outright over a
     * missing etag would be the worse trade.
     *
     * [href] is the item's percent-DECODED path exactly as the local mirror keys it; it is resolved
     * against [collectionUrl] and re-encoded here, for the reason written on [resolve].
     */
    suspend fun update(
        collectionUrl: String,
        href: String,
        credentials: DavCredentials,
        kind: DavKind,
        data: String,
        etag: String?,
    ): DavWriteResult = withContext(Dispatchers.IO) {
        val collection = collectionUrl.toHttpUrlOrNull()
            ?: throw DavException("Bad collection URL: $collectionUrl")
        val target = resolve(collection, href)
        val request = Request.Builder()
            .url(target)
            .put(data.toRequestBody(kind.contentType.toMediaType()))
            .apply {
                // Stored etags are kept unquoted; the wire wants the RFC 7232 quoted form back.
                if (etag != null) header("If-Match", "\"$etag\"")
            }
            .header("Authorization", credentials.authorizationHeader())
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 412) {
                throw DavException("This item changed on the server since the last sync", 412)
            }
            if (!response.isSuccessful) throw DavException(errorFor(response), response.code)
            DavWriteResult(
                url = target.toString(),
                href = "/" + target.pathSegments.joinToString("/"),
                etag = response.header("ETag")?.trim('"', ' ')?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * GET a file the server pointed at, e.g. a calendar attachment's `ATTACH` URL.
     *
     * ## 🔴 The password is attached only when the URL is the user's own server
     * An attachment URL arrives inside an invitation, and an invitation arrives from anybody. A GET
     * that blindly carried `Authorization: Basic` would let a stranger who sends one appointment
     * with `ATTACH:https://their-host/x.png` collect the user's mail password from their own logs.
     * So the header goes on only when the URL's host is [ownServer]'s; every other host is fetched
     * anonymously, and if it answers 401 the download simply fails, which is the correct outcome.
     * The check lives here rather than in the caller because a caller can forget it once.
     *
     * ⚠️ Plain `http://` is fetched, but never with the header on it, whatever the host: sending a
     * password in clear is the thing [baseUrl] refuses, and an unauthenticated GET of a file the
     * user asked for is no worse than the browser they would otherwise open it in.
     *
     * [maxBytes] is enforced twice, against the declared `Content-Length` and again while reading,
     * for the reason written on [LimitedInputStream]: a chunked response declares nothing.
     */
    suspend fun fetch(
        url: String,
        credentials: DavCredentials?,
        ownServer: String?,
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
    ): DavDownload = withContext(Dispatchers.IO) {
        val target = url.trim().toHttpUrlOrNull() ?: throw DavException("Bad attachment URL: $url")
        val trusted = trusts(target, ownServer)
        val request = Request.Builder()
            .url(target)
            .get()
            .apply {
                if (trusted && credentials != null) {
                    header("Authorization", credentials.authorizationHeader())
                }
            }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DavException(errorFor(response), response.code)
            val body = response.body ?: throw DavException("Empty attachment body", response.code)
            if (body.contentLength() > maxBytes) {
                throw DavException("Attachment is too large (${body.contentLength()} bytes)")
            }
            DavDownload(
                bytes = LimitedInputStream(body.byteStream(), maxBytes).readBytes(),
                // The response outranks anything the calendar entry claimed: the entry's FMTTYPE is
                // written by whoever sent the invitation, and it is what a viewer gets launched on.
                contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
                    ?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Whether [target] may be sent this account's password. See the 🔴 on [fetch].
     *
     * Its own function because it is the security decision in this class that is worth testing on
     * its own, without a TLS-terminating test server in the way. [ownServer] goes through [baseUrl]
     * because the stored value may be a bare domain or a full URL, and the comparison has to be
     * host against whole host: `gridlink.me` matches, `evil-gridlink.me` must not.
     */
    internal fun trusts(target: HttpUrl, ownServer: String?): Boolean {
        if (!target.isHttps) return false
        val ownHost = ownServer?.takeIf { it.isNotBlank() }
            ?.let { runCatching { baseUrl(it).host }.getOrNull() } ?: return false
        return target.host.equals(ownHost, ignoreCase = true)
    }

    private suspend fun read(
        url: HttpUrl,
        credentials: DavCredentials,
        kind: DavKind,
        token: String?,
        fullResync: Boolean,
    ): DavSyncResult {
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<D:sync-collection xmlns:D="DAV:" xmlns:${kind.prefix}="${kind.namespace}">""")
            append("<D:sync-token>").append(token.orEmpty().xmlEscaped()).append("</D:sync-token>")
            append("<D:sync-level>1</D:sync-level>")
            append("<D:prop><D:getetag/><${kind.prefix}:${kind.dataElement}/></D:prop>")
            append("</D:sync-collection>")
        }
        val result = report(url, credentials, body)

        val changed = ArrayList<DavItem>()
        val removed = ArrayList<String>()
        var truncated = false
        for (response in result.responses) {
            when {
                // The 507 lands on the COLLECTION's own href, not on an item, and it means the
                // listing above it was cut short. Treated as "this delta is incomplete", never as
                // a deleted resource, which is what its 4xx-shaped sibling statuses mean.
                response.status?.contains(" 507") == true -> truncated = true
                response.isRemoved -> removed += response.href
                else -> changed += DavItem(
                    href = response.href,
                    etag = response.prop(PropKey.GET_ETAG)?.trim('"', ' '),
                    data = response.prop(kind.dataProp),
                )
            }
        }

        // A truncated response's token describes more than arrived. Returning null discards it, so
        // the next sync starts over rather than resuming from a point that was never reached.
        return DavSyncResult(
            changed = changed,
            removed = removed,
            token = if (truncated) null else result.syncToken,
            fullResync = fullResync || truncated,
        )
    }

    // ---- Requests --------------------------------------------------------------------------

    private fun propfind(
        url: HttpUrl,
        credentials: DavCredentials,
        depth: Int,
        props: List<PropKey>,
        includeResourceType: Boolean = false,
    ): MultiStatusResult {
        val namespaces = props.map { it.namespace }.toMutableSet()
        namespaces += "DAV:"
        val prefixes = namespaces.filter { it != "DAV:" }.withIndex().associate { (i, ns) ->
            ns to "N$i"
        }
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<D:propfind xmlns:D="DAV:"""")
            prefixes.forEach { (ns, prefix) -> append(""" xmlns:$prefix="$ns"""") }
            append("><D:prop>")
            if (includeResourceType) append("<D:resourcetype/>")
            props.forEach { key ->
                val prefix = if (key.namespace == "DAV:") "D" else prefixes.getValue(key.namespace)
                append("<$prefix:${key.local}/>")
            }
            append("</D:prop></D:propfind>")
        }
        return execute(
            Request.Builder()
                .url(url)
                .method("PROPFIND", body.toRequestBody(XML))
                .header("Depth", depth.toString())
                .header("Authorization", credentials.authorizationHeader())
                .build(),
        )
    }

    private fun report(
        url: HttpUrl,
        credentials: DavCredentials,
        body: String,
    ): MultiStatusResult = execute(
        Request.Builder()
            .url(url)
            .method("REPORT", body.toRequestBody(XML))
            .header("Depth", "1")
            .header("Authorization", credentials.authorizationHeader())
            .build(),
    )

    private fun execute(request: Request): MultiStatusResult =
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DavException(errorFor(response), response.code)
            val stream = response.body?.byteStream()
                ?: throw DavException("Empty response from ${request.url}", response.code)
            // Guarded rather than trusted: Content-Length is advisory and a chunked response has
            // none at all, so the cap is also enforced while reading (see LimitedInputStream).
            val declared = response.body?.contentLength() ?: -1
            if (declared > MultiStatus.MAX_BODY_BYTES) {
                throw DavException("Response too large ($declared bytes)", response.code)
            }
            MultiStatus.parse(LimitedInputStream(stream, MultiStatus.MAX_BODY_BYTES))
        }

    /**
     * The message a failure carries. The status line is included because the three codes that
     * matter here mean genuinely different things (401 wrong credentials, 403/409 stale token,
     * 404 collection gone) and a caller that cannot tell them apart cannot recover from any.
     */
    private fun errorFor(response: Response): String =
        "DAV ${response.code} ${response.message} for ${response.request.url}"

    // ---- URLs ------------------------------------------------------------------------------

    /**
     * Turn whatever a user typed into a base URL. A bare host means HTTPS: this carries a password
     * on every request, and silently accepting `http://` because someone omitted a scheme would
     * put that password on the wire in clear.
     */
    private fun baseUrl(serverUrl: String): HttpUrl {
        val trimmed = serverUrl.trim().trimEnd('/')
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull() ?: throw DavException("Bad server URL: $serverUrl")
    }

    /**
     * Resolve a server-supplied href against [base].
     *
     * 🔴 The href arrives percent-DECODED from [MultiStatus], so it is re-encoded here rather than
     * pasted in. Handing okhttp a raw `tate@gridlink.me` path segment produces a URL the server
     * answers 404 to, and the failure looks like a missing collection rather than a mangled path.
     */
    internal fun resolve(base: HttpUrl, href: String): HttpUrl {
        if ("://" in href) {
            return href.toHttpUrlOrNull() ?: throw DavException("Bad href: $href")
        }
        val builder = base.newBuilder()
        if (href.startsWith("/")) {
            builder.encodedPath("/")
        }
        href.trim('/').split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
        // Collections are identified by a trailing slash and servers are entitled to care.
        if (href.endsWith("/")) builder.addPathSegment("")
        return builder.build()
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()

        /**
         * The most a single attachment may be. Held to the same 24 MB as a sync body: the bytes are
         * read whole into memory before being handed to a viewer, and a phone that OOMs on somebody
         * else's badly attached video has lost the user their draft as well as the download.
         */
        const val MAX_DOWNLOAD_BYTES = 24L * 1024 * 1024

        /**
         * The HTTP client this was written against.
         *
         * Redirects must be followed: the whole point of `/.well-known/caldav` is that it 301s to
         * wherever the collections really live. `followSslRedirects(false)` is what keeps that from
         * becoming a credential leak, since okhttp re-attaches the Authorization header on a
         * same-host redirect and an HTTPS→HTTP hop would put the password on the wire in clear.
         *
         * The read timeout is generous because a first sync of a large calendar is one response.
         */
        internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()

        private fun String.xmlEscaped(): String = this
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
