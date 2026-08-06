package app.gridlink.core.dav

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * One `<D:response>` out of a WebDAV `207 Multi-Status` body, reduced to what a sync needs.
 *
 * A response describes one URL. It carries the properties that were readable, the HTTP status the
 * server attached to it, and (for a `sync-collection` REPORT) whether the resource was reported as
 * gone rather than changed.
 */
data class DavResponse(
    /** The resource path, percent-DECODED. See [MultiStatus] on why decoding happens here. */
    val href: String,
    /** Properties that came back inside a `200 OK` propstat, keyed by [PropKey]. */
    val props: Map<PropKey, String>,
    /**
     * Every `<D:resourcetype>` child, as `namespace|localName` (e.g. `DAV:|collection`).
     * Kept as a set rather than a flag because a collection is routinely several types at once.
     */
    val resourceTypes: Set<String>,
    /**
     * The status on the response itself (NOT on a propstat), when the server put one there.
     * `sync-collection` uses this to report deletions as `HTTP/1.1 404 Not Found`.
     */
    val status: String?,
) {
    /** True when this response says the resource is gone, which is how a sync reports a delete. */
    val isRemoved: Boolean get() = status?.contains(" 404") == true || status?.contains(" 410") == true

    fun prop(key: PropKey): String? = props[key]

    /** True when the resource claims [type], given as `namespace|localName`. */
    fun isType(type: String): Boolean = type in resourceTypes
}

/**
 * The properties this client asks for, and the only ones it keeps.
 *
 * A closed set rather than a free-form map of QNames: everything downstream switches on these, and
 * an unrecognised property is not data we can do anything with. Adding a property here is the one
 * place to change when the sync needs to learn a new field.
 */
enum class PropKey(val namespace: String, val local: String) {
    DISPLAY_NAME("DAV:", "displayname"),
    GET_ETAG("DAV:", "getetag"),
    SYNC_TOKEN("DAV:", "sync-token"),
    CURRENT_USER_PRINCIPAL("DAV:", "current-user-principal"),
    CALENDAR_HOME_SET("urn:ietf:params:xml:ns:caldav", "calendar-home-set"),
    ADDRESSBOOK_HOME_SET("urn:ietf:params:xml:ns:carddav", "addressbook-home-set"),
    CALENDAR_DATA("urn:ietf:params:xml:ns:caldav", "calendar-data"),
    ADDRESS_DATA("urn:ietf:params:xml:ns:carddav", "address-data"),
    CALENDAR_COLOR("http://apple.com/ns/ical/", "calendar-color"),
    ;

    companion object {
        private val byQName = entries.associateBy { "${it.namespace}|${it.local}" }
        fun of(namespace: String, local: String): PropKey? = byQName["$namespace|$local"]
    }
}

/** The whole 207 body: its responses, plus the trailing `<D:sync-token>` a sync REPORT appends. */
data class MultiStatusResult(
    val responses: List<DavResponse>,
    /**
     * The token to hand back on the next `sync-collection`, or null if the server did not send one.
     *
     * 🔴 Only ever trust the token returned by a collection's OWN sync REPORT. Stalwart reports a
     * single server-wide counter as the `sync-token` of every collection, so the value read off a
     * PROPFIND of calendar A is byte-identical to calendar B's. Seeding B's sync with it would tell
     * the server "I am already up to date" for a collection nothing has ever fetched, and B would
     * stay permanently empty with no error anywhere.
     */
    val syncToken: String?,
)

/**
 * A small SAX reader for `207 Multi-Status` bodies (RFC 4918 §13), shared by CalDAV and CardDAV.
 *
 * ## Why SAX and not DOM
 * A full address book comes back as one document, and this runs on a phone. SAX streams it; DOM
 * would materialise the whole tree, plus a node object per element, to then throw all but a handful
 * of values away. `javax.xml.parsers` is the one XML API present on both Android and the JVM, which
 * is what lets this module stay a plain Kotlin module with real unit tests.
 *
 * ## 🔴 External entities are switched off
 * This parses XML that arrives over the network from a server the app was pointed at by a user, so
 * it is untrusted input by construction. Left at their defaults, `SAXParserFactory` implementations
 * will resolve DOCTYPE declarations and external entities, which turns a malicious 207 body into a
 * file read or an outbound request from inside the app (XXE). The parser is locked down before it
 * is ever handed a stream, and `doctype-decl` is rejected outright rather than merely unexpanded,
 * so a billion-laughs body fails fast instead of being expanded.
 *
 * ## 🔴 Hrefs come out percent-decoded
 * Stalwart returns `/dav/card/tate%40gridlink.me/default/xyz.vcf`. That path is the resource's
 * identity, and it gets stored, compared against later syncs, and re-sent in requests. Decoding it
 * once here means one representation exists in the app; leaving it encoded means the same contact
 * has two spellings the moment anything else in the stack normalises it, and a re-sync then deletes
 * and recreates every row. Requests re-encode on the way out (see [DavClient.resolve]).
 */
object MultiStatus {

    /**
     * Largest 207 body this reader will accept, ~24 MiB. Tate's address book is 455 cards, about
     * 200 KB; a real one is orders of magnitude under this. The cap exists so a server that streams
     * without end cannot walk the app off a heap cliff on a phone.
     */
    const val MAX_BODY_BYTES = 24L * 1024 * 1024

    fun parse(stream: InputStream): MultiStatusResult {
        val handler = Handler()
        newParser().parse(InputSource(stream), handler)
        return MultiStatusResult(handler.responses, handler.trailingSyncToken)
    }

    private fun newParser() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        // Fail on a DOCTYPE rather than trying to process it safely: no legitimate DAV server
        // sends one, and refusing is the only setting with no expansion path behind it at all.
        trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        trySetFeature("http://xml.org/sax/features/external-general-entities", false)
        trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
        trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newSAXParser().apply {
        // The parser is the second half of the same lock: a factory feature that this
        // implementation ignored would otherwise leave the resolver live.
        xmlReader.entityResolver = EntityResolver { _, _ -> InputSource("".reader()) }
    }

    /** Not every implementation knows every feature, and an unknown one throws. Absent is fine. */
    private fun SAXParserFactory.trySetFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private const val DAV = "DAV:"

    private class Handler : DefaultHandler() {
        val responses = ArrayList<DavResponse>()
        var trailingSyncToken: String? = null

        // Text is accumulated per element rather than assumed to arrive in one callback: SAX is
        // free to split character data at any boundary, and it always does on a CDATA section,
        // which is exactly how both servers deliver calendar-data and address-data.
        private val text = StringBuilder()
        private var capture = false

        private var inResponse = false
        private var href: String? = null
        private var status: String? = null
        private var props = HashMap<PropKey, String>()
        private var resourceTypes = HashSet<String>()

        // Which propstat we are inside. Properties are only kept once its status says 200: a
        // server answers an unsupported property with a second propstat carrying 404, and taking
        // those would record "displayname = empty string" for a collection that simply has none.
        private var inPropstat = false
        private var propstatStatus: String? = null
        private var pendingProps = HashMap<PropKey, String>()
        private var pendingTypes = HashSet<String>()

        private var inResourceType = false
        private var currentProp: PropKey? = null
        private var propDepth = 0

        override fun startElement(uri: String, local: String, qName: String, attrs: Attributes) {
            when {
                uri == DAV && local == "response" -> {
                    inResponse = true
                    href = null; status = null
                    props = HashMap(); resourceTypes = HashSet()
                }
                uri == DAV && local == "propstat" && inResponse -> {
                    inPropstat = true
                    propstatStatus = null
                    pendingProps = HashMap(); pendingTypes = HashSet()
                }
                uri == DAV && local == "resourcetype" -> {
                    inResourceType = true
                }
                inResourceType -> pendingTypes.add("$uri|$local")
                uri == DAV && local == "href" && currentProp != null -> {
                    // An href nested inside a property IS that property's value: this is how
                    // current-user-principal and the two home-sets carry their URL.
                    beginText()
                }
                currentProp != null -> propDepth++
                uri == DAV && (local == "href" || local == "status") -> beginText()
                uri == DAV && local == "sync-token" -> {
                    // Both a property inside a propstat and a sibling of the responses. Same
                    // element, two meanings, decided by where it is.
                    if (inPropstat) currentProp = PropKey.SYNC_TOKEN
                    beginText()
                }
                else -> {
                    val key = PropKey.of(uri, local)
                    if (key != null && inPropstat) {
                        currentProp = key
                        propDepth = 0
                        beginText()
                    }
                }
            }
        }

        override fun endElement(uri: String, local: String, qName: String) {
            when {
                uri == DAV && local == "response" -> {
                    val h = href
                    if (h != null) {
                        responses += DavResponse(
                            href = decodeHref(h),
                            props = props,
                            resourceTypes = resourceTypes,
                            status = status,
                        )
                    }
                    inResponse = false
                    currentProp = null
                }
                uri == DAV && local == "propstat" -> {
                    // 🔴 Absent status counts as usable. A propstat is REQUIRED to carry one, but a
                    // server that omits it has still handed us the value, and dropping it silently
                    // would empty the whole sync on a technicality nobody could see.
                    if (propstatStatus == null || propstatStatus?.contains(" 200") == true) {
                        props.putAll(pendingProps)
                        resourceTypes.addAll(pendingTypes)
                    }
                    inPropstat = false
                    currentProp = null
                }
                uri == DAV && local == "resourcetype" -> inResourceType = false
                uri == DAV && local == "href" && currentProp != null ->
                    endText { pendingProps[currentProp!!] = decodeHref(it.trim()) }
                uri == DAV && local == "href" && inResponse && href == null ->
                    endText { href = it.trim() }
                uri == DAV && local == "status" ->
                    endText { if (inPropstat) propstatStatus = it.trim() else status = it.trim() }
                uri == DAV && local == "sync-token" && !inPropstat ->
                    endText { trailingSyncToken = it.trim().takeIf(String::isNotEmpty) }
                currentProp != null && PropKey.of(uri, local) == currentProp && propDepth == 0 -> {
                    val key = currentProp!!
                    // Value already set by a nested <D:href> (the home-set / principal shape).
                    // Its text content is whitespace, and overwriting the URL with it would lose
                    // the only thing the property was asked for.
                    endText { captured -> pendingProps.getOrPut(key) { captured.trim() } }
                    currentProp = null
                }
                currentProp != null -> if (propDepth > 0) propDepth--
                else -> Unit
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capture) text.append(ch, start, length)
        }

        private fun beginText() {
            text.setLength(0)
            capture = true
        }

        private fun endText(use: (String) -> Unit) {
            if (capture) {
                capture = false
                use(text.toString())
                text.setLength(0)
            }
        }
    }

    /**
     * Percent-decode a DAV href. Deliberately hand-rolled rather than `URLDecoder.decode`, which
     * also turns `+` into a space: that is form encoding, not path encoding, and a resource whose
     * name legitimately contains a `+` would be renamed on its way in and never match the server
     * again. Malformed escapes are left as written rather than throwing, because an href we cannot
     * fully decode is still more useful than no href at all.
     */
    internal fun decodeHref(raw: String): String {
        if ('%' !in raw) return raw
        val out = StringBuilder(raw.length)
        val bytes = ArrayList<Byte>()
        fun flush() {
            if (bytes.isNotEmpty()) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.clear()
            }
        }
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val hex = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.add(hex.toByte())
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
    }
}
