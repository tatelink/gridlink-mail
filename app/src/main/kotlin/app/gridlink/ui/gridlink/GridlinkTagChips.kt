package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.GridlinkApplication
import app.gridlink.core.data.settings.MailTag
import app.gridlink.core.data.settings.TagColor
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkType
import kotlinx.coroutines.flow.flowOf

/**
 * A tag as it is about to be DRAWN: the wire keyword resolved against this device's definitions.
 *
 * The resolution happens at draw time and not at mapping time, which is the whole reason this type
 * exists separately from [MailTag]. A message row holds keywords ([GridlinkMessage.tags]); the
 * definitions live in settings and can change while the list is on screen. Recolouring a tag
 * therefore repaints every visible chip immediately instead of waiting for the next fetch.
 *
 * @param defined false when no definition was found — another client's tag, or this device's own
 *   before a settings restore. The chip still draws, under its wire name, in a stable colour
 *   derived from that name; the tag manager is where it gets adopted properly.
 */
data class GridlinkTag(
    val keyword: String,
    val label: String,
    val color: Color,
    val defined: Boolean,
)

/**
 * Wire keywords → drawable tags, in the reader's own tag order.
 *
 * 🔴 The order is [definitions]', not the message's. Keywords arrive from the server sorted
 * alphabetically (see `EmailKeywords`), so ordering by the message would make the same two tags
 * sit in a different order on different messages. Ordering by the definitions means a reader who
 * put "Urgent" first sees it first everywhere. Undefined tags follow, alphabetically, because
 * there is nowhere else to put them.
 */
fun resolveTags(keywords: List<String>, definitions: List<MailTag>): List<GridlinkTag> {
    if (keywords.isEmpty()) return emptyList()
    val byKeyword = definitions.associateBy { it.keyword }
    val defined = definitions.filter { it.keyword in keywords }.map {
        GridlinkTag(
            keyword = it.keyword,
            label = it.label.ifBlank { it.keyword },
            color = Color(it.tagColor.argb),
            defined = true,
        )
    }
    val undefined = keywords.filter { it !in byKeyword }.sorted().map {
        GridlinkTag(
            keyword = it,
            label = it,
            color = Color(TagColor.forUnknown(it).argb),
            defined = false,
        )
    }
    return defined + undefined
}

/**
 * This device's tag definitions, observed.
 *
 * Read once per screen rather than per row: it is the same list for every row, and a store
 * subscription inside `items` would be one subscription per visible row all delivering the same
 * answer — the same reasoning as the swipe config in the message list.
 *
 * Empty when there is no container, which is every preview and the gallery harness. That is not a
 * degraded state: an undefined tag draws under its wire name in a derived colour, so a preview
 * showing tags shows them, just uncoloured by anyone's choices.
 */
@Composable
fun rememberMailTagDefinitions(): List<MailTag> {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as? GridlinkApplication)?.container?.settingsRepository
    }
    return remember(store) { store?.mailTags ?: flowOf(emptyList()) }
        .collectAsState(initial = emptyList()).value
}

/**
 * One tag chip.
 *
 * ## The colour vocabulary, and why it is not the filter chip's
 * A filter chip is on or off, so it borrows the app's accent for "on". A tag chip is neither: it
 * always means the same thing (this message carries this tag) and its colour is the tag's identity,
 * not a state. So it is drawn as the tag's own colour at low fill with a matching hairline and
 * full-strength text, which stays legible on the Gridlink glass in both themes and never competes
 * with the accent for the reader's "this is selected" reflex.
 *
 * 🔴 Full-strength text, on purpose. The fill is faint and the TEXT is not, because the label is
 * the part being read. Dimming the label to match the fill is the opacity trap Tate has called
 * out more than once: it reads as broken rather than as decorative.
 *
 * [onClick] is null wherever a chip is only a statement of fact (a list row, the message view).
 * It is set in the tag picker, where tapping a chip applies or removes the tag.
 */
@Composable
fun GridlinkTagChip(
    tag: GridlinkTag,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = remember { RoundedCornerShape(GridlinkRadii.pill) }
    Row(
        modifier = modifier
            .height(TAG_CHIP_HEIGHT)
            .clip(shape)
            .background(tag.color.copy(alpha = TAG_FILL_ALPHA), shape)
            .border(1.dp, tag.color.copy(alpha = TAG_BORDER_ALPHA), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = GridlinkSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tag.label,
            style = GridlinkType.metadata,
            color = tag.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A row of tag chips, for the open message.
 *
 * Wraps rather than scrolls, unlike the filter row: these are read once on the way past the
 * subject, not aimed at, and a message with five tags should show five rather than hide two behind
 * a gesture nobody knows is there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GridlinkTagChipRow(
    tags: List<GridlinkTag>,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
        verticalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
    ) {
        tags.forEach { GridlinkTagChip(it) }
    }
}

/**
 * The list row's tag indicator: one small dot per tag, in the tag's colour.
 *
 * ## Dots and not chips, and this is the constrained decision on the whole feature
 * 🔴 [GridlinkDimens.messageRowHeight] is fixed, and its own note prices a third line at roughly a
 * quarter of the visible inbox. Labelled chips on a row would cost that line on every tagged
 * message, and on a subject that already ellipsizes they would take the width the subject needs.
 * A dot costs 6dp beside the paperclip and answers the question the list is actually asking, which
 * is "which of these did I mark", not "what exactly did I call it". The label is one tap away, on
 * the message itself, where there is room to read it.
 *
 * Capped at [MAX_ROW_DOTS]: beyond that the dots stop being countable at a glance and start being a
 * smear, so the overflow is simply not drawn. The message view has all of them.
 */
@Composable
fun GridlinkTagDots(
    tags: List<GridlinkTag>,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TAG_DOT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tags.take(MAX_ROW_DOTS).forEach { tag ->
            Box(
                modifier = Modifier
                    .size(TAG_DOT)
                    .clip(CircleShape)
                    .background(tag.color),
            )
        }
    }
}

/** Matches the row's star and paperclip, so the three read as one cluster of marks. */
private val TAG_DOT = 6.dp
private val TAG_DOT_GAP = 3.dp
private const val MAX_ROW_DOTS = 3

/** A chip is shorter than a filter pill: it is a label, not a target. */
private val TAG_CHIP_HEIGHT = 26.dp
private const val TAG_FILL_ALPHA = 0.16f
private const val TAG_BORDER_ALPHA = 0.45f
