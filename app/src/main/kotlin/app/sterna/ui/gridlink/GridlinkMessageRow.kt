package app.sterna.ui.gridlink

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType
import app.sterna.ui.theme.gridlinkSenderBarColor

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
 * One message.
 *
 * Unread is carried by weight, colour and a 6dp amber dot — never by a background fill, per §4.
 * A filled row would fight the hairline separation and turn the list back into cards.
 *
 * [selected] is the single exception, and it is only coherent because of that ban: since no other
 * state fills a row, a fill can only mean selected. The same treatment marks the open thread in the
 * unfolded two-pane list (§7), so the two states never need separate visuals.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridlinkMessageRow(
    message: GridlinkMessage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    // Animated so entering and leaving a selection is a wash of colour across the rows rather than
    // a hard flicker, which at 64dp and this density reads as the list glitching.
    val fill by animateColorAsState(
        targetValue = if (selected) colors.selection else Color.Transparent,
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
        // Identity bar, hard against the leading edge. This is what replaces the avatar.
        //
        // It goes accent while selected rather than keeping its sender colour: at 3dp this bar is
        // the loudest structural mark on the row, and leaving it in place under an accent fill puts
        // two competing hues on the same edge. Identity yields to state for as long as the state
        // lasts.
        Box(
            modifier = Modifier
                .width(GridlinkDimens.senderBarWidth)
                .fillMaxHeight()
                .background(
                    if (selected) colors.accent else gridlinkSenderBarColor(mode, message.domain),
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
                    // §4 changes only line 2 between read and unread: "read state: subject drops
                    // to secondary colour and Regular weight". The sender stays primary in both,
                    // so the row's identity never dims and only its urgency does.
                    text = message.sender,
                    style = GridlinkType.senderName,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Takes all the slack, which is what pins the timestamp to the trailing edge.
                    modifier = Modifier.weight(1f),
                )
                // Both markers live in the timestamp's leading space, so neither costs width, and
                // the tick supersedes the dot: a row you have picked up is no longer telling you
                // about its unread state.
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(GridlinkDimens.selectionTick)
                            .background(colors.accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = gridlinkOnAccent(colors.accent),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Spacer(Modifier.width(GridlinkSpacing.s8))
                } else if (message.unread) {
                    // §4: the dot sits in the timestamp's leading space, so it costs no width.
                    Box(
                        modifier = Modifier
                            .size(GridlinkDimens.unreadDot)
                            .background(colors.attention, CircleShape),
                    )
                    Spacer(Modifier.width(GridlinkSpacing.s8))
                }
                Text(
                    text = message.timestamp,
                    style = GridlinkType.timestamp,
                    color = if (message.unread) colors.attention else colors.textSecondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.subject,
                    style = GridlinkType.subject.copy(
                        // "Full weight" for unread. Read drops to Regular and secondary, which is
                        // the only difference between the two states on line 2.
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

/** The 1px rule that does all the separating. No gaps, no cards. */
@Composable
fun GridlinkRowDivider(
    modifier: Modifier = Modifier,
    startInset: androidx.compose.ui.unit.Dp = GridlinkSpacing.rowHorizontal,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startInset)
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
 */
@Composable
fun GridlinkBundleRow(
    bundle: GridlinkBundle,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = GridlinkMotion.standard(),
        label = "bundleChevron",
    )
    val domains = bundle.messages.map { it.domain }.distinct()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.messageRowHeight)
            .clickable(onClick = onToggle),
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
            onLongClick = onLongClick,
        )
    }
}
