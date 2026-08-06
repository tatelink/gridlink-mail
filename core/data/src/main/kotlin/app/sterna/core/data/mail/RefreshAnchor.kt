package app.sterna.core.data.mail

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * Where a REFRESH must reload the list, in ROWS FROM THE TOP OF THE FOLDER, so that the message
 * the user is looking at stays where it is.
 *
 * Every write into `emails` invalidates the Room paging source under the list, and Paging answers
 * with a refresh anchored on the reader. That is the normal design — and with placeholders off it
 * walks the list back to the top, which is the "scrolling jumps backwards, and older mail can
 * never be reached" report. Two library facts, read in the sources of the exact versions this app
 * builds against (paging 3.3.5, room 2.6.1), make it happen:
 *
 *  - `PageFetcherSnapshotState.currentPagingState` builds `anchorPosition` starting from
 *    `placeholdersBefore`, and `placeholdersBefore` is hard-wired to 0 when placeholders are
 *    disabled. So the anchor Paging hands us is an index INTO THE LOADED WINDOW, not into the
 *    folder.
 *  - `RoomPagingUtil.getClippedRefreshKey` returns `anchorPosition - initialLoadSize / 2`, and
 *    `getOffset` feeds that straight into `LIMIT … OFFSET` — an index INTO THE FOLDER.
 *
 * The two agree only while the loaded window starts at row 0. After one anchored reload it starts
 * at [firstLoadedItemsBefore] rows down, and from then on EVERY reload throws away everything
 * above the window's start: the key is `anchor − 75` where `anchor` is counted from the window,
 * so the list moves back by exactly the window's own offset. A reader who has stopped on a message
 * is taken all the way to the newest mail (offset 225, anchor 75 ⇒ 0); a reader still scrolling
 * loses that offset each time and gains it back by scrolling, which is the reported "it keeps
 * pulling me up and I can't get past a certain date" rather than a single jump to the top. The
 * deeper you are, the more pages the mediator writes to serve you, so the more often it happens.
 *
 * Re-basing the anchor on the window's own offset closes the gap: the reload lands on the same
 * folder rows the reader was on, the anchored row is still in the new window, and the list keeps
 * its place through the write (`LazyColumn` in `InboxScreen` keys its rows by account+id).
 *
 * @param anchorPosition Paging's anchor, null when nothing has been accessed yet.
 * @param firstLoadedItemsBefore `itemsBefore` of the first loaded page — the offset Room queried
 *   it at ([androidx.room.paging.util.queryDatabase] always sets it, placeholders or not). Any
 *   negative value (`COUNT_UNDEFINED`) is read as "window starts at the top".
 * @param initialLoadSize how many rows the reload will fetch; half of it is kept above the anchor.
 * @param placeholdersEnabled when true the anchor is ALREADY a folder-wide index and re-basing it
 *   would count the offset twice — so the offset is not added. Turning placeholders on must not
 *   silently double this.
 */
internal fun refreshOffsetForAnchor(
    anchorPosition: Int?,
    firstLoadedItemsBefore: Int,
    initialLoadSize: Int,
    placeholdersEnabled: Boolean,
): Int? {
    if (anchorPosition == null) return null
    val windowStart = if (placeholdersEnabled) 0 else firstLoadedItemsBefore.coerceAtLeast(0)
    return (windowStart + anchorPosition - initialLoadSize / 2).coerceAtLeast(0)
}

/**
 * A [PagingSource] that loads exactly what [delegate] loads, and answers the refresh key of
 * [refreshOffsetForAnchor] instead of the delegate's own.
 *
 * It exists because the key is the only thing wrong with Room's `LimitOffsetPagingSource`: its
 * loading, its invalidation and its counting are all correct, and it is generated code we cannot
 * subclass per query.
 *
 * Invalidation is forwarded BOTH ways, and each direction answers a different question:
 *  - delegate → us: a write invalidates Room's source, and if that never reached the source Paging
 *    holds, the list would freeze on the generation the write invalidated;
 *  - us → delegate: `PageFetcher` invalidates the source it is replacing, and Room's source has to
 *    hear it or it keeps serving loads for a generation nobody presents (its `invalid` flag is
 *    what makes it answer `LoadResult.Invalid`). NOT a leak fix: `ThreadSafeInvalidationObserver`
 *    registers with `addWeakObserver`, so a forgotten source is collected either way.
 */
internal class AnchoredRefreshPagingSource<Value : Any>(
    private val delegate: PagingSource<Int, Value>,
) : PagingSource<Int, Value>() {

    init {
        delegate.registerInvalidatedCallback(::invalidate)
        registerInvalidatedCallback(delegate::invalidate)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Value> = delegate.load(params)

    override fun getRefreshKey(state: PagingState<Int, Value>): Int? = refreshOffsetForAnchor(
        anchorPosition = state.anchorPosition,
        firstLoadedItemsBefore = state.pages.firstOrNull()?.itemsBefore ?: 0,
        initialLoadSize = state.config.initialLoadSize,
        placeholdersEnabled = state.config.enablePlaceholders,
    )

    override val jumpingSupported: Boolean get() = delegate.jumpingSupported

    override val keyReuseSupported: Boolean get() = delegate.keyReuseSupported
}
