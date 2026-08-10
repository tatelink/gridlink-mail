package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gridlink.core.data.mail.MailFilter
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * The inbox's quick filters: unread, starred, has an attachment.
 *
 * Tate asked for exactly this, in the same breath as turning down LLM categorization of mail:
 * "Not needed at my mail volume. A smart search or filter built into the app would be plenty I
 * think." So it is three switches over facts the cache already stores, and not a classifier.
 *
 * ## Where the narrowing actually happens, and why it is not here
 * 🔴 This row reports; it does not filter. A real account's chips travel up to `MailFilter` and land
 * in the SQL that reads the mailbox window, **before** the window's `LIMIT`. That distinction is the
 * whole correctness of the feature: filtering the rows this screen already holds would search only
 * the newest N messages and quietly answer a narrower question than the chip asks. "Starred" would
 * mean "starred, among the fifty most recent", which is not what anybody taps it for.
 *
 * The sample is the one place a local filter is honest, and for the same reason search is filtered
 * locally there: the fixtures ARE the whole mailbox, so there is no rest of it to miss.
 *
 * ## The on/off vocabulary
 * A lit chip takes the accent gradient and [GridlinkColors.onAccent], exactly as the selected nav
 * destination and the compose button do. 🔴 An unlit one gets the collapsed search pill's glass
 * (14% surface, 35% hairline) at full opacity and is NEVER dimmed with alpha — Tate reads
 * opacity-dimming as broken rather than as off, and has said so more than once. Off is "not
 * filled", not "faded".
 *
 * ## Why it scrolls sideways
 * Three labelled pills do not fit the 380dp list column of the two-pane layout, and an ellipsised
 * filter label is a filter nobody can identify. Scrolling is the one option that keeps every label
 * whole at every width; the alternative was dropping the labels and shipping three unexplained
 * glyphs.
 */
@Composable
fun GridlinkFilterChips(
    filter: MailFilter,
    onFilter: (MailFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GridlinkFilterChip(
            label = "Unread",
            icon = Icons.Outlined.MarkEmailUnread,
            active = filter.unread,
            onClick = { onFilter(filter.copy(unread = !filter.unread)) },
        )
        GridlinkFilterChip(
            label = "Starred",
            icon = Icons.Filled.Star,
            active = filter.starred,
            onClick = { onFilter(filter.copy(starred = !filter.starred)) },
        )
        GridlinkFilterChip(
            label = "Attachments",
            icon = Icons.Outlined.AttachFile,
            active = filter.hasAttachment,
            onClick = { onFilter(filter.copy(hasAttachment = !filter.hasAttachment)) },
        )
    }
}

/**
 * One filter chip.
 *
 * Sized and dressed as the contacts sort pill and the collapsed search pill, because it is the same
 * kind of thing sitting in the same kind of seat. What it adds is the lit state.
 *
 * ⚠️ [Role.Checkbox] rather than `Button`. A screen reader announcing "Starred, button" leaves out
 * the only fact that matters, which is whether the list is currently narrowed to starred mail.
 */
@Composable
private fun GridlinkFilterChip(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = remember { RoundedCornerShape(GridlinkRadii.pill) }
    val content = if (active) colors.onAccent else colors.textSecondary
    Row(
        modifier = modifier
            .height(GridlinkDimens.headerControl)
            .clip(shape)
            .then(
                if (active) {
                    Modifier.background(gridlinkAccentFill(colors.accent), shape)
                } else {
                    Modifier
                        .background(colors.surface.copy(alpha = 0.14f), shape)
                        .border(
                            GridlinkDimens.hairline,
                            colors.surfaceBorder.copy(alpha = 0.35f),
                            shape,
                        )
                },
            )
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
    ) {
        Icon(
            imageVector = icon,
            // Null, not the label: the Text below is already the row's accessible name, and a
            // described icon beside it makes every chip announce itself twice.
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(FILTER_CHIP_ICON),
        )
        Text(
            text = label,
            style = GridlinkType.metadata,
            color = content,
            maxLines = 1,
        )
    }
}

/** Matches the sort pill's glyph, so the two read as siblings across screens. */
private val FILTER_CHIP_ICON = 18.dp

/**
 * Keeps the lit chips across process death, so a list that comes back narrowed comes back narrowed.
 *
 * A saver rather than `@Parcelize`: [MailFilter] lives in `core:data`, which has no business
 * knowing that one of its consumers happens to be Android UI. Three booleans in a list is the whole
 * of it.
 */
private val GridlinkFilterSaver: Saver<MailFilter, Any> = listSaver(
    save = { listOf(it.unread, it.starred, it.hasAttachment) },
    restore = { MailFilter(unread = it[0], starred = it[1], hasAttachment = it[2]) },
)

/**
 * [MailFilter], restored across process death, and reported back up on the way in.
 *
 * 🔴 The re-report is not optional and it is the same trap the search pill has: the saved state
 * comes back into this screen, but nobody upstream heard the chips being tapped, so a restored
 * process would draw three lit chips over a completely unfiltered list. [onFilter] fires once on
 * composition when anything is lit, which is a no-op for the sample and the correction for an
 * account.
 */
@Composable
fun rememberGridlinkFilter(
    initial: MailFilter,
    onFilter: (MailFilter) -> Unit,
): MutableState<MailFilter> {
    val state = rememberSaveable(stateSaver = GridlinkFilterSaver) { mutableStateOf(initial) }
    LaunchedEffect(Unit) {
        if (state.value.isActive) onFilter(state.value)
    }
    return state
}

/**
 * Does this row survive [filter]?
 *
 * ⚠️ The SAMPLE's filter, and nothing else uses it. A real account is narrowed in SQL before the
 * mailbox window's `LIMIT` (see [GridlinkFilterChips]), and re-testing those rows here would be
 * either a no-op or a disagreement, neither of which is worth the chance of the second one.
 *
 * AND, not OR: each lit chip is a further condition. "Unread" plus "Attachments" means unread mail
 * that has an attachment, which is what the pair of them reads as on screen.
 */
fun GridlinkMessage.matchesFilter(filter: MailFilter): Boolean =
    (!filter.unread || unread) &&
        (!filter.starred || starred) &&
        (!filter.hasAttachment || hasAttachment)

/**
 * The lit chips as a sentence, for the empty state that has to explain itself.
 *
 * Phrased as what was looked for rather than as a list of chip names ("no unread mail with an
 * attachment", not "filters: unread, attachments"), because the reader's question at that moment is
 * "what did it just search for", not "what is switched on".
 */
fun gridlinkFilterSummary(filter: MailFilter): String {
    if (filter.isEmpty) return "No mail here"
    val head = when {
        filter.unread && filter.starred -> "No unread starred mail"
        filter.unread -> "No unread mail"
        filter.starred -> "No starred mail"
        else -> "No mail"
    }
    return if (filter.hasAttachment) "$head with an attachment" else head
}
