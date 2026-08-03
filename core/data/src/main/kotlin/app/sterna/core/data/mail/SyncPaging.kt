package app.sterna.core.data.mail

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
 * feeds both — makes the prune delete mail the user explicitly asked to keep: with "All" (1000) on
 * a server admitting 500, retention would start evicting everything older than the window past the
 * 500th message. That is #110 reopened, by the fix for a different bug.
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
