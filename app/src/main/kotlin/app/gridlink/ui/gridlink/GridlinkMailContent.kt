package app.gridlink.ui.gridlink

import androidx.compose.runtime.Immutable

/**
 * The mail a Gridlink screen is showing, when something behind it has real mail to show.
 *
 * ## Why this exists at all
 * Every screen in this package renders what it is handed, and until now what it was handed was
 * [GridlinkSample] — read directly, from inside the composables. That is what lets the debug gallery
 * draw all of it with no account, no database and no network, and it is worth keeping. So the live
 * mailbox arrives the same way the live send button did (see [GridlinkSender]): as a value passed
 * in, with the sample as the default when nothing passes one.
 *
 * 🔴 Null is not "no mail". Null is **"nobody is supplying mail"**, and the screens fall back to the
 * sample. An account whose inbox is genuinely empty supplies a [GridlinkMailContent] with empty
 * lists, and the empty state draws. Collapsing the two would make a fresh mailbox show somebody
 * else's sample mail, which is the single worst thing this type could do.
 */
@Immutable
data class GridlinkMailContent(
    /** The timeline: everything a person wrote, newest first. */
    val humans: List<GridlinkMessage>,
    /** The automated bundle, or null when nothing in the window qualifies. */
    val bundle: GridlinkBundle?,
    /**
     * True before the first read of the cache has come back: the list draws its skeleton.
     *
     * Only the FIRST read. A refresh over mail that is already on screen is not loading, it is
     * syncing, and it belongs to the chrome row's chip rather than to a skeleton that would blank
     * out mail the user is in the middle of reading.
     *
     * ⚠️ There is deliberately no `refreshing` or `error` field beside this. Both already exist, as
     * [GridlinkChromeState.sync] and the OFFLINE state it can hold, and they are what the sync chip,
     * the menu sheet and the empty state all read. A second copy here would be a second source of
     * truth for the same fact, and the two would disagree the first time a sync started from the
     * drawer instead of from a pull.
     */
    val loading: Boolean = false,
    /**
     * The body of the message the reader has open, once it has been fetched.
     *
     * Separate from the rows because it is fetched separately and on demand: a list fetch asks the
     * server for headers, and the body of the one message being read is a second call. Held as one
     * value rather than a map because one message is open at a time, and a map would quietly become
     * an unbounded cache of message bodies living in UI state.
     */
    val open: GridlinkOpenMessage? = null,
)

/**
 * One opened message's fetched content, keyed by the id it was fetched for.
 *
 * 🔴 [id] is load-bearing, not bookkeeping. A body arrives some time after the tap that asked for
 * it, and by then the reader may have gone back and opened something else. Without the id to check
 * against, a slow fetch would paint the previous message's body into the thread now on screen,
 * under the right sender and the right subject, and there would be nothing about it that looked
 * wrong.
 */
@Immutable
data class GridlinkOpenMessage(
    val id: String,
    /** The HTML body, or "" while it is still being fetched. */
    val html: String,
    /**
     * What is attached, now that the fetch has said. Empty is a real answer here, unlike the list's
     * [GridlinkMessage.attachmentPending], which means "there is one and I do not know what".
     */
    val attachment: GridlinkAttachment? = null,
    /** Why the body could not be fetched, in a sentence, or null. */
    val error: String? = null,
)

/**
 * What the list was just asked to do to some messages.
 *
 * ## Why the list reports the action and not just the ids
 * It used to report `onFiled(ids)`, which was enough while filing meant "the row left the list".
 * With a server behind it, archive and delete are different journeys to different folders, and
 * mark-read is not a removal at all. One callback that says which one keeps the screen's own three
 * entry points (a swipe, the selection toolbar, the open thread's buttons) converging on a single
 * place that decides what a filing MEANS, instead of three places that each have to remember.
 *
 * ⚠️ [MOVE] is reported and currently has nowhere to go: the folder picker it needs is still a tree
 * you read rather than one you pick from. It is in the enum so the list is not lying about what the
 * user pressed, and whoever receives it is expected to say it cannot do it yet rather than to
 * silently archive instead.
 *
 * ## Why [SPAM] and [UNSUBSCRIBE] are here rather than folded into [ARCHIVE]
 * The thread view's three filing buttons all make the same row leave, and the note at their call
 * site has said since they were built that they must not stay the same code: archive moves a
 * message, spam moves it AND trains the filter, unsubscribe sends a request first. Collapsing them
 * at this boundary is exactly how that difference gets lost, because from here on nothing can tell
 * which button was pressed.
 */
enum class GridlinkMailAction {
    ARCHIVE,
    DELETE,
    MOVE,
    SPAM,
    UNSUBSCRIBE,
    MARK_READ,
    MARK_UNREAD,
}
