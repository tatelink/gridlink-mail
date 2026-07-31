package app.sterna.core.imap

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The advanced-search criteria an IMAP `SEARCH` can express server-side (RFC 3501 §6.4.4).
 * The mirror of the protocol-neutral `SearchQuery`, minus what IMAP has no key for —
 * "has an attachment" is filtered locally from BODYSTRUCTURE by [searchFolders].
 *
 * [recipient] matches `To` OR `Cc`: a message addressed to an alias in copy was received at
 * that address just as much as one in To.
 *
 * [flagged] IS here, unlike "has an attachment": `FLAGGED` is a standard search key, so the
 * server answers it and no message has to be fetched to find out. Passing it through the local
 * filter instead would have cost an envelope FETCH per candidate, a scan cap and a truncated
 * count, for a question the server answers in one word.
 *
 * [afterMillis]/[beforeMillis] are epoch-millis bounds; IMAP compares them against the
 * server's INTERNALDATE with DAY granularity, so they are rendered as calendar days (UTC).
 */
data class ImapSearchCriteria(
    val text: String = "",
    val from: String = "",
    val recipient: String = "",
    val subject: String = "",
    val flagged: Boolean = false,
    val afterMillis: Long? = null,
    val beforeMillis: Long? = null,
)

/**
 * A built `SEARCH` argument list: the search keys, plus whether any value carries a
 * non-ASCII character and therefore needs a `CHARSET UTF-8` declaration.
 */
data class ImapSearchCommand(val keys: String, val needsUtf8: Boolean) {
    /** The arguments as they go on the wire, after `UID SEARCH `. */
    fun arguments(declareUtf8: Boolean = needsUtf8): String =
        if (declareUtf8) "CHARSET UTF-8 $keys" else keys
}

/** IMAP date-text: `1-Jun-2026`. English month names, never the device locale's. */
private val IMAP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH)

/**
 * Build the `UID SEARCH` key list for [criteria] — pure, so the exact bytes we put on the wire
 * are unit-testable (there is no IMAP server in the test bench).
 *
 * Notes on the shape of the command:
 * - Keys separated by a space are ANDed by the server; there is no explicit AND key.
 * - `OR` is PREFIX notation and takes exactly two keys, hence `OR TO "x" CC "x"`, which then
 *   sits in the AND chain like any other key.
 * - A blank free-text term emits NO `TEXT` key at all. `TEXT ""` matches every message, which
 *   is how "advanced filters only" used to return the whole inbox as if it were a result set.
 * - `SINCE`/`BEFORE` compare INTERNALDATE by calendar day. `BEFORE d` is exclusive, so the
 *   upper bound is rendered as the day AFTER the requested one to keep the picked day itself
 *   in range (the UI passes end-of-day millis). Day granularity makes the bounds slightly
 *   generous rather than slightly lossy — the wrong direction would silently hide messages.
 * - Every value goes through [quote], which rejects CR/LF (no command injection through a
 *   search field).
 * - With no key at all (an attachment-only search) the list is `ALL`: an empty key list is a
 *   syntax error, and the local attachment filter narrows it down afterwards.
 * - `FLAGGED` takes no argument and is the cheapest key there is (a flag test, no header or body
 *   to open), so it goes FIRST: servers evaluate left to right, and it is the key most likely to
 *   throw away the bulk of the folder before anything expensive is read.
 */
fun buildImapSearch(criteria: ImapSearchCriteria): ImapSearchCommand {
    val keys = mutableListOf<String>()
    val from = criteria.from.trim()
    val recipient = criteria.recipient.trim()
    val subject = criteria.subject.trim()
    val text = criteria.text.trim()

    if (criteria.flagged) keys += "FLAGGED"
    if (from.isNotEmpty()) keys += "FROM ${quote(from)}"
    if (recipient.isNotEmpty()) keys += "OR TO ${quote(recipient)} CC ${quote(recipient)}"
    if (subject.isNotEmpty()) keys += "SUBJECT ${quote(subject)}"
    criteria.afterMillis?.let { keys += "SINCE ${imapDay(it)}" }
    criteria.beforeMillis?.let { keys += "BEFORE ${imapDay(it, plusDays = 1)}" }
    // Full-text last: it is the most expensive key, and servers evaluate left to right.
    if (text.isNotEmpty()) keys += "TEXT ${quote(text)}"

    val needsUtf8 = listOf(text, from, recipient, subject).any { value -> value.any { it.code > 127 } }
    return ImapSearchCommand(keys = keys.joinToString(" ").ifEmpty { "ALL" }, needsUtf8 = needsUtf8)
}

private fun imapDay(millis: Long, plusDays: Long = 0): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().plusDays(plusDays).format(IMAP_DATE)

/**
 * One folder's matches. [mailbox] is the folder the UIDs belong to — UIDs mean nothing without it.
 *
 * [scanTruncated] says the walk gave up before examining every candidate (the attachment filter's
 * scan cap) WITHOUT filling the caller's cap: the answer may be short, and anything counting these
 * messages must say so rather than present a partial scan as a total.
 */
data class ImapFolderHits(
    val mailbox: String,
    val messages: List<ImapMessage>,
    val scanTruncated: Boolean = false,
)

/** How many UIDs are fetched per `UID FETCH` while walking candidates for the attachment filter. */
private const val SEARCH_FETCH_BATCH = 50

/**
 * Ceiling on how many candidate messages one folder is fetched-and-filtered for when the
 * attachment filter is on. Without it a broad query on a huge folder would pull every matching
 * envelope over a mobile link looking for the few with a file attached.
 */
private const val ATTACHMENT_SCAN_CAP = 1_000

/**
 * Search [mailboxes] one after another on THIS session and return each folder's matches,
 * newest UID first, at most [limit] per folder.
 *
 * IMAP searches the SELECTed mailbox and only that one, so covering an account means
 * SELECT + UID SEARCH per folder — done on the one pooled connection, not a new one each time.
 * A folder that fails (gone, no permission, server hiccup) is reported to [onFolderError] and
 * skipped: one bad folder must not sink the whole search, nor vanish silently.
 *
 * When [requireAttachment] is set, IMAP has no key for it (`SEARCH` cannot express it), so the
 * server answers the other criteria and the attachment test is applied here from BODYSTRUCTURE,
 * which the envelope FETCH already carries. Candidates are then walked in batches until [limit]
 * messages have been KEPT or the candidates run out — the cap counts results, not rows examined,
 * so a filtered search cannot report three hits merely because it stopped looking after three.
 * A walk that still gives up on the scan cap marks its folder [ImapFolderHits.scanTruncated], so
 * the count shown to the user can say "at least" instead of passing a partial scan off as a total.
 *
 * [stillWanted] is checked between folders: the walk is blocking and holds the account's one
 * connection, so a caller that has moved on (the next keystroke in the inbox search) can stop it
 * instead of leaving every other IMAP operation queued behind a walk nobody is waiting for.
 */
fun ImapSession.searchFolders(
    mailboxes: List<String>,
    command: ImapSearchCommand,
    requireAttachment: Boolean,
    limit: Int,
    stillWanted: () -> Boolean = { true },
    onFolderError: (mailbox: String, error: Throwable) -> Unit = { _, _ -> },
): List<ImapFolderHits> {
    if (limit <= 0) return emptyList()
    val hits = mutableListOf<ImapFolderHits>()
    for (mailbox in mailboxes) {
        if (!stillWanted()) break
        val scan = try {
            select(mailbox)
            searchOneFolder(command, requireAttachment, limit)
        } catch (t: Throwable) {
            onFolderError(mailbox, t)
            continue
        }
        if (scan.messages.isNotEmpty() || scan.truncated) {
            hits += ImapFolderHits(mailbox, scan.messages, scan.truncated)
        }
    }
    return hits
}

/** One folder's scan: what it kept, and whether it stopped looking with candidates left over. */
private class FolderScan(val messages: List<ImapMessage>, val truncated: Boolean)

/** Search the ALREADY SELECTed folder and fetch up to [limit] matching envelopes, newest first. */
private fun ImapSession.searchOneFolder(
    command: ImapSearchCommand,
    requireAttachment: Boolean,
    limit: Int,
): FolderScan {
    val uids = searchUids(command).sortedDescending()
    if (uids.isEmpty()) return FolderScan(emptyList(), truncated = false)
    // No local filter: the newest [limit] UIDs are exactly the answer, in one FETCH.
    if (!requireAttachment) {
        return FolderScan(fetchUids(uids.take(limit)).sortedByDescending { it.uid }, truncated = false)
    }

    val kept = mutableListOf<ImapMessage>()
    var examined = 0
    for (chunk in uids.chunked(SEARCH_FETCH_BATCH)) {
        if (kept.size >= limit || examined >= ATTACHMENT_SCAN_CAP) break
        examined += chunk.size
        kept += fetchUids(chunk).sortedByDescending { it.uid }.filter { it.hasAttachment }
    }
    // Stopping with candidates left AND the cap unfilled means the scan cap cut the walk short:
    // the folder may hold matches nobody looked at, so the answer is not a total.
    return FolderScan(kept.take(limit), truncated = examined < uids.size && kept.size < limit)
}
