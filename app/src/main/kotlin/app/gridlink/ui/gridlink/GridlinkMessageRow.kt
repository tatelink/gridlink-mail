package app.gridlink.ui.gridlink

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gridlink.core.data.settings.MailTag
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import app.gridlink.ui.theme.gridlinkSenderBarColor

/**
 * The dense half of the app: §4 message rows and the §5 automated-sender bundle.
 *
 * 🔴 A row is [gridlinkRowHeight] by default. The brief's density target is 13 rows on a folded Fold
 * screen, and that number is the reason for every omission below: no avatars, no snippet, no card,
 * no drop shadow, no gap between rows. Rows are separated by a 1px hairline and nothing else. Adding
 * a third line to this row costs roughly a quarter of the visible inbox, so it needs a very good
 * reason.
 *
 * 🔴 [gridlinkRowHeight] is the user's list-density setting, and it is padding ONLY: see
 * [gridlinkRowVertical]. It defaults to [GridlinkDimens.messageRowHeight] exactly, and none of the
 * omissions above come back at any setting. A row that is allowed to be roomier is not a row that
 * is allowed to grow content.
 *
 * ## The two things that DO add a line, and what they had to prove
 * Both buy their own height ([gridlinkSearchRowHeight], [gridlinkPreviewRowHeight]) and neither
 * touches anything else on the row.
 * - The **search snippet** ([highlight]), on exactly one screen, because a result you cannot see the
 *   match in is a result you have to open to evaluate.
 * - The **body preview** (Settings → Appearance), because the user asked for it by name, off by
 *   default, and can put it back at any time. That is the only warrant any of this needs, and it is
 *   the reason the default had to be NONE rather than the ONE that was sitting unread in storage.
 */

/**
 * 🔴 Clamp before a gutter reaches a layout modifier. Every consumer of a gutter Dp goes through
 * this, no exceptions.
 *
 * [GridlinkMotion.standard] is a spring at dampingRatio 0.85, which is underdamped by design: it
 * overshoots its target and settles back. A gutter collapsing from 44dp to 0 therefore passes
 * through small NEGATIVE values on the way to rest. `Modifier.padding` throws
 * `IllegalArgumentException: Padding must be non-negative` on those, so the app hard-crashed every
 * time the last selected row was deselected — and only then, since that is the only moment the
 * gutter animates back down to zero.
 *
 * Fixing it by damping the spring to 1.0 would work and is the wrong trade: it would flatten the
 * slide's motion everywhere to paper over one arithmetic edge.
 */
private fun Dp.asGutter(): Dp = coerceAtLeast(0.dp)

/**
 * One message.
 *
 * Unread is carried by weight, colour and a 6dp amber dot — never by a background fill, per §4.
 * A filled row would fight the hairline separation and turn the list back into cards.
 *
 * The fill is the single exception, and it is only coherent because of that ban: since no other state
 * fills a row, a fill can only mean "this row is the one being acted on".
 *
 * ## 🔴 [selected] and [current] are two states, not one
 * They look identical, which is deliberate, and an earlier pass took that as licence to collapse them
 * into one flag: §7's two-pane list simply passed `selected = id == currentId` and reused the fill.
 * That was wrong, and the emulator showed it in one screenshot. [selected] does not only fill the row,
 * it also drops a **ticked disc** into the selection gutter, so the moment a multi-select opened that
 * gutter the open thread's row grew a tick and claimed to be part of a selection it was not in. The
 * header said "1 selected" over two ticked rows.
 *
 * So the fill is shared and the tick is not. [selected] means "in the multi-select set" and owns both;
 * [current] means "open in the reading pane" and owns only the fill. Whether the two can be on screen
 * at once is the caller's call, not this row's.
 *
 * ## Why [gutter] is a Dp handed down rather than a Boolean animated here
 * 🔴 Every row, every section label and every divider slides by the same amount at the same moment.
 * If each row ran its own `animateDpAsState` off a Boolean they would be separate animations with
 * separate start times, and at spring settling times the list would visibly shear. The screen owns
 * ONE animation and passes its current value; nothing in the list may animate this independently.
 *
 * ## [highlight] is the search switch, and it is off everywhere else
 * 🔴 Non-null, with a preview to show, is the ONE case where this row grows to
 * [GridlinkDimens.searchRowHeight] and draws a third line. Passing a query here from an inbox list
 * would spend a quarter of the visible rows (see the note at the top of this file) on every screen
 * in the app, so exactly one call site does it: the search results in [GridlinkMessageListScreen].
 *
 * Null and blank mean the same thing on purpose. A search box that has been opened but not typed
 * into would otherwise add the line, and then remove it again on the first keystroke that fails to
 * match nothing, which is the list changing height while the user is reading it.
 */
/**
 * How far a row shrinks while held. Small enough to be felt rather than watched: see the note at
 * the press animation in [GridlinkMessageRow].
 */
private const val PRESS_SCALE = 0.975f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridlinkMessageRow(
    message: GridlinkMessage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    current: Boolean = false,
    gutter: Dp = 0.dp,
    onLongClick: (() -> Unit)? = null,
    highlight: String? = null,
    /** True when this conversation's other messages are already showing beneath it. */
    threadExpanded: Boolean = false,
    /**
     * Unfold or refold this conversation, or null when the row cannot be unfolded.
     *
     * 🔴 Null is what keeps the count control off every row that is not a conversation, and off
     * every list that has no expansion to offer (search results, the folder panel). The count chip
     * draws only when this is non-null AND [GridlinkMessage.isConversation] — a chip that announced
     * "3" with nothing behind the tap would be worse than no chip.
     */
    onToggleThread: (() -> Unit)? = null,
    /**
     * This device's tag definitions, for colouring the row's tag dots.
     *
     * 🔴 Passed in rather than observed here, and that is the only reason it is a parameter at all:
     * the list is identical for every row, so a store subscription inside the row would be one
     * subscription per visible row all delivering the same answer. The screen reads it once (see
     * `rememberMailTagDefinitions`) and hands it down, same as the swipe configuration.
     *
     * Empty is a working default, not a broken one: an undefined tag still draws, in a colour
     * derived from its own name.
     */
    tagDefinitions: List<MailTag> = emptyList(),
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val tags = remember(message.tags, tagDefinitions) { resolveTags(message.tags, tagDefinitions) }
    val snippet = highlight?.takeIf { it.isNotBlank() && message.preview.isNotBlank() }
    // The user's preview setting, resolved to a line count. Zero is the default and the common case.
    //
    // 🔴 Suppressed entirely while a search snippet is showing, and that is not an ordering
    // preference: the snippet IS this preview, with the matched words picked out in the accent.
    // Drawing both would print the same sentence twice, once highlighted and once not.
    val previewLines = if (snippet != null) 0 else gridlinkPreviewLines()
    val previewText = message.preview.takeIf { previewLines > 0 && it.isNotBlank() }
    // Keyed on everything it reads, so scrolling a long result list does not re-run the fold and
    // the scan on every row on every frame.
    val snippetText = snippet?.let { query ->
        remember(message.preview, query, colors.accent) {
            GridlinkHighlight.annotate(message.preview, query, colors.accent)
        }
    }
    // Animated so entering and leaving a selection is a wash of colour across the rows rather than
    // a hard flicker, which at 64dp and this density reads as the list glitching.
    val fill by animateColorAsState(
        targetValue = if (selected || current) colors.selection else Color.Transparent,
        animationSpec = GridlinkMotion.standard(),
        label = "rowSelection",
    )
    // Press feedback: the row takes a small step back under the finger and springs out again when
    // it is let go. Tate asked for "something small" on tapping a message open, and this is the
    // smallest thing that answers it honestly — the row acknowledges the touch at the instant of
    // the touch, which is well before the message body has been fetched and can be shown.
    //
    // 🔴 Deliberately NOT an opening transition. A row that flew out into the reader would have to
    // survive the fetch behind it, and that fetch takes as long as the network takes: on a slow
    // sync the animation would either finish over an empty pane or have to be held, and a held
    // transition is a frozen screen. The reader's arrival is already animated on its own side.
    //
    // ⚠️ [PRESS_SCALE] is scale, not offset, and the row does not clip its children, so nothing is
    // cropped at the edges as it shrinks. Kept just under 1 on purpose: at 64dp a row that visibly
    // shrinks reads as the list wobbling, and the effect is meant to be felt more than seen.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        // Down fast enough to land under the finger, back on the shared spring so the release
        // matches everything else that settles in this UI.
        animationSpec = if (pressed) tween(70) else GridlinkMotion.standard(),
        label = "rowPress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(
                when {
                    snippetText != null -> gridlinkSearchRowHeight()
                    // 🔴 Keyed on the row having text to show, NOT on the setting alone. IMAP rows
                    // carry no preview at all (see ImapMailService, which stores null), so reserving
                    // the space from the setting would give an IMAP account a screenful of tall
                    // blank rows the first time they touched this dial. Sized off the text, that
                    // account simply keeps the two-line list it already has.
                    //
                    // The reflow this trades away barely exists: a JMAP preview arrives in the same
                    // list response that creates the row, so a row that has one has always had one.
                    previewText != null -> gridlinkPreviewRowHeight(previewLines)
                    else -> gridlinkRowHeight()
                },
            )
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .background(fill)
            .combinedClickable(
                interactionSource = interaction,
                // 🔴 No ripple. The scale IS the feedback, and a Material ripple over it would put
                // an expanding grey disc on top of a design layer that has no other ripples in it.
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        GridlinkSelectionSlot(
            selected = selected,
            gutter = gutter,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = gutter.asGutter()),
        ) {
            // Identity bar, hard against the leading edge of the row's content. This is what
            // replaces the avatar.
            //
            // It keeps its sender colour while selected. An earlier pass swapped it to the accent,
            // which made sense when the tick was buried on the far side of the row; with a circle
            // now sitting immediately to its left, an accent bar just smears the accent into one
            // blob and costs the sender colour for nothing.
            //
            // 🔴 On an outgoing row it follows the RECIPIENT's domain, not the sender's. The bar and
            // line 1 are one identity between them, and leaving the bar on the sender would paint a
            // whole Sent list in a single colour (your own domain, on every row) beside a column of
            // names that all differ. The colour has to name the same party the text does.
            Box(
                modifier = Modifier
                    .width(GridlinkDimens.senderBarWidth)
                    .fillMaxHeight()
                    .background(
                        gridlinkSenderBarColor(
                            mode,
                            message.sentTo?.domain?.takeIf { it.isNotBlank() } ?: message.domain,
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = GridlinkSpacing.rowHorizontal,
                        end = GridlinkSpacing.rowHorizontal,
                        top = gridlinkRowVertical(),
                        bottom = gridlinkRowVertical(),
                    ),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // ⚠️ Departs from §4, deliberately. The brief changes only line 2 between
                        // read and unread and keeps the sender primary in both. In practice that
                        // made the two states nearly indistinguishable: line 1 is the loudest thing
                        // in the row, and if it never changes, a read row and an unread row read as
                        // the same object with a small orange dot bolted on.
                        //
                        // So the whole row now steps down when it is read, not just its subject.
                        // Both lines drop to secondary and lose their weight, which turns the list
                        // into two clearly separated tiers you can sort at a glance without reading
                        // a word. This is a colour-token step, not an alpha fade of the same colour.
                        //
                        // In Sent and Drafts this is "To <name>" instead. See
                        // [GridlinkMessage.sentTo]: the sender there is you, on every row.
                        text = message.sentTo?.line ?: message.sender,
                        style = GridlinkType.senderName.copy(
                            fontWeight = if (message.unread) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = if (message.unread) colors.textPrimary else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Takes all the slack, which pins the timestamp to the trailing edge.
                        modifier = Modifier.weight(1f),
                    )
                    // 🔴 Only in the merged inbox, and null everywhere else. In a single-account
                    // list every row would carry the same word, which is a column of noise that
                    // costs the sender its width and says nothing; merged, it is the one thing the
                    // row cannot otherwise tell you, because two accounts on one server routinely
                    // hold the same message ids and the same senders write to both.
                    //
                    // Metadata weight beside the sender's, and capped, so it reads as the row's
                    // provenance rather than as part of the name. The sender takes the slack
                    // (`weight(1f)` above), so a long account label shortens the NAME rather than
                    // pushing the timestamp off the edge; this cap is what stops it going far.
                    if (message.accountLabel != null) {
                        Text(
                            text = message.accountLabel,
                            style = GridlinkType.metadata,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = GridlinkSpacing.s8)
                                .widthIn(max = ACCOUNT_LABEL_MAX),
                        )
                    }
                    Text(
                        text = message.timestamp,
                        style = GridlinkType.timestamp,
                        color = if (message.unread) colors.attention else colors.textSecondary,
                    )
                    if (message.unread) {
                        // ⚠️ Departs from §4, on request. The brief parks the dot in the leading
                        // space *before* the timestamp; it now sits after it, hard against the
                        // trailing edge, with the timestamp butted up against it.
                        //
                        // The trade this makes: a read row has no dot, so its timestamp slides
                        // ~14dp further right than an unread row's, and the timestamps no longer
                        // form one straight column. That is not raggedness by accident. The offset
                        // tracks unread state exactly, so the whole trailing block shifts as a unit
                        // and becomes another read/unread tell rather than noise. If it ever starts
                        // looking like a mistake instead of a pattern, the fix is to reserve the
                        // dot's width on read rows too, which realigns the column and costs the
                        // shift.
                        Spacer(Modifier.width(GridlinkSpacing.s8))
                        Box(
                            modifier = Modifier
                                .size(GridlinkDimens.unreadDot)
                                .background(colors.attention, CircleShape),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The conversation count, and the ONE tappable thing inside a row besides the
                    // row itself. It earns that against the ban two notes below (no tap targets on
                    // line 2) on the ground the star could not meet: it exists only on rows that
                    // stand for more than one message, so it costs nothing on an ordinary row and
                    // cannot be a control the user has to already know about to find.
                    //
                    // ⚠️ Its target is about 34x24dp, under the 48dp guideline, and that is the
                    // honest trade for a 64dp row with two lines in it. Missing it opens the newest
                    // message, which is the row's own action, so a near-miss is never destructive.
                    if (message.isConversation && onToggleThread != null) {
                        GridlinkThreadCount(
                            count = message.threadCount,
                            expanded = threadExpanded,
                            unread = message.unread,
                            onClick = onToggleThread,
                        )
                    }
                    Text(
                        text = message.subject,
                        style = GridlinkType.subject.copy(
                            // "Full weight" for unread. Read drops to Regular and secondary, which
                            // is the only difference between the two states on line 2.
                            fontWeight = if (message.unread) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = if (message.unread) colors.textPrimary else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 🔴 Read-only, and it has to be, at this density. The row is 64dp with no
                    // spare width and a subject that already ellipsizes; a tappable star would
                    // need a 40dp target carved out of line 2 on EVERY row, starred or not,
                    // because a control that only exists once you have used it cannot be found.
                    // So the star is set in the open message (see [GridlinkThreadAction.STAR]) and
                    // this only reports it, exactly like the paperclip beside it.
                    //
                    // Without this the loop had no end: you could star a message, go back, and the
                    // list looked identical, which is indistinguishable from the write having
                    // failed. It is the accent rather than the secondary text colour the paperclip
                    // uses, because a star is a thing you chose and an attachment is a fact about
                    // the message.
                    if (message.starred) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Starred",
                            tint = colors.accent,
                            modifier = Modifier
                                .padding(start = GridlinkSpacing.s8)
                                .size(14.dp),
                        )
                    }
                    if (message.hasAttachment) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = "Has attachment",
                            tint = colors.textSecondary,
                            modifier = Modifier
                                .padding(start = GridlinkSpacing.s8)
                                .size(14.dp),
                        )
                    }
                    // Last in the cluster, after the star and the paperclip, and read-only for the
                    // star's reason: at 64dp there is no width for a control here. Dots rather than
                    // labelled chips because a chip would cost line 2 the width the subject already
                    // does not have — the full argument is on [GridlinkTagDots], and it is the one
                    // real compromise in the feature. The names are one tap away, on the message.
                    GridlinkTagDots(
                        tags = tags,
                        modifier = Modifier.padding(start = GridlinkSpacing.s8),
                    )
                }
                if (snippetText != null) {
                    // Line 3, search only. Secondary colour at 13sp so it sits UNDER the subject in
                    // the hierarchy: it is evidence for the row, not the row's own identity, and the
                    // accent runs inside it are the part meant to catch the eye.
                    //
                    // 🔴 An AnnotatedString built from plain text by GridlinkHighlight. It is never
                    // parsed as markup and must never be handed to the body renderer: this is
                    // arbitrary text off arbitrary mail, drawn in a list.
                    Text(
                        text = snippetText,
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (previewText != null) {
                    // The opt-in preview. Same style, size and colour as the search snippet above,
                    // because it is the same line doing the same job: evidence about the row, sitting
                    // below the subject in the hierarchy rather than beside it.
                    //
                    // 🔴 Plain text, drawn as plain text. This is the body of arbitrary mail; it is
                    // never parsed as markup and never goes near the body renderer.
                    Text(
                        text = previewText,
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                        maxLines = previewLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * How wide the merged inbox's account marker may get before it ellipsises.
 *
 * Sized to hold a short account name or the local part of an address, which is what distinguishes
 * two accounts in practice. Wider would start competing with the sender for line 1; narrower would
 * cut every label to two characters and answer nothing.
 */
private val ACCOUNT_LABEL_MAX = 96.dp

/**
 * The count pill on a conversation row: how many messages are behind it, and the tap that unfolds
 * them.
 *
 * ## Why it leads line 2 instead of joining the star and the paperclip
 * 🔴 Those two are read-only reports and sit at the trailing edge, after a subject that already
 * ellipsizes. This one is a control, and a control at the trailing edge of a shrinking subject would
 * be at a different x on every row, drift as the subject changed, and be the first thing squeezed on
 * a narrow folded screen. Leading, it is in the same place on every conversation row in the list,
 * which is what makes it findable without being explained.
 *
 * ## Why the chevron is here at all when the count already says "more"
 * The count alone says how many; it does not say that the row does anything, nor which way it is
 * pointing right now. The rotation is the only thing on the row that distinguishes a folded
 * conversation from an unfolded one once the children are scrolled off screen above.
 *
 * ⚠️ It borrows [GridlinkMotion.standard]'s spring for that rotation, which overshoots slightly.
 * That is fine here for the same reason it is not fine for a gutter: an angle has no non-negative
 * constraint to violate.
 */
@Composable
private fun GridlinkThreadCount(
    count: Int,
    expanded: Boolean,
    unread: Boolean,
    onClick: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = GridlinkMotion.standard(),
        label = "threadChevron",
    )
    // Outlined rather than filled, and that is the §4 unread ban doing its work one level down: a
    // filled pill on a row would be the second fill in a list where a fill already means "this row
    // is being acted on", and the two would compete on the same row.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = GridlinkSpacing.s8)
            .height(20.dp)
            .border(1.dp, colors.textSecondary, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(start = 5.dp, end = 2.dp),
    ) {
        Text(
            // No cap and no "9+". A thread of 40 is worth knowing about, the number is the only
            // thing telling you, and truncating it would hide exactly the case the row exists for.
            text = count.toString(),
            style = GridlinkType.metadata,
            color = if (unread) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            // Says what the tap DOES, not what the row is, because that is what a screen reader
            // user is choosing between. The count is already announced by the Text beside it.
            contentDescription = if (expanded) "Collapse conversation" else "Expand conversation",
            tint = colors.textSecondary,
            modifier = Modifier
                .size(14.dp)
                .rotate(turn),
        )
    }
}

/**
 * The circle that lives in the strip the slide opens up.
 *
 * Empty ring when unselected, filled accent disc with a tick when selected — so once a selection
 * exists every row advertises that it can join it, which is the whole reason the list slides
 * instead of just tinting the one row that was long-pressed.
 *
 * 🔴 `clipToBounds` is load-bearing. The strip animates from 0 width, and without it the circle
 * would pop out at full size over the sliding row on the first frame instead of being revealed by
 * the slide.
 */
@Composable
private fun GridlinkSelectionSlot(
    selected: Boolean,
    gutter: Dp,
    modifier: Modifier = Modifier,
) {
    if (gutter <= 0.dp) return
    val colors = GridlinkTheme.colors
    val discFill by animateColorAsState(
        targetValue = if (selected) colors.accent else Color.Transparent,
        animationSpec = GridlinkMotion.standard(),
        label = "selectionDisc",
    )
    Box(
        modifier = modifier
            .width(gutter)
            .fillMaxHeight()
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(GridlinkDimens.selectionCircle)
                .background(discFill, CircleShape)
                .border(
                    width = GridlinkDimens.selectionRing,
                    // The ring disappears under its own fill rather than being drawn over it: a
                    // ring plus a disc in the same hue reads as a double edge at this size.
                    color = if (selected) Color.Transparent else colors.textSecondary.copy(alpha = 0.55f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = colors.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** The 1px rule that does all the separating. No gaps, no cards. */
@Composable
fun GridlinkRowDivider(
    modifier: Modifier = Modifier,
    startInset: Dp = GridlinkSpacing.rowHorizontal,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startInset.asGutter())
            .height(GridlinkDimens.hairline)
            .background(GridlinkTheme.colors.divider),
    )
}

/**
 * The bundle row (§5): a morning of robots as one line.
 *
 * ⚠️ Derived: the identity bar is **segmented**, one slice per distinct sender domain inside the
 * bundle, rather than a single flat colour. A bundle has no one domain, and a segmented bar means
 * the collapsed row still says *which* robots are in there at a glance — which is the same job the
 * bar does on a normal row.
 *
 * While a selection is open the row slides with the rest of the list and its circle selects the
 * whole bundle at once. That is the only reading that makes sense: a bundle is not a message, so
 * "select the bundle" can only mean "select what is in it".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridlinkBundleRow(
    bundle: GridlinkBundle,
    expanded: Boolean,
    /** Row tap. The screen decides whether that means expand or select, so the chevron and the
     *  circle never disagree about what a tap does. */
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    gutter: Dp = 0.dp,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = GridlinkMotion.standard(),
        label = "bundleChevron",
    )
    val fill by animateColorAsState(
        targetValue = if (selected) colors.selection else Color.Transparent,
        animationSpec = GridlinkMotion.standard(),
        label = "bundleSelection",
    )
    val domains = bundle.messages.map { it.domain }.distinct()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(gridlinkRowHeight())
            .background(fill)
            .combinedClickable(onClick = onToggle, onLongClick = onLongClick),
    ) {
        GridlinkSelectionSlot(
            selected = selected,
            gutter = gutter,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = gutter.asGutter()),
        ) {
            Column(
                modifier = Modifier
                    .width(GridlinkDimens.senderBarWidth)
                    .fillMaxHeight(),
            ) {
                domains.forEach { domain ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(gridlinkSenderBarColor(mode, domain)),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = GridlinkSpacing.rowHorizontal,
                        end = GridlinkSpacing.rowHorizontal,
                        top = gridlinkRowVertical(),
                        bottom = gridlinkRowVertical(),
                    ),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bundle.title,
                        style = GridlinkType.senderName,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    GridlinkCountBadge(text = "${bundle.unreadCount} new")
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = colors.textSecondary,
                        modifier = Modifier
                            .padding(start = GridlinkSpacing.s8)
                            .size(18.dp)
                            .rotate(chevronRotation),
                    )
                }
                Text(
                    text = bundle.senderSummary,
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Unread count pill.
 *
 * 🔴 Amber, never red. Red is reserved for delete and spending it on a count would blunt the one
 * signal in the app that is supposed to stop a thumb.
 */
@Composable
fun GridlinkCountBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .background(
                color = colors.attention.copy(alpha = 0.16f),
                shape = RoundedCornerShape(GridlinkSpacing.s8),
            )
            .padding(horizontal = GridlinkSpacing.s8, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = GridlinkType.badge,
            color = colors.attention,
        )
    }
}

/**
 * A bundled child row: the normal row, indented, with the containment rule running through it.
 *
 * The rule is drawn per child rather than once behind the group because the children are separate
 * list items — a single continuous line behind a LazyColumn range would have to be measured
 * against scroll offset, and this costs nothing.
 */
@Composable
fun GridlinkBundledChildRow(
    message: GridlinkMessage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    current: Boolean = false,
    gutter: Dp = 0.dp,
    onLongClick: (() -> Unit)? = null,
    /** Forwarded to the child row. See [GridlinkMessageRow]'s parameter of the same name. */
    tagDefinitions: List<MailTag> = emptyList(),
) {
    val colors = GridlinkTheme.colors
    // 🔴 The rule has to be exactly as tall as the row beside it, so it repeats the child's own
    // height rule rather than assuming the default. Get this wrong with the preview setting on and
    // the containment line stops short of the row it is containing, which reads as a rendering bug.
    val previewLines = gridlinkPreviewLines()
    val childHeight = if (previewLines > 0 && message.preview.isNotBlank()) {
        gridlinkPreviewRowHeight(previewLines)
    } else {
        gridlinkRowHeight()
    }
    Row(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(GridlinkSpacing.bundleIndent)
                .height(childHeight),
        ) {
            // 🔴 Leading edge, not trailing. At the trailing edge the rule lands flush against the
            // child's own sender bar and disappears into it, which loses the containment §5 asks
            // for. At the leading edge it continues the line the collapsed bundle row drew, so the
            // group reads as hanging off the parent.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(GridlinkDimens.hairline)
                    .fillMaxHeight()
                    // Not colors.divider. The row separator is tuned to be nearly subliminal, and
                    // at that alpha a vertical run of it vanishes; this rule has to actually be
                    // seen for the indent to mean anything. Secondary text colour rather than the
                    // accent, because containment is structure, not an interactive affordance.
                    .background(colors.textSecondary.copy(alpha = 0.40f)),
            )
        }
        GridlinkMessageRow(
            message = message,
            onClick = onClick,
            modifier = Modifier.weight(1f),
            selected = selected,
            current = current,
            gutter = gutter,
            onLongClick = onLongClick,
            tagDefinitions = tagDefinitions,
        )
    }
}
