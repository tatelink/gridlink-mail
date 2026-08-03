package app.sterna.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sterna.R

/**
 * Gridlink design tokens — the single source of truth for the fork's UI.
 *
 * Everything here comes from `docs/GRIDLINK-UI-BRIEF.md`, which specifies the token set as the
 * FIRST deliverable precisely so no screen ever hard-codes a value. If a colour, size, weight or
 * spring lives in a composable instead of this file, that is a bug.
 *
 * ## Why this exists next to [ArcticColorScheme] rather than replacing it
 * Upstream Sterna's own DESIGN.md specifies Material You dynamic colour, hierarchy from space
 * rather than hue, and motion capped at 250ms with no springs. The brief deliberately inverts all
 * three: a fixed three-mode palette inherited from the Home Assistant dashboard, meaning encoded
 * IN hue, and springs specified as stiffness/damping with no durations anywhere. Keeping the
 * Gridlink tokens in their own file leaves upstream's theme intact, so merges from
 * codeberg.org/emon/sterna-mail conflict on as little as possible.
 *
 * ## The rule that governs the three modes
 * 🔴 The modes differ ONLY in colour. Same layout, same spacing, same type scale, same geometry in
 * all three — a mode switch should read as the lights changing in a room, not a different app.
 * That is why [GridlinkSpacing], [GridlinkDimens], [GridlinkRadii], [GridlinkType] and
 * [GridlinkMotion] are mode-independent objects, and only [GridlinkColors] varies.
 */

// ---------------------------------------------------------------------------------------------
// Modes
// ---------------------------------------------------------------------------------------------

/**
 * The three-mode ladder. Not a light/dark pair: [OLED] is a third rung with its own hue family
 * (amber on true black) that exists to keep pixels physically off late at night.
 */
enum class GridlinkMode {
    DAY,
    NIGHT,
    OLED,
}

/**
 * Default automatic ladder, by local hour.
 *
 * ⚠️ These cutoffs are a placeholder: the brief says the ladder runs "Day, then Night at dusk,
 * then OLED late at night", and real dusk moves through the year. Wire this to actual sunset when
 * the settings screen lands; until then the boundaries are fixed and documented here rather than
 * scattered through the UI. The manual override pill (`Auto · Day` / `Night` / `OLED`) always wins
 * over this function.
 */
fun gridlinkModeForHour(hour: Int): GridlinkMode = when (hour) {
    in 6..19 -> GridlinkMode.DAY
    in 20..22 -> GridlinkMode.NIGHT
    else -> GridlinkMode.OLED
}

// ---------------------------------------------------------------------------------------------
// Colour
// ---------------------------------------------------------------------------------------------

/**
 * One palette. Roles are named for MEANING, not appearance, because the brief fixes a semantic
 * grammar carried over from the dashboard and every screen must obey it:
 *
 * - [positive] — settled, safe, done. Archive.
 * - [attention] — needs a human. Unread.
 * - [accent] — interactive, tappable.
 * - [destructive] — 🔴 DELETE AND NOTHING ELSE. Never errors, never badges, never unread counts.
 *   It is the only hue outside the inherited system, so it reads as an alarm on its own without
 *   needing size or weight to carry the warning. Spending it anywhere else destroys that.
 */
/**
 * One radial blob in the aurora backdrop.
 *
 * Positions are fractions of the screen (0,0 top-left to 1,1 bottom-right) and may sit outside it,
 * which is how a blob shows only its edge. [radius] is a multiple of screen WIDTH, not the diagonal,
 * so a blob keeps its apparent size when the Fold opens and the aspect ratio changes hard.
 */
@Immutable
data class GridlinkAuroraBlob(
    val color: Color,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
)

@Immutable
data class GridlinkColors(
    /** Flat background fill. In [GridlinkMode.DAY] this is only a fallback; see [gradient]. */
    val background: Color,
    /**
     * Background gradient, drawn top-left to bottom-right. Non-null in Day only — Night is a flat
     * near-black and OLED is true black by definition.
     */
    val gradient: List<Color>?,
    /**
     * Faint radial glow placed behind THE PRIMARY ELEMENT ONLY, never as a page-wide wash.
     * Night only; null elsewhere.
     *
     * ⚠️ Derived, not quoted: the brief specifies "faint blue-violet radial glow" without a hex,
     * so this value is a judgement call and is the one colour here that should be checked on a
     * real panel before it is treated as final.
     */
    val glow: Color?,
    /**
     * Stacked radial blobs painted over [background], the app's strongest identity marker.
     *
     * 🔴 This is the one place violet and magenta are allowed. Purple is a hard no as a UI ACCENT
     * across every Gridlink surface, and at the same time the aurora BACKDROP is a thing Tate
     * specifically wants; both are true, and the line between them is that nothing here may ever
     * touch an icon, a fill, a border or an active state.
     *
     * Empty in OLED, where the entire premise is that pixels stay off.
     */
    val aurora: List<GridlinkAuroraBlob>,
    /** Translucent panel fill (header, nav pill, sheets). Alpha is baked in. */
    val surface: Color,
    /**
     * Fill of the panel the dense list scrolls on.
     *
     * Separate from [surface] because it has the opposite job. [surface] belongs to small floating
     * chrome that has to stay readable against anything under it, so it is nearly opaque. This one
     * covers most of the screen, and at that size the same alpha erases the [aurora] completely —
     * which throws away the app's identity to protect text that was already legible. It is
     * deliberately thinner so the backdrop bleeds through at the panel's edges.
     */
    val listSurface: Color,
    /** Hairline border that defines a surface. In OLED this is the ONLY thing defining it. */
    val surfaceBorder: Color,
    /** Raised panel fill, for sheets and dragged rows. */
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    /**
     * What goes ON TOP of an [accent] fill: the send glyph, the compose glyph, the tick inside a
     * selected row's disc, the label of a filled nav item.
     *
     * 🔴 A palette value, not a computation. The luminance heuristic in `gridlinkOnAccent` picks
     * white for OLED's `#F97316`, which lands at about 2.8:1 and fails. Every filled accent control
     * reads this instead of deciding for itself, so the one mode that needs a dark glyph gets one
     * and no call site has to know which mode it is in.
     *
     * ⚠️ This is for [accent] fills only. `gridlinkOnAccent` is still the right call for a fill
     * whose colour is not the accent, which today means the swipe tracks.
     */
    val onAccent: Color,
    /**
     * Warm secondary accent. Currently unreferenced: the brief names it but never spends it, and
     * Day's used to be an alias of [attention] before that went blue. Kept because §1 lists it, and
     * a token the palette defines but nothing uses is cheaper than one a screen needs and lacks.
     */
    val accentWarm: Color,
    val positive: Color,
    val attention: Color,
    /**
     * Stage one of a two-stage destructive gesture: §6a's amber mark-unread track, before the swipe
     * crosses 60% and turns red.
     *
     * 🔴 Not [attention], although the brief calls both of them amber. Day's attention went blue
     * when it became the unread signal, and reusing it here painted the mark-unread track royal
     * blue on a blue gradient beside a blue compose button — a caution state that read as another
     * piece of chrome. The two are different jobs that happened to share a colour in two palettes
     * out of three: attention says "there is something here", caution says "keep going and this
     * gets worse". Amber in all three, because the escalation to red is the whole point and it only
     * works from a warm start.
     */
    val caution: Color,
    val destructive: Color,
    /**
     * The halo behind an action surface: the compose button, and the nav pill at a lower alpha.
     *
     * ⚠️ Split out from [accent], which is what it used to be derived from. That worked in Night,
     * where a blue glow on near-black is the whole look, and was invisible in Day, where a blue
     * glow sits on a blue gradient and does precisely nothing. The colour of the light and the
     * colour of the thing emitting it are two different decisions and now say so.
     *
     * 🔴 Null in OLED. No glows, ever, and that is not a tuning question.
     */
    val actionGlow: Color?,
    /**
     * Fill of a SELECTED row.
     *
     * ⚠️ The one background fill allowed in the dense list. §4 forbids a fill for unread, and that
     * ban is what this borrows its force from: because nothing else in the list is ever filled, a
     * fill can only mean "selected" and needs no second cue to be unambiguous.
     *
     * 🔴 In OLED this is a real compromise. That mode's rule is definition by hairline, never by a
     * lighter fill, and a fill is lit pixels. The alternative was an outline in OLED and a fill
     * elsewhere, which breaks the harder rule that the modes differ ONLY in colour. Kept as a fill,
     * kept faint, and it is only on screen while a selection is active.
     */
    val selection: Color,
    /** 1px row separator in the dense list. The list is separated by hairlines, never by gaps. */
    val divider: Color,
    /**
     * What everything behind a modal, sheet or dialog is covered with.
     *
     * 🔴 Not one flat black in all three modes. Translucent black over Day's blue gradient and over
     * Night's near-black are two different problems, and over OLED's true black it does almost
     * nothing at all, so that mode needs a heavier one to separate the sheet from the page at all.
     */
    val scrim: Color,
    /** True where a drop shadow is legitimate. 🔴 False in OLED: no glows, no shadows, ever. */
    val usesShadows: Boolean,
)

/** Day: the dashboard's cyan-to-blue gradient with translucent white glass over it. */
val GridlinkDayColors = GridlinkColors(
    background = Color(0xFF2F6FE0),
    gradient = listOf(Color(0xFF4DD5F0), Color(0xFF2F6FE0)),
    glow = null,
    // Day's identity is the gradient itself. Blobs on top of it would only muddy two blues.
    aurora = emptyList(),
    surface = Color.White.copy(alpha = 0.55f),
    // Day's is the one that may NOT be thinned. Body text here is near-black on a mid-blue
    // gradient, and 55% white is already the minimum that carries it.
    listSurface = Color.White.copy(alpha = 0.55f),
    surfaceBorder = Color.White.copy(alpha = 0.70f),
    surfaceRaised = Color.White.copy(alpha = 0.72f),
    textPrimary = Color(0xFF0A0F1A),
    textSecondary = Color(0xFF3A4A5F),
    accent = Color(0xFF1B7FE8),
    // White on this azure is about 4.0:1, which carries a glyph comfortably.
    onAccent = Color(0xFFFFFFFF),
    accentWarm = Color(0xFFD97706),
    positive = Color(0xFF16A34A),
    // ⚠️ Blue, not the brief's amber, and Day is the only mode that departs.
    //
    // §1's grammar gives unread to amber in all three palettes. In Night and OLED that is right:
    // both are dark, warm amber against them is the only warm thing on screen and it reads as a
    // flag. Day is a cyan-to-blue gradient under white glass, and an amber dot on it does not read
    // as a flag, it reads as a colour from a different app. So Day gets a blue.
    //
    // 🔴 It has to be a different blue from [accent], or unread starts looking tappable. This is
    // deliberately deeper and more saturated: a royal blue against the accent's lighter azure, so
    // the two are separated by value and not only by hue. If a dot ever looks like a button, this
    // is the number that went wrong.
    attention = Color(0xFF0B3FD1),
    // Amber-600, not the lighter ambers the dark palettes use: this one has to hold a white icon
    // and sit on a pale blue panel without glowing.
    caution = Color(0xFFD97706),
    destructive = Color(0xFFDC2626),
    // A near-white bloom, because the light has to be brighter than what it sits on and Day's
    // background is already mid-blue. This is the "slight glow" behind the compose button.
    actionGlow = Color(0xFFEAF6FF),
    // Heavier than the dark modes: this fill sits on white glass, where a thin accent wash barely
    // registers at all.
    selection = Color(0xFF1B7FE8).copy(alpha = 0.22f),
    divider = Color(0xFF0A0F1A).copy(alpha = 0.12f),
    // The background blue at half strength rather than black: Day's page is a saturated gradient,
    // and dimming it toward grey reads as the screen having gone wrong rather than gone behind
    // something.
    scrim = Color(0xFF2F6FE0).copy(alpha = 0.50f),
    usesShadows = true,
)

/** Night: near-black blue with a single soft glow behind the primary element. */
val GridlinkNightColors = GridlinkColors(
    background = Color(0xFF050A14),
    gradient = null,
    glow = Color(0xFF4C5BD4).copy(alpha = 0.18f),
    // Concentrated at the top and bottom edges on purpose. It satisfies the aurora look and the
    // brief's rule that colour lives in the header and behind the nav bar, and it leaves the middle
    // of the screen, where the actual reading happens, calm and nearly black.
    aurora = listOf(
        GridlinkAuroraBlob(Color(0xFF3B82F6).copy(alpha = 0.34f), 0.08f, 0.00f, 1.15f),
        GridlinkAuroraBlob(Color(0xFF7C3AED).copy(alpha = 0.26f), 0.98f, 0.04f, 0.85f),
        GridlinkAuroraBlob(Color(0xFFDB2777).copy(alpha = 0.16f), 0.55f, -0.06f, 0.65f),
        GridlinkAuroraBlob(Color(0xFF2563EB).copy(alpha = 0.22f), 0.50f, 1.04f, 0.95f),
    ),
    surface = Color(0xFF0D1524).copy(alpha = 0.85f),
    // 66%, not 85%. White text on #0D1524 has enormous headroom, so the extra 19% was buying no
    // legibility and costing the whole aurora.
    listSurface = Color(0xFF0D1524).copy(alpha = 0.66f),
    surfaceBorder = Color(0xFF5A78B4).copy(alpha = 0.18f),
    surfaceRaised = Color(0xFF141E31),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF8CA0BC),
    accent = Color(0xFF3B82F6),
    onAccent = Color(0xFFFFFFFF),
    accentWarm = Color(0xFFF6B87C),
    positive = Color(0xFF34D399),
    attention = Color(0xFFFBBF24),
    caution = Color(0xFFFBBF24),
    destructive = Color(0xFFDC2626),
    // The accent itself. On near-black a blue surface glowing blue is exactly the intent.
    actionGlow = Color(0xFF3B82F6),
    selection = Color(0xFF3B82F6).copy(alpha = 0.18f),
    divider = Color.White.copy(alpha = 0.12f),
    // Black over near-black is nearly a no-op, so this leans on the background hue and a heavier
    // alpha to push the page down and away from the sheet.
    scrim = Color(0xFF050A14).copy(alpha = 0.62f),
    usesShadows = true,
)

/**
 * OLED: earns its name. Surfaces are literally `#000000` so the pixels stay off, and definition
 * comes from hairline borders and text colour — 🔴 never from a lighter fill. Interactive shifts
 * from blue to orange here, which is the one place the hue grammar changes family.
 */
val GridlinkOledColors = GridlinkColors(
    background = Color(0xFF000000),
    gradient = null,
    glow = null,
    // 🔴 Never. An aurora is lit pixels, which is the exact thing this mode exists to avoid.
    aurora = emptyList(),
    surface = Color(0xFF000000),
    // 🔴 Opaque true black, and there is nothing behind it to let through anyway.
    listSurface = Color(0xFF000000),
    surfaceBorder = Color(0xFFF97316).copy(alpha = 0.22f),
    surfaceRaised = Color(0xFF0A0604),
    textPrimary = Color(0xFFE9A87F),
    textSecondary = Color(0xFF9C8574),
    accent = Color(0xFFF97316),
    // 🔴 The reason this token exists. White on #F97316 is about 2.8:1 and fails outright; this
    // near-black is about 6.3:1. It is warmed rather than pure #000 so a glyph sitting on the accent
    // does not look like a hole punched through it.
    onAccent = Color(0xFF1A0C02),
    accentWarm = Color(0xFFFB923C),
    positive = Color(0xFF34D399),
    attention = Color(0xFFFB923C),
    caution = Color(0xFFFB923C),
    destructive = Color(0xFFB91C1C),
    actionGlow = null,
    // 🔴 The faintest of the three. See the field's KDoc: this is the one place OLED lights pixels
    // it would rather leave off, so it gets the minimum that still reads as a fill.
    selection = Color(0xFFF97316).copy(alpha = 0.14f),
    // Derived: the brief gives a 12% hairline for the list but no hue for it in OLED. Tinting it
    // with the accent keeps the mode's "definition by hairline" logic consistent.
    divider = Color(0xFFF97316).copy(alpha = 0.12f),
    // 🔴 The heaviest of the three, and pure black. There is no hue to lean on here and nothing to
    // dim, so separation has to come from covering the page's own hairlines and text almost
    // completely.
    scrim = Color(0xFF000000).copy(alpha = 0.72f),
    usesShadows = false,
)

/**
 * Sender-identity bars: the 3dp leading edge that replaces avatars.
 *
 * ⚠️ Derived. The brief says "coloured by sender domain" and gives no values, and the constraint is
 * tighter than it first looks: green, amber and red are all spoken for by the semantic grammar
 * (archive / unread / delete), so an identity bar may not borrow any of them or it starts making a
 * claim about the message. That leaves the cool family in Day and Night, and in OLED it leaves only
 * lightness, since the whole mode is one hue by design.
 *
 * 🔴 No violet or purple anywhere in these ramps. That is a standing preference across the
 * dashboard, and it happens to be the obvious hue a generic palette would reach for here.
 *
 * Six entries is deliberate: this inbox is dominated by a handful of repeat senders, so the ramp
 * only has to keep those few apart, and a longer ramp would put near-identical blues side by side.
 */
object GridlinkSenderBars {
    val day = listOf(
        Color(0xFF0EA5E9),
        Color(0xFF1B7FE8),
        Color(0xFF1D4ED8),
        Color(0xFF0891B2),
        Color(0xFF475569),
        Color(0xFF94A3B8),
    )

    val night = listOf(
        Color(0xFF38BDF8),
        Color(0xFF3B82F6),
        Color(0xFF1D4ED8),
        Color(0xFF7DD3FC),
        Color(0xFF64748B),
        Color(0xFF0E7490),
    )

    /**
     * OLED: same six slots, separated by lightness within the amber family rather than by hue.
     *
     * 🔴 Every entry is deliberately duller and darker than the accent `#F97316` and the attention
     * amber `#FB923C`. A first pass used saturated oranges here and the result was a column of
     * bars that all read as alerts, drowning out the one unread dot that was supposed to be the
     * loudest thing on screen. Identity is allowed hue, not volume.
     */
    val oled = listOf(
        Color(0xFFC98B5E),
        Color(0xFFB45309),
        Color(0xFF9A5B2C),
        Color(0xFF7C2D12),
        Color(0xFF92400E),
        Color(0xFF5C3317),
    )

    fun forMode(mode: GridlinkMode): List<Color> = when (mode) {
        GridlinkMode.DAY -> day
        GridlinkMode.NIGHT -> night
        GridlinkMode.OLED -> oled
    }
}

/**
 * Maps a sender domain to a stable identity bar colour.
 *
 * Keyed on the DOMAIN, not the address, so every alert from `tallyman.example` shares one bar no
 * matter which no-reply mailbox emitted it — that shared bar is the whole point, since it is what
 * makes a run of the same robot readable as one block while scrolling.
 */
fun gridlinkSenderBarColor(mode: GridlinkMode, domain: String): Color {
    val ramp = GridlinkSenderBars.forMode(mode)
    var hash = 0
    for (ch in domain.lowercase()) hash = (hash * 31 + ch.code) and 0x7FFFFFFF
    return ramp[hash % ramp.size]
}

fun gridlinkColorsFor(mode: GridlinkMode): GridlinkColors = when (mode) {
    GridlinkMode.DAY -> GridlinkDayColors
    GridlinkMode.NIGHT -> GridlinkNightColors
    GridlinkMode.OLED -> GridlinkOledColors
}

// ---------------------------------------------------------------------------------------------
// Spacing, geometry, radii — identical in all three modes
// ---------------------------------------------------------------------------------------------

/**
 * The whole spacing scale. Nothing outside this ladder is allowed.
 *
 * The named aliases below encode the brief's central tension: **chrome is spacious, the list is
 * dense**. Header/nav/sheets use [chrome]; message rows use [rowHorizontal] / [rowVertical] and
 * are separated by a hairline rather than by gaps or cards.
 */
object GridlinkSpacing {
    val s4 = 4.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s20 = 20.dp
    val s28 = 28.dp
    val s40 = 40.dp

    /** Generous padding for spacious chrome: header, nav pill, sheets, dialogs, empty states. */
    val chrome = s20
    val rowHorizontal = s16
    val rowVertical = s12

    /** Bundled automated senders indent by this, with a continuous rule at the indent. */
    val bundleIndent = s12

    /** Folder tree indents by this PER LEVEL, with a continuous rule at each indent. */
    val folderIndentPerLevel = s16
}

object GridlinkRadii {
    /** Cards, sheets, dialogs. */
    val card = 28.dp

    /** Pills (nav bar, mode toggle, selection toolbar) are fully rounded; this is a large value
     *  used with a rounded-corner shape rather than a "percent" so the morph between the nav pill
     *  and the selection toolbar can hold the corner radius constant. */
    val pill = 999.dp
}

object GridlinkDimens {
    /** 🔴 Dense by design: 64dp targets ~13 visible rows on a folded Fold screen. */
    val messageRowHeight = 64.dp

    /** Leading vertical bar carrying sender identity, coloured by sender domain. Replaces avatars. */
    val senderBarWidth = 3.dp

    /** Unread marker, sitting where the timestamp's leading space would be. */
    val unreadDot = 6.dp

    /**
     * The selection circle that appears at the leading edge of every row once a selection starts.
     *
     * A control, not a marker, so it is sized to be hit rather than to be seen: 22dp of visible
     * circle inside a [selectionGutter]-wide strip that is entirely tappable. The unread dot stays
     * 6dp because nothing ever taps it.
     */
    val selectionCircle = 22.dp

    /**
     * How far the rows slide right when a selection opens, and therefore the width of the strip the
     * circles live in.
     *
     * 🔴 The rows slide rather than the circles overlaying them. An overlay would sit on top of the
     * sender bar, which is the one thing on the row that never moves, and 44dp is what it takes to
     * clear a 22dp circle with a real touch margin either side.
     */
    val selectionGutter = 44.dp

    /** Stroke of an unselected circle. Thin enough to read as an empty slot, not as a filled chip. */
    val selectionRing = 1.5.dp

    /**
     * How much of the list's bottom edge fades to nothing.
     *
     * The list stops above the floating controls rather than scrolling under them, and this fade is
     * what keeps that boundary from reading as a cut. Deep enough to catch a whole row mid-scroll.
     */
    val listFade = 40.dp

    /**
     * The floating compose button.
     *
     * ⚠️ §9 lists "no FAB" as an anti-requirement and Tate overrode it directly. Sized to match
     * [GRIDLINK_PILL_HEIGHT] so the button and the nav pill share one line and one baseline rather
     * than stacking into two rows of floating chrome.
     */
    val composeButton = 64.dp

    /** Row separator thickness. Opacity lives in [GridlinkColors.divider]. */
    val hairline = 1.dp

    /** Unfolded two-pane layout: list pane is fixed, thread fills the remainder. */
    val listPaneWidth = 380.dp

    /** Elevation of a folder row while it is being dragged to a new parent. */
    val dragElevation = 4.dp

    /** Outline drawn on a valid drop target during a reparent drag. */
    val dropTargetOutline = 2.dp

    /**
     * A one-line row: a folder in the tree, a recipient suggestion, a preset in the schedule sheet.
     *
     * Shorter than [messageRowHeight] because it carries one line where a message carries two, and
     * a one-line row padded out to 64dp reads as a list that has been stretched. Still a 52dp touch
     * target, comfortably above the 48dp floor, which matters more here than on a message row
     * because §6d's long-press has to be distinguishable from a tap that opens the folder.
     */
    val compactRow = 52.dp

    /** @see compactRow. Named separately because the folder tree was the first thing to want it. */
    val folderRowHeight = compactRow

    /**
     * A control that lives in a header rather than on the nav-pill baseline: the search pill, and
     * the composer's send circle while the keyboard is up.
     *
     * 🔴 Smaller than [composeButton] on purpose, and the composer relies on the difference. With
     * the keyboard down, send grows to the full 64dp and lands exactly where compose was, so the
     * gesture that opened the composer is the gesture that sends from it.
     */
    val headerControl = 44.dp

    /**
     * A ring drawn around a control to show it is the one being acted on: the press ring that holds
     * on send while the schedule sheet is open.
     *
     * Heavier than [selectionRing], which marks a slot that could be chosen. This marks the thing
     * that already was, over a scrim, so it has to survive being the only lit edge on screen.
     */
    val ringStroke = 3.dp

    /**
     * The tap target that removes one thing from a composed message: an attachment, a recipient.
     *
     * Under the 48dp floor deliberately. These sit inside a row that is itself a control, and a
     * full-size target here would swallow taps meant for the row. The row is the safe action and
     * the small glyph is the destructive one, so the sizes say which is which.
     */
    val inlineDismiss = 28.dp

    /** The vertical rule marking one level of folder nesting. Same weight as a row separator. */
    val treeRule = 1.dp

    /**
     * One day cell in the month grid.
     *
     * Six rows of this plus the weekday header is the worst case a month can be, and that has to
     * fit the panel on a folded Fold without scrolling — a month view you have to scroll is a
     * three-week view with extra steps.
     */
    val calendarDayCell = 44.dp

    /** The dot under a day number in the month grid, one per event up to three. */
    val calendarEventDot = 5.dp

    /** Height of one hour in the day and week time grids. */
    val calendarHourHeight = 56.dp

    /** Width of the hour labels running down the leading edge of a time grid. */
    val calendarGutterWidth = 44.dp
}

/**
 * Swipe-action thresholds, as a fraction of row width.
 *
 * Left swipe escalates: amber "mark unread" up to [deleteThreshold], then the track colour and
 * icon swap to red "delete" in a single spring paired with a haptic tick, so the escalation is
 * felt as well as seen. The icon scales across [iconScaleMin]..[iconScaleMax] as the threshold is
 * crossed. Releasing before a threshold springs the row back.
 */
object GridlinkSwipe {
    const val archiveThreshold = 0.25f
    const val markUnreadThreshold = 0.25f
    const val deleteThreshold = 0.60f
    const val iconScaleMin = 0.8f
    const val iconScaleMax = 1.0f

    /**
     * A flick faster than this commits without reaching the distance threshold, provided it got past
     * [flingMinFraction].
     *
     * 🔴 Distance alone is the wrong test and is what makes a swipe feel like it ignored you. A
     * quick confident flick is the most common way people archive mail, and it is short by nature:
     * the finger is already leaving the glass at 15% of the row. Judged on distance that reads as
     * the app refusing the gesture, and the fix people reach for is to swipe again harder.
     *
     * ⚠️ Deliberately NOT applied to delete. Escalating to destructive stays purely a distance
     * decision, so no amount of speed can turn a hurried flick into a deleted message.
     */
    const val flingVelocityDp = 380f

    /** Below this the gesture is a twitch, and no velocity should promote it into an action. */
    const val flingMinFraction = 0.12f
}

/** How long the mail sits locally before it actually leaves, with a draining ring on the undo. */
const val GRIDLINK_UNDO_SEND_SECONDS = 10

// ---------------------------------------------------------------------------------------------
// Type
// ---------------------------------------------------------------------------------------------

/**
 * **Outfit** — the geometric sans the brief calls for, with the high x-height and heavy display
 * weights that match the dashboard. SIL Open Font License 1.1; see `docs/licenses/OFL-Outfit.txt`.
 *
 * 🔴 Bundled as static TTFs in `res/font`, NOT pulled from Google's downloadable-fonts provider:
 * that provider is a Play Services component and this app ships with no Google dependencies at all.
 * Five weights only, because every weight is ~75 KB of APK and the scale below uses exactly these.
 */
val GridlinkFontFamily: FontFamily = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
    Font(R.font.outfit_extrabold, FontWeight.ExtraBold),
)

/**
 * Type scale.
 *
 * 🔴 Tabular numerals on timestamps and counts are non-negotiable — proportional digits make a
 * scrolling list of times visibly jitter. That is what `fontFeatureSettings = "tnum"` buys, and it
 * must not be dropped when these styles are copied.
 *
 * Tracking is expressed by the brief as a percentage; it is resolved here against the style's own
 * size (32sp at -1% = -0.32sp, 13sp at +6% = +0.78sp).
 */
object GridlinkType {
    val screenTitle = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 32.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.32).sp,
    )

    /** Uppercase at the call site — the caps are content, not a font feature. */
    val sectionLabel = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.78.sp,
    )

    // 🔴 The explicit 18sp line height on the two row styles is what makes 64dp rows possible.
    // Compose's default leading (~1.4x) would put two 15sp lines at ~42dp, which with the row's
    // 12dp top and bottom padding overflows a 64dp row and quietly stretches the whole list.
    // 18 + 18 + 12 + 12 = 60dp, leaving 4dp of slack.

    val senderName = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 18.sp,
    )

    val subject = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
    )

    /** Snippet and secondary metadata. Note the brief OMITS snippets from list rows. */
    val metadata = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
    )

    val timestamp = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
        lineHeight = 18.sp,
    )

    val badge = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    )

    /** Icon-over-label in the selection toolbar. */
    val toolbarLabel = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )

    /**
     * Running prose that is meant to be read rather than scanned: the message body in the composer
     * and in the thread view.
     *
     * 🔴 One step LARGER than the list rows, not the same size. It was 15sp/22sp to match a row, on
     * the theory that one type size everywhere is tidier. Reading an actual message on the device
     * killed that: a list row is one line you glance at, and a body is four hundred words you track
     * across and down, which is a different job for the eye and wants a different size.
     *
     * 16sp with a 27sp line is a 1.69 ratio. Prose wants somewhere between 1.5 and 1.75, and the old
     * 22sp on 15sp was 1.47, which is the bottom of the range and reads as cramped the moment a
     * paragraph runs past three lines. Do not chase this any higher: past about 1.8 the lines stop
     * belonging to the same paragraph and the eye starts losing its place on the way back to the
     * left margin, which is the exact problem the extra leading was meant to fix.
     */
    val body = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 27.sp,
    )

    /**
     * A resolved recipient, sitting in the composer's TO field.
     *
     * 🔴 Medium, not the [senderName] SemiBold it sits closest to. A chip already carries a fill and
     * a shape; adding weight on top of that makes a row of two recipients louder than the subject
     * line underneath it.
     */
    val chip = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    )

    /**
     * A thread's subject where it is the heading of a screen rather than a line in a list.
     *
     * Fills the gap between [subject] at 15sp and [screenTitle] at 32sp. A subject promoted straight
     * to 32sp wraps to three lines and eats the top third of the panel.
     */
    val threadTitle = TextStyle(
        fontFamily = GridlinkFontFamily,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
    )
}

// ---------------------------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------------------------

/**
 * Springs only.
 *
 * 🔴 The brief specifies motion as stiffness and damping, NOT milliseconds, and upstream's
 * "everything ≤ 250ms, FastOutSlowIn" rule does not apply to Gridlink screens. Target is 120Hz:
 * nothing may block a frame.
 *
 * [rowCollapse] is critically damped (1.0) on purpose — a destructive action must not bounce.
 *
 * Reduced motion: when the system setting is on, drop to opacity-only transitions rather than
 * retuning these values.
 */
object GridlinkMotion {
    fun <T> standard(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 380f)

    /** Swipe release and snap-back — looser and quicker, so it tracks the finger. */
    fun <T> swipeRelease(): SpringSpec<T> = spring(dampingRatio = 0.75f, stiffness = 500f)

    /** Longest a row may take to clear the edge, i.e. the duration for a full row-width of travel. */
    private const val FLY_OFF_FULL_MS = 190

    /** Floor on that duration, so a row released at 90% still reads as a movement and not a cut. */
    private const val FLY_OFF_MIN_MS = 90

    /**
     * The committed row leaving the screen sideways, over [remainingFraction] of a row width.
     *
     * 🔴 A tween, and it used to be a critically-damped spring at stiffness 2000. The spring was
     * wrong for this in a way that only became visible once the exit was sequenced properly.
     *
     * A spring approaches its target asymptotically, so it does not so much end as become too small
     * to see. That was survivable while `onAction` fired at the instant of release, because the
     * collapse ran *concurrently* and hid the tail. Now that the row flies off first and only then
     * hands over (see `GridlinkSwipeRow.settle`, and the bug that forced it), the tail is exactly
     * what the eye is waiting on: measured on device, the row's visible travel finished and then a
     * full-bleed green slab sat perfectly still for ~170ms while the spring crawled its last two
     * pixels, before the collapse could start. A coloured rectangle sitting still is read as the app
     * having stalled. Tuning stiffness cannot fix it, only move it; the shape is the problem.
     *
     * A tween ends when it says it will. The handover therefore lands on the frame the row clears
     * the edge, with no dead time on either side of it, which is the entire point.
     *
     * 🔴 Duration scales with distance left to travel rather than being fixed. A fixed duration
     * makes the row's speed depend on where the finger let go: a row released just past the
     * threshold sprints, an identical row released at 90% crawls the last sliver. Scaling keeps the
     * apparent speed constant, so every archive leaves at the same rate regardless of how far the
     * user chose to drag it. [FLY_OFF_MIN_MS] stops the short ones becoming an instant cut.
     *
     * Float rather than generic: only the swipe offset uses it.
     */
    fun swipeFlyOff(remainingFraction: Float): AnimationSpec<Float> =
        tween(
            durationMillis = (remainingFraction * FLY_OFF_FULL_MS).toInt()
                .coerceIn(FLY_OFF_MIN_MS, FLY_OFF_FULL_MS),
            easing = FastOutSlowInEasing,
        )

    /** Nav pill morphing into the selection toolbar: slow and heavily damped, so the shape holds. */
    fun <T> toolbarMorph(): SpringSpec<T> = spring(dampingRatio = 0.90f, stiffness = 300f)

    /**
     * Row height collapsing to zero after an action completes. No overshoot.
     *
     * 🔴 Stiffness was 400, which is Compose's own default for [shrinkVertically] and far too soft
     * for this. Measured: a 158px row took 456ms to reach zero, and spent the last 200ms of that
     * crawling the final 13px. The gap closing long after the row has gone is the other half of
     * what Tate reported as sticking. 1200 puts it at roughly 190ms, which is quick enough to
     * feel like a consequence of the swipe rather than an afterthought.
     */
    fun <T> rowCollapse(): SpringSpec<T> = spring(dampingRatio = 1.0f, stiffness = 1200f)

    /**
     * A row opening into the list: new mail arriving, or the demo recycle putting one back.
     *
     * Softer than [rowCollapse] on purpose. A dismissal should be over quickly because the user has
     * already decided; an arrival is the list telling you something you did not ask for, and at
     * 1200 it snapped open fast enough to read as a glitch rather than as mail landing. Still
     * critically damped: a row that overshoots its own height bulges the text inside it.
     */
    fun <T> rowArrive(): SpringSpec<T> = spring(dampingRatio = 1.0f, stiffness = 500f)

    /**
     * A whole group folding shut — currently the automated bundle.
     *
     * Deliberately its own token rather than borrowing [rowCollapse], even though the numbers
     * started out the same. Folding a bundle is a disclosure the user asked for and can undo by
     * tapping again; a swiped row leaving is a consequence they want over with. Tuning one to feel
     * right must not drag the other along behind it, which is exactly what happened when they
     * shared a spec.
     *
     * Critically damped for the same reason [rowCollapse] is: six rows bouncing as they close reads
     * as the list being unsure.
     */
    fun <T> groupCollapse(): SpringSpec<T> = spring(dampingRatio = 1.0f, stiffness = 400f)
}

/**
 * Whether the user has asked the system to stop animating things.
 *
 * §8's last line requires this and, until the undo bar, nothing in the fork read it. Android has no
 * "prefer reduced motion" flag of its own: the setting a user actually reaches for is Developer
 * options' animator duration scale, or the accessibility shortcut that zeroes it, and both land on
 * [Settings.Global.ANIMATOR_DURATION_SCALE]. Zero means off.
 *
 * ## 🔴 What this is NOT for
 * Compose already honours the same setting on its own, through `MotionDurationScale` in the
 * animation coroutine context: with animations off, every `animateTo` completes on the frame it
 * starts. So this is not needed to *shorten* anything, and reading it to conditionally pick a
 * shorter spring would be doing the platform's job twice.
 *
 * It exists for the opposite case: places where the automatic behaviour is wrong. An animation that
 * is decoration should collapse to an opacity change, which is what §8 asks for and what the
 * automatic collapse already gives. An animation that is *information* must keep running, and there
 * the automatic collapse is a bug — see the countdown ring in `GridlinkUndoBar`, which is a clock
 * face and would otherwise jump straight to empty and take the undo window with it.
 *
 * ⚠️ Read once and remembered. The setting can change while the app is alive and this will not
 * notice until the composition is recreated. Observing it means a ContentObserver and a lifecycle,
 * which is real machinery for a setting people change roughly never, and a stale read here costs
 * one animation rather than any correctness.
 */
@Composable
fun gridlinkReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

// ---------------------------------------------------------------------------------------------
// Plumbing
// ---------------------------------------------------------------------------------------------

/**
 * Current palette, read as `GridlinkTheme.colors` from any composable.
 *
 * Defaults to Night because the brief designates Night as the primary mode — every screen is
 * designed there first, and Day/OLED are palette applications of it.
 */
val LocalGridlinkColors = staticCompositionLocalOf { GridlinkNightColors }

val LocalGridlinkMode = staticCompositionLocalOf { GridlinkMode.NIGHT }

/** Accessor object, so call sites read `GridlinkTheme.colors.attention` rather than the locals. */
object GridlinkTheme {
    val colors: GridlinkColors
        @Composable get() = LocalGridlinkColors.current

    val mode: GridlinkMode
        @Composable get() = LocalGridlinkMode.current
}

/**
 * Provides the Gridlink tokens to a subtree.
 *
 * Deliberately does NOT touch [MaterialTheme][androidx.compose.material3.MaterialTheme]: upstream
 * screens keep rendering with [ArcticColorScheme] / [PelagicColorScheme] while the Gridlink
 * screens are built one at a time. Wiring the two together is the next step, not this file's job.
 */
@Composable
fun ProvideGridlinkTokens(
    mode: GridlinkMode,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGridlinkMode provides mode,
        LocalGridlinkColors provides gridlinkColorsFor(mode),
        content = content,
    )
}
