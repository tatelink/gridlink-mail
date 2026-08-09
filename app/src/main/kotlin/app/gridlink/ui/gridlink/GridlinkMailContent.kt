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
    /**
     * Sender addresses whose remote images load without being asked, lowercased.
     *
     * 🔴 A set of addresses rather than a boolean on the open message, because it is a standing
     * permission and not a property of what is on screen: it survives the app being killed, it is
     * shared with upstream's reader (same store, same key), and the thread view has to be able to
     * say "you allowed this sender" about a message opened days later.
     *
     * ⚠️ There is no callback beside it. [GridlinkMailContent] is `@Immutable` and a lambda in it
     * would break that equality, recomposing every list row whenever the view model re-emitted.
     * Granting and revoking arrive as a scaffold parameter instead, the same shape the filing
     * actions already use.
     */
    val imageAllowlist: Set<String> = emptySet(),
    /**
     * The server search the pill's text asked for, or null when the pill is empty.
     *
     * Rides in here rather than as its own screen parameter because it is mail, and everything
     * about how mail reaches a screen (null means "nobody is supplying mail", the sample fallback,
     * the @Immutable equality) applies to it unchanged. A gallery run with no account never has
     * one of these; the list filters its fixtures locally instead, which is honest there because
     * the fixtures are the whole mailbox.
     */
    val search: GridlinkSearchContent? = null,
    /**
     * A saved draft, fetched and rebuilt as something the composer can resume, or null.
     *
     * The answer half of a round trip: the scaffold reports a tapped Drafts row via `onEditDraft`,
     * the supplier fetches the message (unread-safe, the fetch never marks it read), and the rebuilt
     * draft arrives here. The scaffold opens the composer over it and immediately calls
     * `onEditDraft(null)` to clear it, so a stale answer cannot reopen the composer later — the
     * same one-shot discipline as [GridlinkChromeState.menuRoute].
     */
    val draftEdit: GridlinkComposeDraft? = null,
)

/**
 * One search, from the account's whole mailbox, as the list draws it.
 *
 * Transient by design: nothing here is cached, and the repository's own doc says why — a search
 * result is an answer to a question, not mail the account holds. [query] is what the answer is FOR,
 * and the screen must check it against what the pill currently says: emissions trail keystrokes,
 * and results for "invoi" drawn under a pill that says "invoice" are stale the moment a newer
 * answer differs.
 */
@Immutable
data class GridlinkSearchContent(
    /** The trimmed text this answer (or in-flight question) is about. Never blank. */
    val query: String,
    /** The hits, newest first, each already wearing the day section its date earns. */
    val results: List<GridlinkMessage> = emptyList(),
    /** True from the keystroke until the server answers: the skeleton draws, not "No results". */
    val searching: Boolean = false,
    /**
     * False when the answer may not be the whole answer (the server stopped at the cap, a folder
     * refused, the folder cache was empty). The footer says "there may be more" instead of letting
     * a capped page read as everything. See [MailSearchResult.complete]'s doc for the three ways.
     */
    val complete: Boolean = true,
    /** The search itself failed (offline, server error). Distinct from an honest empty answer. */
    val failed: Boolean = false,
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
     * Each carries the opaque [GridlinkAttachment.id] a tapped chip reports back, so the supplier
     * can find the real part again.
     */
    val attachments: List<GridlinkAttachment> = emptyList(),
    /**
     * One line about the attachment being opened ("Opening invoice.pdf…", "Too big to open here"),
     * or null when there is nothing to say — which is almost always.
     *
     * Lives on the open message rather than in its own flow because that is the only message it can
     * be about: a download outliving the message it belongs to has nowhere to report, deliberately,
     * for the same reason a slow body fetch is dropped rather than painted under the next message.
     */
    val attachmentStatus: String? = null,
    /** Why the body could not be fetched, in a sentence, or null. */
    val error: String? = null,
    /** True when [html] is really the plain-text part. See [GridlinkMessage.bodyIsPlainText]. */
    val plainText: Boolean = false,
    /** The body's inline images, `cid:` → `data:`. See [GridlinkMessage.inlineImages]. */
    val inlineImages: Map<String, String> = emptyMap(),
    /**
     * How this message can be unsubscribed from, or null when it cannot be — which is most mail,
     * and which is what takes the Unsubscribe row out of the menu.
     *
     * 🔴 Here rather than on [GridlinkMessage] or in the database, and that is the whole design.
     * The headers behind it come back with the BODY, so the action becomes available at the moment
     * the message is open and readable, which is the only moment it can be tapped. Putting it on
     * the list row would mean a column, a migration, and a promise to keep it in step with the
     * server; putting it here means it is either fetched or the row simply is not offered.
     */
    val unsubscribe: GridlinkUnsubscribe? = null,
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
