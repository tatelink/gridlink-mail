package app.sterna.core.data.mail

import app.sterna.core.jmap.WindowWalk

/**
 * The three numbers ONE folder refresh runs on, derived together from one `SyncWindow` and the
 * server's per-request object limit — because two of them are the same number and the third must
 * not be.
 *
 * @property windowTarget how many messages the sync aims to bring back: the window, whole.
 * @property pageSize how many it may ask for in ONE request: the window capped by what the server
 *   admits (`maxObjectsInGet`).
 * @property retentionFloor how many of the folder's newest rows retention keeps whatever their
 *   age: the window, whole — never the capped number.
 */
internal data class FolderSyncSizing(
    val windowTarget: Int,
    val pageSize: Int,
    val retentionFloor: Int,
)

/**
 * How a folder refresh sizes itself, from the account's window ([windowLimit]) and the server's
 * per-request object limit ([serverCapacity], `JmapSession.getBatchSize()`).
 *
 * ⛔ The whole reason this returns a triple rather than a number: `SyncWindow.limit` plays TWO
 * roles. It is the size of the request, and it is the RETENTION FLOOR handed to the prune ("keep
 * at least the newest N whatever their age", Codeberg #110). Only the first may be capped. Capping
 * both — which is what happens the moment the cap is applied at the call site, where one variable
 * feeds both — makes the prune delete mail the user explicitly asked to keep: with the largest
 * window (10 000 messages) on a server admitting 500, retention would keep 500 of them and evict
 * the other 9 500. That is #110 reopened, by the fix for a different bug.
 *
 * The window is NOT clamped to the server's capacity either: a window bigger than one request is
 * fetched in several ([app.sterna.core.jmap.nextWindowPageLimit]). Clamping would silently shrink
 * every window to what the server admits — 100 messages on a server that advertises nothing.
 *
 * [pageSize] is floored at 1 so a server advertising an absurd limit yields a walk that still
 * advances instead of asking for zero messages for ever.
 */
internal fun folderSyncSizing(windowLimit: Int, serverCapacity: Int): FolderSyncSizing =
    FolderSyncSizing(
        windowTarget = windowLimit,
        pageSize = requestPageSize(windowLimit, serverCapacity),
        retentionFloor = windowLimit,
    )

/**
 * How many messages a FULL RE-QUERY of a folder must bring back: [accountWindow], the window set on
 * the account, and only when there is none ([accountWindow] null — an account the store no longer
 * holds) the number [requestedByCaller] asked for.
 *
 * ⛔ The caller does not get to size this branch, and that is the whole point. A full query ends in
 * `EmailDao.replaceMailbox`, which DELETES every cached row it is not given. Two callers reach it
 * with a hardcoded 50 that has nothing to do with the user's setting — the push/worker pass
 * (`refreshAccountFolders`, `limit = 50`) and the unified inbox (`refreshAllInboxes`, `limit = 50`)
 * — because for them 50 is "how much new mail to look at", not "how much to keep". While a cursor
 * existed they only ever ran the delta branch, so the difference never showed.
 *
 * It shows the moment a cursor is missing, and changing "Messages to sync" is now exactly that
 * (`MailRepository.dropSyncCursors`): the window goes 50 → 500, the cursor falls, and a push
 * arriving BEFORE the user pulls would re-query with 50, replace the folder with those 50, and
 * re-arm the cursor. The deltas then resume and never fetch backwards — 50 rows cached for a window
 * of 500, and the setting inert again, by the hand of the fix meant to apply it.
 *
 * ⚠ This sizes the WINDOW only. The per-request page stays what the server negotiated
 * ([requestPageSize]), and the retention floor keeps reading the window and never a request size —
 * capping that one is Codeberg #110 reopened ([folderSyncSizing]).
 */
internal fun fullQueryWindowTarget(accountWindow: Int?, requestedByCaller: Int): Int =
    accountWindow ?: requestedByCaller

/**
 * The THREE numbers a full re-query runs on, all of them derived from [accountWindow] — the window
 * set on the account — and only from [requestedByCaller] when there is no account row left to read
 * ([fullQueryWindowTarget]).
 *
 * ⛔ Why the whole sizing and not just the target. Sizing the window on the account while sizing the
 * REQUEST on the caller left the background passes asking for their own 50 messages per request on
 * a server admitting 500 ([requestPageSize] caps what is wanted; it cannot raise it). The target was
 * then right and the walk was ten times longer than it had to be: a 10 000-message window is 200
 * sequential round trips instead of 20 — on the pass that runs FIRST after "Messages to sync"
 * changed, because dropping the cursor is exactly what opens this branch. On the largest window
 * that is the difference between a long sync and one nobody waits for.
 *
 * ⚠ The server still has the last word on ONE request: the page is `min(window, capacity)`, never
 * the window itself. And the retention floor stays the window and never a request size — capping
 * that one is Codeberg #110 reopened ([folderSyncSizing]).
 */
internal fun fullQuerySizing(accountWindow: Int?, requestedByCaller: Int, serverCapacity: Int): FolderSyncSizing =
    folderSyncSizing(fullQueryWindowTarget(accountWindow, requestedByCaller), serverCapacity)

/**
 * The cache ids a finished JMAP window walk MAY be reconciled against — the JMAP twin of
 * [reconcilableIds], and null for the same reason: `EmailDao.reconcileMailbox` DELETES every cached
 * row outside the set it is given, so "I cannot say" has to be expressible as something other than
 * an empty set.
 *
 * ⛔ The case this closes, and it needs no failure anywhere: an `Email/get` that answers `200 OK`
 * with no `list` key. `decodeList` returns an empty list WITHOUT raising, so the first page adds
 * nothing, [app.sterna.core.jmap.nextWindowPageLimit]'s anti-spin guard (`last.added <= 0`) ends
 * the walk by ordinary return, and the walk hands back an empty id list. "The walk finished" is
 * therefore not the same statement as "the walk saw the folder", and only the second licenses a
 * delete.
 *
 * What tells an empty ANSWER from an empty FOLDER is the query, not the get. An empty folder is a
 * real state that must be able to clear a stale cache — refusing on every empty walk would leave
 * an emptied folder showing its old contents for ever, and on IMAP nothing else would clean up. So:
 *
 * - ids came back ⇒ reconcile against them, whole;
 * - nothing came back and `Email/query` listed NOTHING ([WindowWalk.queryCount] zero) ⇒ the folder
 *   is empty, and the empty keep set is the right answer;
 * - nothing came back but the query listed ids ⇒ REFUSE. The pages the server was asked for never
 *   arrived; the worst case becomes a cache holding more than the window, which is survivable,
 *   instead of a folder emptied by a refresh that learned nothing.
 *
 * ⚠ The refusal also swallows the rarer case where every listed message really was destroyed
 * between the query and the get. That folder keeps stale rows until the ghost sweep or the next
 * full query removes them — the survivable direction, deliberately.
 */
internal fun reconcilableWindowIds(walk: WindowWalk): Set<String>? = when {
    walk.ids.isNotEmpty() -> walk.ids.toHashSet()
    walk.queryCount == 0 -> emptySet()
    else -> null
}

/**
 * How many objects ONE request may ask for: what the caller wants ([wanted]) capped by what the
 * server admits ([serverCapacity]), never below 1.
 *
 * The header crawl that feeds local search asked for a hardcoded 500 with no reference to the
 * advertised limit. On a server admitting less, every page was refused whole; the crawl skips a
 * failed page and gives up after three, so the local index simply stopped being filled — with no
 * surface anywhere to say so, unlike the folder sync, which at least raises an error.
 *
 * Deliberately shared with [folderSyncSizing] so there is exactly ONE expression in the app that
 * turns "what I want" plus "what the server takes" into a request size.
 */
internal fun requestPageSize(wanted: Int, serverCapacity: Int): Int =
    wanted.coerceAtMost(serverCapacity).coerceAtLeast(1)
