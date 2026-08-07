package app.sterna.core.imap

/**
 * How many sequence positions one request of a folder walk asks for.
 *
 * IMAP has nothing to negotiate here — no `maxObjectsInGet`, no server-declared limit — so this is
 * ours alone, and it is a MEMORY number, not a network one: everything one `FETCH` brings back is
 * parsed and held whole before a single message is read out of it (`ImapSession.command` keeps
 * every token of every untagged line). A few hundred envelopes is a page the weakest device in the
 * fleet (a Moto G on Android 9, no `largeHeap`) can hold; a whole folder is not, and that is the
 * entire reason the walk exists.
 */
const val IMAP_FOLDER_PAGE = 200

/**
 * What one paginated walk of a folder brings back — UIDS and a verdict, never messages.
 *
 * ⛔ There is no `messages` here, and that is the point of the shape: the walk hands each page to
 * its caller as it lands and forgets it. Holding the window's envelopes instead is what made
 * "Messages to sync = All" a memory problem rather than a network one.
 *
 * [uids] is in walk order, newest first, DE-DUPLICATED BY UID — never by sequence number. Pages can
 * overlap (an expunge shifts everything above it DOWN by one, so the next page down re-reads a
 * message the previous one already had), and a sequence number is not a name: it means a different
 * message the moment the folder changes. A UID means one message for as long as UIDVALIDITY holds
 * (RFC 3501 §2.3.1.1).
 *
 * ⛔ [moved] is the whole IMAP-specific half of this walk, and the caller MUST honour it: true means
 * the folder changed under the walk, so the set of UIDs above is a set the walk cannot vouch for.
 * Reconciling the cache against it would DELETE messages the server still holds. See [folderMoved]
 * for what "changed" is read from, and for WHICH messages are at risk — it is not the ones between
 * two pages.
 */
data class ImapFolderWalk(
    val uids: List<Long>,
    val moved: Boolean,
    /**
     * ⛔ Whether the `SELECT` this walk started from STATED that the folder holds nothing — an
     * `* 0 EXISTS` the client actually parsed ([ImapMailboxStatus.existsObserved] and a count of
     * zero), and never the absence of an answer.
     *
     * It exists because [uids] being empty says two opposite things at once. A folder that really
     * holds no message walks without a single `FETCH` and answers with no UID — and its cache SHOULD
     * be cleared, or an emptied folder shows its old contents for ever. A folder whose `SELECT`
     * said nothing about its size, or whose every `FETCH` came back unreadable (no `UID` in the
     * response — `ImapSession.messages` drops those silently), answers with no UID either, and
     * clearing its cache destroys mail the server still holds.
     *
     * ⭐ False by default, the refusing direction: a fixture, or a future walk, that does not state
     * this cannot license a delete by omission.
     */
    val folderStatedEmpty: Boolean = false,
)

/**
 * The lowest sequence number of the newest [limit] messages of a folder holding [exists] of them.
 *
 * Never below 1, so a window larger than the folder is the whole folder rather than a range the
 * server rejects. `Int.MAX_VALUE` does not wrap here: `exists` is at most `Int.MAX_VALUE` and the
 * subtraction of two non-negative Ints cannot overflow downwards past `Int.MIN_VALUE`.
 */
fun folderWindowLowest(exists: Int, limit: Int): Int = (exists - limit + 1).coerceAtLeast(1)

/**
 * The sequence range of the NEXT request of a newest-first folder walk over [lowest]..[highest], or
 * null when the walk has reached the bottom of the window.
 *
 * Newest first because that is the half of a folder the user is looking at: a walk cut off halfway
 * has cached the mail that matters, not the mail of 2019.
 *
 * The step is counted in SEQUENCE POSITIONS, never in messages returned: a page's response can hold
 * fewer messages than it covers positions (`\Deleted` ones are dropped, see [ImapSession.messages]),
 * and stepping by the message count would re-read the same slice for ever on a folder with a hidden
 * message in it.
 */
fun nextFolderPage(lowest: Int, highest: Int, previous: IntRange?, pageSize: Int): IntRange? {
    if (pageSize <= 0 || highest < 1 || lowest > highest) return null
    if (previous == null) return (highest - pageSize + 1).coerceAtLeast(lowest)..highest
    if (previous.first <= lowest) return null
    val top = previous.first - 1
    return (top - pageSize + 1).coerceAtLeast(lowest)..top
}

/**
 * Whether these untagged response lines say the folder is no longer the one the walk started on,
 * which held [startedWith] messages.
 *
 * ⛔ THE GUARD THAT KEEPS THIS WALK FROM DELETING MAIL. A single `FETCH` sees a folder frozen; a
 * walk of several does not. TWO different messages can be lost, and neither of them is the one the
 * shape of the problem suggests:
 *
 * 1. ⭐ **The floor of the window slides out from under it.** `lowest` is computed ONCE, from the
 *    `exists` the `SELECT` reported, and the walk stops there. An `EXPUNGE` moves every sequence
 *    number above it DOWN by one, so after k of them the folder's oldest messages sit k positions
 *    lower than they did — and the k messages that were at `lowest .. lowest+k-1` are now BELOW
 *    `lowest`, where this walk never looks. They belong to the window the user asked for, they are
 *    still on the server, and they are absent from the walk's UIDs: reconciling would delete them
 *    from the cache. ⚠ This is the ONLY way a message of the window goes unread, and it cannot
 *    happen at all when `limit >= exists` — `lowest` is then 1, and nothing can slide below 1. So
 *    the "All" window, the one this work exists for, is arithmetically safe; the count windows
 *    (50/200/500) are not.
 *
 *    ⛔ It is NOT true that a message can fall between two pages. The shift is downwards and the
 *    walk descends, so a renumbering makes the next range RE-READ what the previous one already
 *    had (hence the de-duplication by UID). There is no gap in the middle — only at the bottom.
 *
 * 2. **An arrival while another path is writing.** `* n EXISTS` going UP renumbers nothing, but the
 *    push/IDLE path caches new messages by itself, and it does not go through the recently-mutated
 *    spare. A message that arrived and was cached during the walk is in the cache and not in the
 *    walk's UIDs, so the reconcile would delete what the push had just written.
 *
 * The safe answer to both is the same: finish the walk WITHOUT reconciling. Nothing is lost, the
 * cache merely keeps more than the window, and IMAP re-queries the folder whole at the next refresh
 * (it has no cursor), so a later walk over a quiet folder converges.
 *
 * Three things are read as "it moved", and every one of them is a fact the server stated:
 * - `* n EXPUNGE` — the renumbering itself (RFC 3501 §7.4.1);
 * - `* VANISHED …` — the same thing said by a QRESYNC server (RFC 7162 §3.2.10);
 * - `* n EXISTS` with an `n` other than [startedWith] — the count changed, so something arrived or
 *   left; case 1 or case 2. An `n` that will not parse counts as movement too.
 */
fun folderMoved(untagged: List<List<Any?>>, startedWith: Int): Boolean = untagged.any { line ->
    val second = line.getOrNull(1)
    when {
        line.getOrNull(2) == "EXPUNGE" -> true
        second == "VANISHED" -> true
        line.getOrNull(2) == "EXISTS" -> (second as? String)?.toIntOrNull() != startedWith
        else -> false
    }
}
