package app.gridlink.core.data.mail

/**
 * The tags a mail SERVER says exist, as opposed to the ones this device happens to have cached.
 *
 * ## 🔴 Why the app has to ask at all
 * A tag is two halves that live in different places. The keyword travels with the message and
 * belongs to the server; the label and the colour are a local definition and belong to this
 * device. Mail tagged by another client, or by a server-side rule, or by this app before a factory
 * reset, arrives carrying keywords nothing here can name — and the tag manager could only ever
 * find those by scanning the messages it had already downloaded, which is a window, not a mailbox.
 * A keyword used only on mail older than that window did not exist as far as Settings was
 * concerned.
 *
 * ## The two protocols answer very differently
 * IMAP answers properly: `SELECT` returns `PERMANENTFLAGS`, the server naming its own vocabulary
 * for that folder, and the whole account costs one SELECT per folder and not one message fetched.
 *
 * JMAP has no such method. RFC 8621 stores keywords as keys on each Email and provides no way to
 * enumerate them, so the only answer available is assembled by reading mail, bounded, and possibly
 * short of the end.
 *
 * ## ⚠️ [complete] is load-bearing
 * False means the answer may be MISSING keywords, for one of two reasons depending on the
 * protocol: a JMAP sweep that stopped at its ceiling, or an IMAP folder that would not open or
 * would not state its flags. Both leave the same hazard, which is why they share one flag: shown
 * as a finished list, an incomplete answer reads as "these are all your tags", and a reader who
 * trusts it will conclude a tag they use every day has vanished.
 *
 * Empty and complete is a real answer, and a different one: the server was asked and has nothing.
 */
data class ServerKeywords(
    /** Distinct non-system keywords the server named, sorted. */
    val keywords: List<String>,
    /** True only when the whole mailbox was covered and every part of it answered. */
    val complete: Boolean,
) {
    companion object {
        /** What a protocol with nothing to offer returns: no keywords, and honest about it. */
        val UNANSWERED = ServerKeywords(emptyList(), complete = false)
    }
}
