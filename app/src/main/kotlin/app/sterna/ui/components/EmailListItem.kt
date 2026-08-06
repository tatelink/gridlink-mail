package app.sterna.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.sterna.ui.rememberMotionEnabled
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.jmap.model.Email
import app.sterna.util.MailDates
import kotlinx.coroutines.delay

/**
 * The background a message row is painted with — one place, three states, in this order.
 *
 * Selection comes first and beats everything: the row's own colour is the *only* sign that a row is
 * selected (no checkbox, no icon, no state description) and selection is what arms the destructive
 * actions, so it may never be hidden behind another state.
 *
 * Failing that, an unread row takes [ColorScheme.surfaceContainerHighest] and a read one plain
 * [ColorScheme.surface]: in the dark scheme, bold text alone was too faint a difference to read
 * (#141). surfaceContainerHighest is the role picked because it exists in every Material You
 * scheme, is opaque, is distinct from secondaryContainer, and is *not* surfaceContainerHigh — the
 * resting colour of the swipe reveal drawn underneath the row (InboxScreen).
 *
 * [unreadTint] is the reader's answer to that second state (Settings → Appearance → Message list,
 * on by default). Off, this function gives back exactly what it gave before #141: secondaryContainer
 * when selected, plain surface otherwise, with the flash on top. It governs the BACKGROUND only —
 * the bold weight of an unread row is decided elsewhere and never consults it, so a reader who finds
 * the tint too loud still sees which mail is unread.
 *
 * [flash] is the return-from-message emphasis: it tints whichever base was retained, never a branch
 * of its own, so the flash cannot erase the selected or unread state while it plays — and it is not
 * what [unreadTint] switches off.
 */
internal fun rowBackground(
    scheme: ColorScheme,
    selected: Boolean,
    unread: Boolean,
    unreadTint: Boolean,
    flash: Float,
): Color {
    val base = when {
        selected -> scheme.secondaryContainer
        unread && unreadTint -> scheme.surfaceContainerHighest
        else -> scheme.surface
    }
    return if (flash > 0f) lerp(base, scheme.primary, 0.14f * flash) else base
}

/**
 * The fill behind the small rounded chips a row carries: the account chip of the unified inbox, the
 * [ThreadPill] of a collapsed thread, the [DraftLabel]. Decided once per row, because a row is one
 * state — the chips are painted ON the row and have to know what they sit on.
 *
 * Until [rowBackground] grew a state (#141) they did not: every chip was
 * [ColorScheme.surfaceVariant] over plain [ColorScheme.surface] and that was enough. An unread row
 * now carries [ColorScheme.surfaceContainerHighest], and in the light scheme those two roles are
 * 0xFFE0E4E9 and 0xFFE1E5E9 — one step out of 255 per channel. The chips did not fade there, they
 * disappeared and left their labels floating, on unread rows only, right next to read rows where
 * the same chips were perfectly visible.
 *
 * [ColorScheme.surfaceContainerLowest] is what an unread row's chips take instead: opaque, present
 * in every Material You scheme, and the best of the free roles at standing clear of the unread
 * background in BOTH themes (30/255 per channel in light, 35/255 in dark). Not the only one —
 * `surface` itself clears it by 22/255 — but the one that also carries the chips' own
 * [ColorScheme.onSurfaceVariant] ink at 6.5:1 in light, where surfaceVariant gave 5.1:1. That ink
 * ratio is prose: nothing in this repo executes a composable, so no test holds it.
 *
 * So a chip is darker than its row when the row is read and lighter when it is unread. The rule is
 * "a chip steps away from its row", not "a chip is darker": tying it to a fixed direction is what
 * made the light theme swallow them.
 *
 * [unreadTint] is the same setting [rowBackground] takes, and it has to be the same answer: with the
 * tint off an unread row is plain `surface` again, so lifting its chips to surfaceContainerLowest
 * would fix a collision that is no longer there and put a pale chip on a pale row.
 *
 * **Two rows this function does NOT rescue, both exactly as [ColorScheme.surface] left them before
 * #141 and both filed rather than fixed here:**
 *
 *  - a **selected** row is [ColorScheme.secondaryContainer], and a surfaceVariant chip sits at
 *    1.02:1 on it in the light scheme — it reads by hue, not by lightness, and under a monochrome
 *    Material You palette the two roles are the same tone and the chip is simply gone. Saying
 *    "no unread tint touches it, so it keeps surfaceVariant" is the reasoning-by-role-name that
 *    produced the defect above; it is true and it is not enough.
 *  - during the **return flash** [rowBackground] lerps a read row toward `primary`, and around the
 *    peak it passes within ~5/255 of surfaceVariant: the chip vanishes for a fraction of a second,
 *    on every return to the list. [flash] is deliberately not a parameter here — adding it would
 *    make a chip change colour mid-animation, which is a bigger change than the defect.
 *
 * Both predate this branch (`main` already lerps a surfaceVariant chip's row under it) and neither
 * is what #141 reported.
 */
internal fun chipFill(
    scheme: ColorScheme,
    selected: Boolean,
    unread: Boolean,
    unreadTint: Boolean,
): Color = if (unread && unreadTint && !selected) scheme.surfaceContainerLowest else scheme.surfaceVariant

/**
 * One row in a message list: monogram, sender, subject, preview, time + state.
 * [accountLabel] shows the owning account as a chip — set only in the unified inbox.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmailListItem(
    email: Email,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accountLabel: String? = null,
    accountColor: Color? = null,
    onToggleFavourite: (() -> Unit)? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    // In conversation view: the thread's unread state and how many of its messages are in
    // the viewed folder(s) — the pill shows this in-view count.
    unread: Boolean = !email.isSeen,
    threadCount: Int = 1,
    // Whether the row is a conversation at all (2+ cached messages account-wide). Keeps the
    // pill — and with it the unfold affordance — visible even when only one of the thread's
    // messages sits in this view (the rest in Sent, say).
    threadExpandable: Boolean = threadCount > 1,
    // Conversation expand affordance: when the row is a thread and a handler is given, the
    // count badge becomes a tappable chevron pill that unfolds the thread inline.
    // [expanded] drives the chevron direction and the accessibility expanded/collapsed state.
    onToggleExpand: (() -> Unit)? = null,
    expanded: Boolean = false,
    // Brief emphasis flash when returning to the list from this message.
    highlighted: Boolean = false,
    onHighlightShown: () -> Unit = {},
    // In Sent/Drafts the sender is yourself, so the row shows who the mail went TO
    // instead (Codeberg #59). Falls back to the sender while a cached row carries no
    // recipients (e.g. offline before the folder's first refresh).
    showRecipients: Boolean = false,
) {
    val senderName = email.from.firstOrNull()?.display() ?: stringResource(R.string.message_unknown_sender)
    val recipient = if (showRecipients) email.to.firstOrNull() else null
    val nameLine = if (recipient != null) {
        stringResource(R.string.list_to_recipients, email.to.joinToString { it.display() })
    } else {
        senderName
    }
    val density = LocalListDensity.current
    // Read once, here: both decisions below take it as an argument, so a row cannot answer the
    // question "is this row tinted?" twice and differently. Not a remember {} — that would freeze
    // the row on the value it was first composed with and leave the list half-tinted after a change.
    val unreadTint = LocalUnreadTint.current
    val rowPadding = when (density) {
        ListDensity.COMPACT -> 6.dp
        ListDensity.NORMAL -> 10.dp
        ListDensity.SPACED -> 16.dp
    }
    val previewLines = LocalPreviewLines.current.lines
    // Today → the time, this year → day and month, any other year → a short numeric date, so a
    // 2019 row and a 2026 one can no longer read the same. Numeric on that last step to keep the
    // stamp narrow: it shares this line with the sender's name, which must not be the field that
    // yields. Shared with search, the one view that mixes years by construction.
    val receivedLabel = remember(email.receivedAt) { MailDates.formatListDate(email.receivedAt) }
    val motionOn = rememberMotionEnabled()
    // Return-from-message emphasis: a soft accent tint that rises then fades over ~1s, so the
    // eye lands on the row just left. Skipped (and consumed at once) under reduced motion.
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (!highlighted) return@LaunchedEffect
        if (motionOn) {
            // Small lead-in so the flash lands just after the list is back, not the instant
            // the return transition starts (which read as a touch by mistake).
            delay(100)
            highlight.animateTo(1f, tween(160))
            highlight.animateTo(0f, tween(840, easing = FastOutSlowInEasing))
        }
        onHighlightShown()
    }
    // Entering multi-select ticks, the way K-9 and every list built on the old View system do:
    // there, View.performLongClick() fires the haptic itself. Compose's combinedClickable does
    // not (foundation 1.7), so a long-press dropped the reader into selection mode with no
    // confirmation at all — the one gesture in the list that changes what every later tap means.
    // Kept here rather than at the two call sites so the top-level row and the conversation
    // child cannot drift apart, and so a row with no selection to enter (search results pass no
    // onLongClick) stays silent.
    // NOTE when Compose is next bumped: foundation 1.8 added `hapticFeedbackEnabled = true` to
    // combinedClickable, which performs this same tick itself. Drop this wrapper then, or the
    // long-press buzzes twice.
    val haptics = LocalHapticFeedback.current
    val enterSelection = onLongClick?.let { enter ->
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            enter()
        }
    }
    val chipBackground = chipFill(
        scheme = MaterialTheme.colorScheme,
        selected = selected,
        unread = unread,
        unreadTint = unreadTint,
    )
    val rowColor = rowBackground(
        scheme = MaterialTheme.colorScheme,
        selected = selected,
        unread = unread,
        unreadTint = unreadTint,
        flash = highlight.value,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowColor)
            .combinedClickable(onClick = onClick, onLongClick = enterSelection)
            .padding(start = 16.dp, end = 4.dp, top = rowPadding, bottom = rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(
            seed = (recipient ?: email.from.firstOrNull())?.email ?: senderName,
            label = recipient?.display() ?: senderName,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = nameLine,
                    style = MaterialTheme.typography.titleMedium,
                    // Unread is shown by weight (bold) rather than a status dot.
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // A trashed draft now shows its recipient like a sent mail (#69, 1.3.11), so mark
                // it "(Draft)" wherever it surfaces (Trash especially) to keep the two apart.
                if (email.isDraft) {
                    Spacer(Modifier.width(6.dp))
                    DraftLabel(fill = chipBackground)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = receivedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = email.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.message_no_subject),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Conversation pill — only when 2+ of the thread's messages are actually in
                // this view. The pill is gated on both [threadExpandable] (a real thread
                // account-wide) and [threadCount] > 1: without the count guard a thread whose
                // other messages sit outside this and the Sent view (e.g. Trash) would show a
                // bogus "(1)" pill (Codeberg #75). The in-view count plus a chevron that unfolds
                // the thread inline; tappable when a handler is given (the browse list),
                // otherwise a static count badge (e.g. search results).
                if (threadExpandable && threadCount > 1) {
                    Spacer(Modifier.width(6.dp))
                    ThreadPill(
                        count = threadCount,
                        expanded = expanded,
                        onToggleExpand = onToggleExpand,
                        fill = chipBackground,
                    )
                }
            }
            if (previewLines > 0) {
                email.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = previewLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            accountLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Spacer(Modifier.size(4.dp))
                // Tint the account chip with the account's accent colour when set.
                val chipColor = accountColor ?: MaterialTheme.colorScheme.primary
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = chipColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (accountColor != null) accountColor.copy(alpha = 0.16f)
                            else chipBackground,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        // A small paperclip flags rows whose message carries an attachment — sits just
        // left of the favourite star, mirroring the star's muted weight until earned.
        if (email.hasAttachment) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.AttachFile,
                contentDescription = stringResource(R.string.a11y_has_attachment),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (onToggleFavourite != null) {
            // The ★/☆ glyph is meaningless to a screen reader, so replace its
            // semantics with a clear, state-aware label + button role.
            val favLabel = stringResource(
                if (email.isFlagged) R.string.a11y_unfavourite else R.string.a11y_favourite,
            )
            // Micro-pop: favouriting springs the star and pops it to coral — the one
            // place vivid coral is earned (a positive, deliberate action).
            val pop = remember { Animatable(1f) }
            var firstPass by remember { mutableStateOf(true) }
            LaunchedEffect(email.isFlagged) {
                if (firstPass) { firstPass = false; return@LaunchedEffect }
                if (email.isFlagged && motionOn) {
                    pop.snapTo(1.4f)
                    pop.animateTo(
                        1f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    )
                }
            }
            Text(
                text = if (email.isFlagged) "★" else "☆",
                style = MaterialTheme.typography.titleLarge,
                color = if (email.isFlagged) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(onClick = onToggleFavourite)
                    .padding(8.dp)
                    .scale(pop.value)
                    .clearAndSetSemantics {
                        contentDescription = favLabel
                        role = Role.Button
                    },
            )
        }
    }
}

/**
 * The conversation pill on a collapsed thread row: a message [count] and a chevron that
 * unfolds the thread inline. Tappable when [onToggleExpand] is given (the browse list);
 * a plain count badge otherwise (e.g. search results, which can't expand in place). The
 * chevron flips on [expanded]; screen readers get a labelled button with an expanded/
 * collapsed state, so the affordance is never conveyed by the glyph alone.
 *
 * [fill] is handed down rather than read from the theme here: the pill's fill depends on the row it
 * sits on, which only the row knows (see [chipFill]).
 */
@Composable
private fun ThreadPill(
    count: Int,
    expanded: Boolean,
    onToggleExpand: (() -> Unit)?,
    fill: Color,
) {
    // Belt-and-suspenders: never render a "(1)" (or empty) pill — a conversation is 2+ messages.
    if (count <= 1) return
    val motionOn = rememberMotionEnabled()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (motionOn) tween(180) else snap(),
        label = "threadChevron",
    )
    val pillLabel = stringResource(R.string.a11y_conversation_pill, count)
    val stateLabel = stringResource(
        if (expanded) R.string.a11y_conversation_expanded else R.string.a11y_conversation_collapsed,
    )
    val base = Modifier
        .clip(MaterialTheme.shapes.small)
        .background(fill)
    val pillModifier = if (onToggleExpand != null) {
        base
            .clickable(onClick = onToggleExpand)
            .semantics {
                contentDescription = pillLabel
                stateDescription = stateLabel
                role = Role.Button
            }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    } else {
        base.padding(horizontal = 6.dp, vertical = 1.dp)
    }
    Row(modifier = pillModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onToggleExpand != null) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(rotation),
            )
        }
    }
}

/**
 * A light "(Draft)" chip on a list row whose message is a draft (#69). Matches the static
 * [ThreadPill]'s muted weight so it reads as a marker, not an action; purely informational, so it
 * carries no click or extra semantics (the label text is read out as part of the row).
 *
 * [fill] is handed down rather than read from the theme here, for the reason given on [chipFill]:
 * the chip has to know which row it is painted on.
 */
@Composable
private fun DraftLabel(fill: Color) {
    Text(
        text = stringResource(R.string.draft_label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
