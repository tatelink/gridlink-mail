package app.gridlink.core.data.calendar

import java.io.ByteArrayOutputStream

/**
 * A file hung off an appointment, in the one shape both protocols reduce to.
 *
 * JSCalendar calls it a link with `rel: enclosure` (RFC 8984 §4.2.7), iCalendar calls it `ATTACH`
 * (RFC 5545 §3.8.1.1), and both of them end up saying the same four things: where it is, what to
 * call it, what it is, and how big. Reducing to that here rather than at the screen is what keeps
 * the calendar UI from learning which protocol delivered the event it is drawing.
 *
 * 🔴 This is a POINTER, never the bytes. An iCalendar `ATTACH;VALUE=BINARY` is deliberately not
 * modelled: the payload it inlines lives in the cached row, would be re-parsed on every month view,
 * and counts against [ICalendarStream.MAX_SOURCE_CHARS] for every event in the collection. A file
 * worth showing is a file worth fetching when it is actually tapped.
 *
 * @param href where the file is. Empty is legal ONLY when [blobId] is set, which is the JMAP case:
 *   the server offers a blob and the download URL is built from the session, not from the event.
 * @param title what to call it. Null means the server did not say and the reader should fall back to
 *   the last path segment; that fallback is [displayName]'s job, not a value invented here.
 * @param size bytes, when the server said. Null is unknown, which is NOT zero: a chip reading "0 KB"
 *   claims a fact about a file nobody has looked at.
 * @param managedId the server's handle for this file under RFC 8607, when there is one. Only a
 *   managed attachment can be removed by asking the server to remove it; anything else is a URL
 *   somebody typed into an event and the most we can do is stop pointing at it.
 */
data class CalendarAttachment(
    val href: String,
    val title: String? = null,
    val contentType: String? = null,
    val size: Long? = null,
    val blobId: String? = null,
    val managedId: String? = null,
) {
    /**
     * The name to put on a chip: what the server called it, else the tail of the URL, else a
     * generic. Never blank, because a nameless chip is untappable in practice.
     *
     * The URL tail is percent-decoded, because it is a path segment and a file called
     * `holiday pay.pdf` reaches us spelled `holiday%20pay.pdf`.
     */
    val displayName: String
        get() = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: urlTail?.let(::percentDecode)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Attachment"

    private val urlTail: String?
        get() = href.substringBefore('?').substringBefore('#')
            .substringAfterLast('/').takeIf { it.isNotEmpty() }

    companion object {
        /**
         * The managed id hiding in a managed attachment's own URL, or null.
         *
         * 🔴 Why this exists: Stalwart's JMAP view of an event throws `MANAGED-ID` away. It reduces
         * the whole `ATTACH` property to a JSCalendar link carrying href, content type and size, so
         * an account syncing over JMAP can SEE an attachment it cannot ask the server to remove.
         * Our own server therefore stores each blob one-to-a-folder, at
         * `.attachments/<managed-id>/<filename>`, which puts the id somewhere a lossy conversion
         * cannot reach: the URL itself.
         *
         * ⚠️ This is OUR convention, not RFC 8607, which says nothing about the shape of the URL.
         * So it is deliberately narrow: the path must sit under a `.attachments` segment and the
         * segment after it must look like a managed id. Anything else returns null and the
         * attachment is treated as un-removable, which is the safe way to be wrong.
         */
        fun managedIdFromUrl(href: String): String? {
            val segments = href.substringBefore('?').substringBefore('#').split('/')
            val marker = segments.indexOf(ATTACHMENT_DIR)
            if (marker < 0) return null
            return segments.getOrNull(marker + 1)?.takeIf { it.matches(MANAGED_ID) }
        }

        private const val ATTACHMENT_DIR = ".attachments"
        private val MANAGED_ID = Regex("[0-9a-f]{32}")

        /**
         * Percent-decoding for one path segment.
         *
         * ⚠️ Hand-rolled rather than `URLDecoder`, which decodes `+` to a space. That is correct
         * for a query string and wrong for a path, and our own filename allowlist keeps `+`, so
         * `Q3+Q4 notes.pdf` would come back with the plus eaten.
         */
        private fun percentDecode(value: String): String {
            if (!value.contains('%')) return value
            val out = ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val hex = if (c == '%') value.substring(i + 1, (i + HEX_END).coerceAtMost(value.length)) else null
                val byte = hex?.takeIf { it.length == 2 }?.toIntOrNull(HEX_RADIX)
                if (byte == null) {
                    // Written out as UTF-8 rather than as one byte: a segment SHOULD be ASCII, but
                    // a server that put a literal accent in the path must not lose it here.
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                } else {
                    out.write(byte)
                    i += HEX_END
                }
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        }

        private const val HEX_END = 3
        private const val HEX_RADIX = 16
    }
}

/**
 * Where a tapped attachment's bytes actually come from.
 *
 * A [CalendarAttachment] can be reached two ways and only ever one of them: an ordinary URL the
 * server pointed at, or a JMAP blob id that only means anything inside the account's own session.
 * This is that decision made once, so the downloader is handed an answer rather than two nullable
 * fields and a rule about which wins.
 */
sealed interface CalendarAttachmentSource {
    /** Fetch this URL. Whether the account's password rides along is [href]'s host's business. */
    data class Url(val href: String) : CalendarAttachmentSource

    /** Download this blob from the account's JMAP session. */
    data class Blob(val id: String) : CalendarAttachmentSource
}
