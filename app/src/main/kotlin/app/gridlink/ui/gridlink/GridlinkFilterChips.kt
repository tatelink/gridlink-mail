package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FilterListOff
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gridlink.core.data.mail.MailFilter
import app.gridlink.core.data.settings.MailTag
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * The inbox's quick filters: unread, starred, has an attachment, one tag. All behind one button.
 *
 * Brandon asked for the filters themselves in the same breath as turning down LLM categorization of
 * mail: "Not needed at my mail volume. A smart search or filter built into the app would be plenty I
 * think." So they are switches over facts the cache already stores, and not a classifier.
 *
 * ## Where the narrowing actually happens, and why it is not here
 * 🔴 This control reports; it does not filter. A real account's switches travel up to `MailFilter`
 * and land in the SQL that reads the mailbox window, **before** the window's `LIMIT`. That
 * distinction is the whole correctness of the feature: filtering the rows this screen already holds
 * would search only the newest N messages and quietly answer a narrower question than the switch
 * asks. "Starred" would mean "starred, among the fifty most recent", which is not what anybody taps
 * it for.
 *
 * The sample is the one place a local filter is honest, and for the same reason search is filtered
 * locally there: the fixtures ARE the whole mailbox, so there is no rest of it to miss.
 *
 * ## 🔴 One button, not four chips
 * This was a scrolling row of four outlined pills sitting on top of the mail list. Brandon, on the
 * two-pane inbox: *"unread, starred, attachment, tags - don't outline them. can they all live behind
 * a button?"* They can, and the row was paying for itself badly: four controls permanently occupying
 * the top of the list to hold four states that are off almost all of the time, in a 380dp column
 * they did not fit, so the fourth one was always half out of the glass.
 *
 * So it collapses to one control, and the sheet behind it is where the four states live. That costs
 * a tap to CHANGE a filter and costs nothing to READ one, which is the right way round: the question
 * "why is my inbox short" gets answered on the button itself, in words.
 *
 * ## The on/off vocabulary
 * Lit takes the accent gradient and [GridlinkColors.onAccent], exactly as the selected nav
 * destination and the compose button do. 🔴 Unlit is now a bare glyph and label with no fill and no
 * hairline (the outline Brandon called out), and it is NEVER dimmed with alpha — he reads
 * opacity-dimming as broken rather than as off, and has said so more than once. Off is "not filled",
 * not "faded".
 *
 * 🔴 The lit label names the filter rather than saying "Filter": "Unread", or the tag's own name,
 * or "3 filters" once there are more names than fit. A lit control that still said "Filter" would be
 * the one thing on this screen whose label does not answer "what am I looking at".
 */
@Composable
fun GridlinkFilterBar(
    filter: MailFilter,
    onFilter: (MailFilter) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The reader's tags, for the tag section of the sheet.
     *
     * 🔴 Empty means that section is not drawn at all. Tags are a feature you opt into by inventing
     * one, and a filter for a feature nobody here uses is a control that can only ever return
     * nothing.
     */
    tagDefinitions: List<MailTag> = emptyList(),
) {
    var open by remember { mutableStateOf(false) }
    val picked = tagDefinitions.firstOrNull { it.keyword == filter.tag }
    val tagLabel = picked?.let { it.label.ifBlank { it.keyword } }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GridlinkFilterButton(
            label = gridlinkFilterButtonLabel(filter, tagLabel),
            active = filter.isActive,
            // Lit in the TAG's colour when the tag is the only thing narrowing the list, not the
            // accent. The dots on the rows below are that colour, and lighting the app accent
            // instead would break the one thread tying the filter to what it filtered to. With a
            // second condition on, the button is no longer speaking for the tag alone, so it goes
            // back to the accent.
            activeFill = picked
                ?.takeIf { !filter.unread && !filter.starred && !filter.hasAttachment }
                ?.let { Color(it.tagColor.argb) },
            onClick = { open = true },
        )
    }

    if (open) {
        GridlinkFilterSheet(
            filter = filter,
            onFilter = onFilter,
            tagDefinitions = tagDefinitions,
            onDismiss = { open = false },
        )
    }
}

/**
 * The one control, in the seat the four chips used to share.
 *
 * ⚠️ [Role.Button] and not `Checkbox`. It is not a switch any more: it opens the sheet where the
 * switches are. What a screen reader needs from it is the state, and the state is in the label,
 * which is why the label changes rather than a lit fill carrying the whole message.
 */
@Composable
private fun GridlinkFilterButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeFill: Color? = null,
) {
    val colors = GridlinkTheme.colors
    val shape = remember { RoundedCornerShape(GridlinkRadii.pill) }
    val content = if (active) colors.onAccent else colors.textSecondary
    Row(
        modifier = modifier
            .height(GridlinkDimens.headerControl)
            .clip(shape)
            // 🔴 Nothing at all when it is off: no fill, no border. The glass panel it sits on is
            // already a surface, and a hairline drawn on top of one is the outline that got this
            // rewritten. Lit, it is the same accent fill every other selected thing in the app takes.
            .then(
                if (active) {
                    Modifier.background(
                        activeFill?.let { gridlinkAccentFill(it) } ?: gridlinkAccentFill(colors.accent),
                        shape,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
    ) {
        Icon(
            imageVector = Icons.Outlined.FilterList,
            // Null, not the label: the Text beside it is already the row's accessible name, and a
            // described icon next to it makes the control announce itself twice.
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

/**
 * What the button says, which is the whole readout now that the chips are gone.
 *
 * One active condition names itself. Two or more count themselves, because three labels joined by
 * plus signs is longer than the list column is wide and an ellipsised filter is a filter nobody can
 * identify. The empty state is the verb, not a noun: "Filter" is what tapping it does.
 */
private fun gridlinkFilterButtonLabel(filter: MailFilter, tagLabel: String?): String {
    if (filter.isEmpty) return "Filter"
    if (filter.count > 1) return "${filter.count} filters"
    return when {
        filter.unread -> "Unread"
        filter.starred -> "Starred"
        filter.hasAttachment -> "Attachments"
        // The label, and the keyword only as a fallback, for [gridlinkFilterSummary]'s reason: the
        // slug is an implementation detail the reader never typed.
        else -> tagLabel ?: filter.tag.orEmpty()
    }
}

/**
 * The four filters, as rows.
 *
 * 🔴 Toggling does NOT close it. The three booleans AND together (see [matchesFilter]), so turning
 * two on is one thought and one visit; a sheet that shut on the first tap would make combining them
 * a game of reopening the same card. The tag rows behave the same way for consistency, even though
 * only one tag can be on at a time.
 */
@Composable
private fun GridlinkFilterSheet(
    filter: MailFilter,
    onFilter: (MailFilter) -> Unit,
    tagDefinitions: List<MailTag>,
    onDismiss: () -> Unit,
) {
    GridlinkCenterSheet(onDismiss = onDismiss) {
        GridlinkSheetHeading(
            title = "Filter mail",
            icon = Icons.Outlined.FilterList,
            subline = if (filter.isActive) "Tap a lit row to turn it off" else "Combine as many as you like",
        )
        GridlinkSheetDivider()
        GridlinkFilterSheetRow(
            label = "Unread",
            icon = Icons.Outlined.MarkEmailUnread,
            applied = filter.unread,
            onClick = { onFilter(filter.copy(unread = !filter.unread)) },
        )
        GridlinkFilterSheetRow(
            label = "Starred",
            icon = Icons.Filled.Star,
            applied = filter.starred,
            onClick = { onFilter(filter.copy(starred = !filter.starred)) },
        )
        GridlinkFilterSheetRow(
            label = "Attachments",
            icon = Icons.Outlined.AttachFile,
            applied = filter.hasAttachment,
            onClick = { onFilter(filter.copy(hasAttachment = !filter.hasAttachment)) },
        )
        if (tagDefinitions.isNotEmpty()) {
            GridlinkSheetDivider()
            // 🔴 ONE tag, so picking replaces rather than adds. AND-ing two reader-invented tags
            // answers "which mail carries BOTH", which is almost never the question and leaves an
            // empty list with no explanation; the reasoning is written down on [MailFilter.tag].
            tagDefinitions.forEach { tag ->
                GridlinkTagPickerRow(
                    label = tag.label.ifBlank { tag.keyword },
                    color = Color(tag.tagColor.argb),
                    applied = tag.keyword == filter.tag,
                    // Tapping the tag already filtered on clears it, so a tag row is a toggle here
                    // as well as a picker.
                    onClick = { onFilter(filter.copy(tag = tag.keyword.takeIf { it != filter.tag })) },
                )
            }
        }
        if (filter.isActive) {
            GridlinkSheetDivider()
            // The one row that closes, because it is the only one you can have nothing left to do
            // after. Everything off IS the answer, so leaving the card open over an unnarrowed list
            // would be the app waiting for an instruction that has already been given.
            GridlinkSheetAction(
                label = "Clear filters",
                icon = Icons.Outlined.FilterListOff,
                onClick = {
                    onDismiss()
                    onFilter(MailFilter.none)
                },
            )
        }
        GridlinkSheetFooterSpace()
    }
}

/**
 * One switch in the sheet.
 *
 * Dressed as [GridlinkTagPickerRow], which sits directly below it in the same card: same height,
 * same pad line, same "applied is textPrimary, off is textSecondary" rule. A check mark on the
 * trailing edge carries the state a second time, in a shape that survives being read out loud.
 */
@Composable
private fun GridlinkFilterSheetRow(
    label: String,
    icon: ImageVector,
    applied: Boolean,
    onClick: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = GridlinkSpacing.chrome, vertical = GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (applied) colors.accent else colors.textSecondary,
            modifier = Modifier.size(FILTER_CHIP_ICON),
        )
        Text(
            text = label,
            style = GridlinkType.senderName,
            color = if (applied) colors.textPrimary else colors.textSecondary,
            modifier = Modifier.weight(1f).padding(start = GridlinkSpacing.s16),
        )
        if (applied) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(FILTER_CHIP_ICON),
            )
        }
    }
}

/** Matches the sort pill's glyph, so the two read as siblings across screens. */
private val FILTER_CHIP_ICON = 18.dp

/**
 * Keeps the lit chips across process death, so a list that comes back narrowed comes back narrowed.
 *
 * A saver rather than `@Parcelize`: [MailFilter] lives in `core:data`, which has no business
 * knowing that one of its consumers happens to be Android UI. Three booleans and a keyword is the
 * whole of it.
 *
 * ⚠️ The list is heterogeneous, so the restore casts, and the tag is saved as "" rather than null:
 * `listSaver` cannot hold a null. Adding a field means adding it at the END and reading it
 * defensively — a bundle written by the previous build comes back into this one after an update, and
 * an index that is not there must restore as "off" rather than crash the screen it is restoring.
 */
private val GridlinkFilterSaver: Saver<MailFilter, Any> = listSaver(
    save = { listOf(it.unread, it.starred, it.hasAttachment, it.tag.orEmpty()) },
    restore = {
        MailFilter(
            unread = it[0] as Boolean,
            starred = it[1] as Boolean,
            hasAttachment = it[2] as Boolean,
            tag = (it.getOrNull(3) as? String)?.takeIf { keyword -> keyword.isNotEmpty() },
        )
    },
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
 * mailbox window's `LIMIT` (see [GridlinkFilterBar]), and re-testing those rows here would be
 * either a no-op or a disagreement, neither of which is worth the chance of the second one.
 *
 * AND, not OR: each lit chip is a further condition. "Unread" plus "Attachments" means unread mail
 * that has an attachment, which is what the pair of them reads as on screen.
 */
fun GridlinkMessage.matchesFilter(filter: MailFilter): Boolean =
    (!filter.unread || unread) &&
        (!filter.starred || starred) &&
        (!filter.hasAttachment || hasAttachment) &&
        (filter.tag == null || filter.tag in tags)

/**
 * The lit chips as a sentence, for the empty state that has to explain itself.
 *
 * Phrased as what was looked for rather than as a list of chip names ("no unread mail with an
 * attachment", not "filters: unread, attachments"), because the reader's question at that moment is
 * "what did it just search for", not "what is switched on".
 */
fun gridlinkFilterSummary(filter: MailFilter, tagLabel: String? = null): String {
    if (filter.isEmpty) return "No mail here"
    val head = when {
        filter.unread && filter.starred -> "No unread starred mail"
        filter.unread -> "No unread mail"
        filter.starred -> "No starred mail"
        else -> "No mail"
    }
    val withAttachment = if (filter.hasAttachment) "$head with an attachment" else head
    // 🔴 The label, and the keyword only as a fallback. A reader who called the tag "Tax 2026" reads
    // an empty state naming "tax-2026" as a different thing than the one they picked, and the slug
    // is an implementation detail they never typed.
    val keyword = filter.tag ?: return withAttachment
    val name = tagLabel ?: keyword
    return "$withAttachment tagged “$name”"
}
