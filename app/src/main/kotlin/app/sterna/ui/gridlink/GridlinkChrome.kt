package app.sterna.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkMode
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType

/**
 * The spacious half of the app.
 *
 * The brief's central tension is that **chrome is spacious and the list is dense** (§3), so
 * everything in this file is deliberately generous — 20dp padding, 28dp radii, fully rounded pills
 * — while everything in [GridlinkMessageRow] is deliberately tight. The gradient and the glow live
 * here and behind the nav pill, never under the list: the list scrolls on a flat fill so scrolling
 * costs nothing per frame.
 */

/**
 * Root background for every Gridlink screen.
 *
 * Day paints the dashboard's cyan-to-blue gradient corner to corner. Night and OLED paint a flat
 * fill, because Night's depth comes from a single local glow and OLED's whole point is that the
 * pixels stay off.
 */
@Composable
fun GridlinkBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = GridlinkTheme.colors
    val gradient = colors.gradient
    val aurora = colors.aurora
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                if (gradient != null) {
                    drawRect(
                        Brush.linearGradient(
                            colors = gradient,
                            start = Offset.Zero,
                            end = Offset.Infinite,
                        ),
                    )
                } else {
                    drawRect(colors.background)
                }
                // Painted once into the backdrop layer, not per row. Nothing above this animates
                // it, so it costs a handful of static radial fills on the first frame and nothing
                // on any subsequent scroll frame.
                aurora.forEach { blob ->
                    val r = size.width * blob.radius
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(blob.color, Color.Transparent),
                            center = Offset(size.width * blob.centerX, size.height * blob.centerY),
                            radius = r,
                        ),
                        radius = r,
                        center = Offset(size.width * blob.centerX, size.height * blob.centerY),
                    )
                }
            },
    ) {
        content()
    }
}

/**
 * The Night glow, drawn behind one element.
 *
 * 🔴 Behind THE PRIMARY ELEMENT ONLY. A page-wide wash is explicitly not what the brief asks for,
 * and it also lights up pixels that Night is trying to keep dim. No-ops in Day (the gradient is
 * already doing this job) and in OLED (no glows, ever).
 */
fun Modifier.gridlinkGlow(
    color: Color?,
    radiusMultiplier: Float = 0.9f,
    center: (androidx.compose.ui.geometry.Size) -> Offset = { Offset(it.width / 2f, it.height / 2f) },
): Modifier = if (color == null) this else drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = center(size),
            radius = maxOf(size.width, size.height) * radiusMultiplier,
        ),
    )
}

/**
 * Screen header.
 *
 * ⚠️ The subline is derived, not quoted. The brief never specifies header contents, but it does
 * state the app's entire job in one sentence: find the handful of messages that need a human and
 * dispatch the rest. Printing that split at the top ("3 need you · 14 reports") answers the
 * question the user opened the app to ask, before they scroll at all. If it ever reads as clutter,
 * this is the line to cut.
 */
@Composable
fun GridlinkHeader(
    title: String,
    needsYou: Int,
    reports: Int,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .gridlinkGlow(colors.glow) { Offset(it.width * 0.28f, it.height * 0.5f) }
            // §3's "chrome is spacious" is the only thing holding the top of this screen open, so
            // the header is where it has to be spent. 40 over 20 against a 32sp ExtraBold title
            // reads as breathing room; the previous 28 over 16 just read as a tight app bar.
            .padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = GridlinkSpacing.s40,
                bottom = GridlinkSpacing.s20,
            ),
    ) {
        Text(
            text = title,
            style = GridlinkType.screenTitle,
            color = colors.textPrimary,
        )
        Row(
            modifier = Modifier.padding(top = GridlinkSpacing.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$needsYou need you",
                style = GridlinkType.metadata,
                // Amber: these are the ones still asking something of a human.
                color = colors.attention,
            )
            Text(
                text = "  ·  ",
                style = GridlinkType.metadata,
                color = colors.textSecondary,
            )
            Text(
                text = "$reports reports",
                style = GridlinkType.metadata,
                color = colors.textSecondary,
            )
        }
    }
}

/** Uppercase timeline heading. The caps are content, not a font feature, so they are applied here. */
@Composable
fun GridlinkSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = GridlinkType.sectionLabel,
        color = GridlinkTheme.colors.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.rowHorizontal,
                end = GridlinkSpacing.rowHorizontal,
                top = GridlinkSpacing.s20,
                bottom = GridlinkSpacing.s8,
            ),
    )
}

enum class GridlinkDestination(val label: String, val icon: ImageVector) {
    INBOX("Inbox", Icons.Outlined.Inbox),
    SEARCH("Search", Icons.Outlined.Search),
    FOLDERS("Folders", Icons.Outlined.FolderOpen),
    COMPOSE("Compose", Icons.Outlined.Create),
}

/**
 * The floating navigation pill.
 *
 * Two constraints shaped this. First, §9 bans a floating action button, so **compose lives here**
 * as the trailing destination rather than as a circle over the list. Second, §6b says the selection
 * toolbar must be this pill *transformed in place* — same height, same radius, same inset — so the
 * items are icon-over-11sp-label to match what the toolbar will hold, and the container's
 * dimensions are the ones the morph has to preserve.
 */
@Composable
fun GridlinkNavPill(
    selected: GridlinkDestination,
    onSelect: (GridlinkDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GRIDLINK_PILL_HEIGHT)
            // The halo. Tate's dashboards read as premium because action surfaces appear to
            // EMIT light rather than sit on it, and the halo is what does that. Suppressed in OLED
            // along with every other shadow.
            .gridlinkGlow(
                if (colors.usesShadows) colors.accent.copy(alpha = 0.22f) else null,
                radiusMultiplier = 0.85f,
            )
            // 🔴 Two fills, not one. Every surface in the palette is translucent (Night 85%, Day
            // 55%), which is right for a panel sitting on the background but wrong for one
            // FLOATING OVER THE LIST: at 85% the rows underneath read straight through the pill.
            // Laying the opaque background down first and the translucent surface over it keeps
            // the intended glass tint while making the pill actually opaque. Not a blur — §9 bans
            // live blur, and this costs two rect fills instead of a render-effect pass.
            .background(colors.background, shape)
            .background(colors.surface, shape)
            .border(
                width = GridlinkDimens.hairline,
                color = colors.surfaceBorder,
                shape = shape,
            )
            // 8, not 4. At 4 the active capsule sits almost on the container's own border and the
            // two rounded edges read as one thick smear instead of a capsule inside a pill.
            .padding(GridlinkSpacing.s8),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GridlinkDestination.entries.forEach { destination ->
            GridlinkNavItem(
                destination = destination,
                active = destination == selected,
                onClick = { onSelect(destination) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One nav destination.
 *
 * The active state is a filled gradient capsule with a white glyph, and the inactive state is bare.
 * That is Tate's standing on/off vocabulary: **on is a bright gradient with a white icon, off is
 * the plain tile.** 🔴 Never dim the inactive one with alpha — he reads opacity-dimming as broken
 * rather than as off, and has said so more than once.
 */
@Composable
private fun GridlinkNavItem(
    destination: GridlinkDestination,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    val fill = Modifier.background(
        brush = Brush.linearGradient(
            // 135 degrees, matching the dashboard's action buttons: the lighter stop leads.
            colors = listOf(colors.accent, colors.accent.copy(alpha = 0.62f)),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
        shape = shape,
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .clickable(onClick = onClick)
            // 🔴 No vertical padding here. The container's 8dp already insets the capsule, and
            // adding 8 more each side left 48dp of the pill's 64 for a 20dp icon plus an 11sp
            // label, which silently clipped every label in half.
            .then(if (active) fill else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (active) gridlinkOnAccent(colors.accent) else colors.textSecondary
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = destination.label,
            style = GridlinkType.toolbarLabel,
            color = tint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Foreground colour for anything sitting on an accent fill.
 *
 * Read off the accent's own luminance rather than off the mode, because Day's flat background is
 * the gradient's end stop and not a neutral, so using it here produced cyan text on blue.
 */
fun gridlinkOnAccent(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

/**
 * Height shared by the nav pill and the selection toolbar it morphs into.
 *
 * 🔴 Load-bearing for §6b: if these two ever disagree the morph becomes a resize, which is exactly
 * the "arrival" the brief rules out. Both read this constant.
 */
val GRIDLINK_PILL_HEIGHT = 64.dp

/**
 * The `Auto · Day / Night / OLED` override pill from the dashboard.
 *
 * Lives in settings per §1. It is surfaced in the gallery too, because a three-mode palette that
 * can only be checked by waiting for dusk is a palette nobody checks.
 */
@Composable
fun GridlinkModePill(
    selected: GridlinkMode,
    isAuto: Boolean,
    onSelect: (GridlinkMode?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            // Opaque underlay for the same reason as the nav pill: this one floats over the header.
            .background(colors.background, RoundedCornerShape(GridlinkRadii.pill))
            .background(colors.surface, RoundedCornerShape(GridlinkRadii.pill))
            .border(
                width = GridlinkDimens.hairline,
                color = colors.surfaceBorder,
                shape = RoundedCornerShape(GridlinkRadii.pill),
            )
            .padding(GridlinkSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegment(
            label = if (isAuto) "Auto · ${selected.shortLabel()}" else "Auto",
            active = isAuto,
            onClick = { onSelect(null) },
        )
        GridlinkMode.entries.forEach { mode ->
            ModeSegment(
                label = mode.shortLabel(),
                active = !isAuto && mode == selected,
                onClick = { onSelect(mode) },
            )
        }
    }
}

private fun GridlinkMode.shortLabel(): String = when (this) {
    GridlinkMode.DAY -> "Day"
    GridlinkMode.NIGHT -> "Night"
    GridlinkMode.OLED -> "OLED"
}

@Composable
private fun ModeSegment(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = Modifier
            .background(
                color = if (active) colors.accent else Color.Transparent,
                shape = RoundedCornerShape(GridlinkRadii.pill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = GridlinkSpacing.s12, vertical = GridlinkSpacing.s8),
    ) {
        Text(
            text = label,
            style = GridlinkType.toolbarLabel,
            color = if (active) gridlinkOnAccent(colors.accent) else colors.textSecondary,
        )
    }
}

/** Bottom padding the list needs so its last row can scroll clear of the floating pill. */
val GRIDLINK_PILL_CLEARANCE = GRIDLINK_PILL_HEIGHT + 40.dp
