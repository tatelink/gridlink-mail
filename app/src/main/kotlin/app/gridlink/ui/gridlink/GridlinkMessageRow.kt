package app.gridlink.ui.gridlink

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import app.gridlink.ui.theme.gridlinkSenderBarColor

/**
 * The dense half of the app: §4 message rows and the §5 automated-sender bundle.
 *
 * 🔴 Everything here is fixed at [GridlinkDimens.messageRowHeight]. The brief's density target is
 * 13 rows on a folded Fold screen, and that number is the reason for every omission below: no
 * avatars, no snippet, no card, no drop shadow, no gap between rows. Rows are separated by a 1px
 * hairline and nothing else. Adding a third line to this row costs roughly a quarter of the visible
 * inbox, so it needs a very good reason.
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
 */
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
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    // Animated so entering and leaving a selection is a wash of colour across the rows rather than
    // a hard flicker, which at 64dp and this density reads as the list glitching.
    val fill by animateColorAsState(
        targetValue = if (selected || current) colors.selection else Color.Transparent,
        animationSpec = GridlinkMotion.standard(),
        label = "rowSelection",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.messageRowHeight)
            .background(fill)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                        top = GridlinkSpacing.rowVertical,
                        bottom = GridlinkSpacing.rowVertical,
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
                }
            }
        }
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
            .height(GridlinkDimens.messageRowHeight)
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
                        top = GridlinkSpacing.rowVertical,
                        bottom = GridlinkSpacing.rowVertical,
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
) {
    val colors = GridlinkTheme.colors
    Row(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(GridlinkSpacing.bundleIndent)
                .height(GridlinkDimens.messageRowHeight),
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
        )
    }
}
