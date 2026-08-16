package app.gridlink.core.data.calendar

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
 */
data class CalendarAttachment(
    val href: String,
    val title: String? = null,
    val contentType: String? = null,
    val size: Long? = null,
    val blobId: String? = null,
) {
    /**
     * The name to put on a chip: what the server called it, else the tail of the URL, else a
     * generic. Never blank, because a nameless chip is untappable in practice.
     */
    val displayName: String
        get() = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: href.substringBefore('?').substringBefore('#')
                .substringAfterLast('/').trim().takeIf { it.isNotEmpty() }
            ?: "Attachment"
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
