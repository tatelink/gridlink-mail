package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gridlink.core.data.settings.MailTag
import app.gridlink.core.data.settings.TagColor
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * Put tags on the open message, or take them off. Reached from the thread's More sheet.
 *
 * ## Why it lists the definitions rather than the message
 * The rows are every tag this device knows about, ticked where the message carries one, which makes
 * applying and removing the same gesture in the same place. A picker that listed only what was
 * already on the message would need a separate "add" affordance, and the tag list is short by
 * construction: it is a set of words the reader invented, not a folder tree.
 *
 * 🔴 The sheet stays open after a tap. Tagging is plural far more often than starring is — "urgent"
 * and "receipts" go on together — and a sheet that closed on the first tick would make the second
 * tag cost the same three taps as the first. Dismiss is the back gesture or the scrim, like every
 * other sheet in the app.
 *
 * ## The tick is optimistic, and it has to be
 * [applied] comes from the cached row, which does not change until the write has round-tripped to
 * the server and been written back to Room. That is a few hundred milliseconds of a row that looks
 * dead, on a control the user is likely to tap twice in a row. So the tap is remembered locally and
 * the local answer wins until the truth agrees — the same shape, and the same reasoning, as the
 * thread's star (see `GridlinkThreadScreen`). ⚠️ A write that FAILS never agrees, so the tick stays
 * on until the sheet is closed and reopened; that is the failure mode every action on this screen
 * has, and fixing it properly means an error surface the thread does not have yet.
 */
@Composable
fun GridlinkTagPickerSheet(
    definitions: List<MailTag>,
    applied: List<String>,
    onSetTag: (keyword: String, applied: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Open the tag manager. Null where there is nowhere to send them, which is the debug gallery;
     * the empty state then just says tags are made in Settings rather than offering a dead button.
     */
    onManageTags: (() -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    GridlinkCenterSheet(onDismiss = onDismiss) {
        GridlinkSheetHeading(
            title = "Tags",
            icon = Icons.Outlined.Sell,
            subline = if (definitions.isEmpty()) null else "Tap to apply or remove",
        )
        GridlinkSheetDivider()
        if (definitions.isEmpty()) {
            Text(
                // 🔴 Says where they come from. An empty sheet with no explanation reads as a
                // feature that is broken rather than one that has not been set up, and the tag
                // manager is two screens away in a place nobody would guess.
                text = "No tags yet. Create them in Settings, under Tags.",
                style = GridlinkType.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(
                    horizontal = GridlinkSpacing.chrome,
                    vertical = GridlinkSpacing.s16,
                ),
            )
        }
        definitions.forEach { tag ->
            val on = pending[tag.keyword] ?: (tag.keyword in applied)
            // Clears itself the moment the cached row agrees, so there is one source of this state
            // for all but the handful of frames the write is in flight.
            if (pending[tag.keyword] == (tag.keyword in applied)) pending.remove(tag.keyword)
            GridlinkTagPickerRow(
                label = tag.label.ifBlank { tag.keyword },
                color = Color(tag.tagColor.argb),
                applied = on,
                onClick = {
                    pending[tag.keyword] = !on
                    onSetTag(tag.keyword, !on)
                },
            )
        }
        // Tags the message carries that this device has no definition for: another client's, or its
        // own before a settings restore. Removable but not describable, which is exactly what the
        // reader can honestly be offered here.
        val strays = applied.filter { keyword -> definitions.none { it.keyword == keyword } }.sorted()
        strays.forEach { keyword ->
            val on = pending[keyword] ?: true
            if (pending[keyword] == true) pending.remove(keyword)
            GridlinkTagPickerRow(
                label = keyword,
                color = Color(TagColor.forUnknown(keyword).argb),
                applied = on,
                onClick = {
                    pending[keyword] = !on
                    onSetTag(keyword, !on)
                },
            )
        }
        if (onManageTags != null) {
            GridlinkSheetDivider()
            GridlinkSheetAction(
                label = "Manage tags",
                icon = Icons.Outlined.Sell,
                onClick = onManageTags,
            )
        }
        GridlinkSheetFooterSpace()
    }
}

/**
 * One tag row: its colour, its name, and whether this message carries it.
 *
 * A dot and a tick rather than a checkbox. The dot is the tag's identity and has to be here anyway
 * for the chips elsewhere to be recognisable; a checkbox beside it would put two controls' worth of
 * furniture on a row that answers one yes-or-no.
 */
@Composable
internal fun GridlinkTagPickerRow(
    label: String,
    color: Color,
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
        Box(
            modifier = Modifier
                .size(PICKER_DOT)
                .clip(CircleShape)
                .background(color),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = GridlinkSpacing.s16),
        ) {
            Text(
                text = label,
                style = GridlinkType.senderName,
                color = if (applied) colors.textPrimary else colors.textSecondary,
            )
        }
        if (applied) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Applied",
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Big enough to read as the tag's colour, small enough not to read as a button. */
private val PICKER_DOT = 12.dp
