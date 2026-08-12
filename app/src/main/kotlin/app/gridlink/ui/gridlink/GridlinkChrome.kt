package app.gridlink.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

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
    val radius = maxOf(size.width, size.height) * radiusMultiplier
    val origin = center(size)
    // 🔴 drawCircle, NEVER drawRect. A rect is the size of the element, so the gradient gets cut
    // off wherever the element ends rather than where the light runs out. On the header that put a
    // hard horizontal step across the entire screen at the header's bottom edge, and on the nav
    // pill it hid the halo completely, because the rect was exactly the pill and the pill's own
    // fill painted straight over it. A circle spills past the element's bounds — Compose does not
    // clip a draw modifier unless the node asks it to — so the light fades out on its own.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = origin,
            radius = radius,
        ),
        radius = radius,
        center = origin,
    )
}

/**
 * Screen title, drawn inline on the chrome row beside the hamburger.
 *
 * This used to be a stacked block of its own, filling a whole row below the chrome row, until
 * Brandon collapsed the top of the screen into one line: "leave the hamburger menu where it is, put
 * INBOX on the same horizontal line, and put the search icon on the same line. then extend both side
 * panes upward." That move is what this still is: the title RIDES the chrome row, it owns no
 * horizontal padding and no right-hand slot, and the search pill sits in the row's `trailing` seat
 * rather than in here.
 *
 * 🔴 What came back is only the internal stack. Brandon, 2026-08-10: "put 1 unread underneath
 * inbox." Title and metadata are a Column again, not two things sharing a baseline, so the count
 * reads as a property OF the title rather than as a second item on the chrome line. It costs the
 * metadata line's height, ~16dp, which comes off the panels below — the whole chrome row grows,
 * because it centres its children and this is now the tallest one. That is the trade he asked for
 * and it is the only cost; nothing else on the row moved.
 *
 * ⚠️ The title no longer takes `weight(1f, fill = false)`. That was RowScope, and it is not needed:
 * the box this rides in already carries the row's weight, so a long calendar title still ellipsizes
 * inside its own seat and still cannot shove the sync chip or the search pill off the pad line.
 *
 * 🔴 The metadata beside the title is a plain unread count and nothing else, absent when [unread]
 * is zero. It used to print a derived split, "3 need you · 14 reports", on the theory that the
 * app's job is to separate the messages that want a human from the ones that do not. That theory
 * may be right but the line was not: neither number matched anything the user could point at, and
 * the two halves invited a comparison that means nothing. Zero unread prints nothing at all rather
 * than "0 unread". An empty line is the reward.
 *
 * [subline] overrides the unread count for screens that are not a mailbox: the calendar puts its
 * date range there, the folder tree its mailbox total. It is drawn in secondary text rather than in
 * [GridlinkColors.attention][app.gridlink.ui.theme.GridlinkColors.attention], because that colour
 * means "unread" everywhere else in the app and a date wearing it would be making a claim.
 */
@Composable
fun GridlinkHeader(
    title: String,
    unread: Int,
    modifier: Modifier = Modifier,
    selectedCount: Int = 0,
    subline: String? = null,
) {
    val colors = GridlinkTheme.colors
    val selecting = selectedCount > 0
    Column(
        // Tighter than the 0.9 default now that the circle spills past the header instead of
        // being cropped to it: the same multiplier would wash half the screen and swallow the
        // aurora, which is already doing the broad work behind this.
        modifier = modifier.gridlinkGlow(colors.glow, radiusMultiplier = 0.55f) {
            Offset(it.width * 0.28f, it.height * 0.5f)
        },
    ) {
        Text(
            // The count replaces the screen title rather than sitting beside it. §6b puts the
            // selection ACTIONS in the toolbar; this is only the readout, and it belongs where
            // the eye already is.
            text = if (selecting) "$selectedCount selected" else title,
            style = GridlinkType.screenTitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The whole metadata Text is conditional, not just its string: an empty Text would still
        // cost its 12dp lead-in and hold a phantom seat beside the title.
        val metaText: String?
        val metaColor: Color
        if (selecting) {
            metaText = "Tap to add or remove"
            metaColor = colors.textSecondary
        } else if (subline != null) {
            metaText = subline
            metaColor = colors.textSecondary
        } else if (unread > 0) {
            metaText = "$unread unread"
            // Same colour as the dots down the list. The header count and the row markers are the
            // same fact stated twice, so they have to match or the eye reads them as two different
            // signals. Amber in the dark modes, blue in Day.
            metaColor = colors.attention
        } else {
            metaText = null
            metaColor = colors.textSecondary
        }
        if (metaText != null) {
            Text(
                text = metaText,
                style = GridlinkType.metadata,
                color = metaColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Collapsed this is a circle, so the height is also the diameter. */
private val SEARCH_PILL_HEIGHT = 44.dp

/** How wide the input gets when the pill opens. Fixed, so the header's layout never jumps. */
private val SEARCH_FIELD_WIDTH = 168.dp

/** Shared by both glyphs in the pill, the magnifier and the close, so they cannot drift apart. */
private val SEARCH_GLYPH_SIZE = 20.dp

// SEARCH_PILL_FOOTPRINT used to live here: the expanded pill's whole width, summed from the layout
// constants so the scaffold could reserve a fixed right-aligned box for it. That reservation is
// unnecessary now that the pill sits LAST in the chrome row after a weight(1f) header: Row layout
// measures weighted children with whatever is left after the fixed ones, so when the pill opens it
// takes its width out of the header's slack and its right edge never moves off the pad line. The
// growth is leftward for free, with no reserved footprint and no animated offset.

/**
 * Search, as a momentary control in the header instead of a permanent seat in the nav bar.
 *
 * ## Why it moved off the toolbar
 * The nav bar names *places*: Inbox, Folders, Calendar, Contacts. Search is not a place, it is
 * something you do to one, and giving a verb a fifth of a bar of nouns spent permanent space on a
 * control wanted for about four seconds at a time. Up here it costs a 44dp circle when idle and
 * takes the room it needs only while it is being used.
 *
 * ## Collapsed by default, the same trick as the mode pill
 * At rest it is a barely-there glass disc with a half-strength magnifier: findable if you look for
 * it, invisible if you are reading. Tapping expands it to an input and focuses it in the same
 * frame, so the keyboard is up by the time the animation finishes.
 *
 * ⚠️ It does NOT time out the way the mode pill does. The mode pill folds itself away after a few
 * idle seconds because nothing is lost when it does; folding this one away would pull focus out
 * from under the keyboard mid-sentence. It closes when you close it, or when you dismiss an empty
 * field.
 */
@Composable
fun GridlinkSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    /**
     * Told whenever the pill opens or closes, so the rest of the chrome row can get out of its way.
     *
     * The pill owns its own open/closed state and keeps owning it: nothing outside can force it
     * open or shut, which is what stopped the focus and keyboard handling below from having to
     * cope with a parent changing its mind mid-sentence. This is a report, not a control.
     *
     * The screen that cares is the message list, whose header title has to vanish rather than be
     * squeezed: on a folded display the pill takes its width out of the title's slack and "Inbox"
     * ellipsised down to "I…". See [GridlinkMessageListScreen].
     */
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val colors = GridlinkTheme.colors
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    // Reported from an effect rather than from each of the two places that flip the flag, so a
    // state RESTORED as open (rotation, process death, the gallery's deep link) reports itself
    // too. Keyed on the value, so it fires once per change and not once per recomposition.
    LaunchedEffect(expanded) { onExpandedChange(expanded) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(GridlinkRadii.pill)

    // Same three-layer treatment as the mode pill: an opaque underlay that only exists when open
    // (so the header's glow cannot show through a live input), the glass over it, and the hairline.
    val underlay by animateColorAsState(
        targetValue = if (expanded) colors.background else Color.Transparent,
        animationSpec = GridlinkMotion.standard(),
        label = "searchUnderlay",
    )
    val surface by animateColorAsState(
        targetValue = if (expanded) colors.surface else colors.surface.copy(alpha = 0.14f),
        animationSpec = GridlinkMotion.standard(),
        label = "searchSurface",
    )
    val outline by animateColorAsState(
        targetValue = if (expanded) colors.surfaceBorder else colors.surfaceBorder.copy(alpha = 0.35f),
        animationSpec = GridlinkMotion.standard(),
        label = "searchBorder",
    )
    val glyph by animateColorAsState(
        targetValue = if (expanded) colors.textPrimary else colors.textSecondary.copy(alpha = 0.55f),
        animationSpec = GridlinkMotion.standard(),
        label = "searchGlyph",
    )

    fun close() {
        expanded = false
        onQueryChange("")
        keyboard?.hide()
    }

    // Back closes an open search before anything under it moves. Composed inside the list content,
    // so it registers after the scaffold's selection and detail handlers and wins over both: an
    // expanded search is the most recently entered mode on the screen, and back peels modes off
    // innermost-first. The drawer still beats this (it is composed later still), which is right —
    // it is drawn over the top of everything, search included.
    BackHandler(enabled = expanded) { close() }

    Box(
        modifier = modifier
            .height(SEARCH_PILL_HEIGHT)
            .background(underlay, shape)
            .background(surface, shape)
            .border(width = GridlinkDimens.hairline, color = outline, shape = shape)
            .clip(shape)
            // 🔴 Only clickable while collapsed. Left on, this swallows every tap meant for the
            // text field inside it, and the field silently stops taking a caret.
            .then(if (expanded) Modifier else Modifier.clickable { expanded = true }),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                (fadeIn(GridlinkMotion.standard()) togetherWith fadeOut(GridlinkMotion.standard()))
                    .using(SizeTransform(clip = true) { _, _ -> GridlinkMotion.standard() })
            },
            label = "searchPill",
        ) { isExpanded ->
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = GridlinkSpacing.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = if (isExpanded) null else "Search mail",
                    tint = glyph,
                    modifier = Modifier.size(SEARCH_GLYPH_SIZE),
                )
                if (isExpanded) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = GridlinkType.metadata.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier
                            .padding(start = GridlinkSpacing.s8)
                            .width(SEARCH_FIELD_WIDTH)
                            .focusRequester(focusRequester),
                        decorationBox = { field ->
                            // The placeholder is drawn behind the field rather than swapped for it,
                            // so the caret does not jump left by a few pixels on the first keypress.
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "Search mail",
                                        style = GridlinkType.metadata,
                                        color = colors.textSecondary,
                                    )
                                }
                                field()
                            }
                        },
                    )
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close search",
                        tint = colors.textSecondary,
                        modifier = Modifier
                            .padding(start = GridlinkSpacing.s8)
                            .size(SEARCH_GLYPH_SIZE)
                            .clickable { close() },
                    )
                }
            }
        }
    }

    // Focus on open, not on every recomposition while open. Requesting focus before the node is
    // attached throws, and AnimatedContent only attaches the expanded branch after this frame, so
    // the request has to be keyed on the transition rather than fired inline.
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        focusRequester.requestFocus()
        // ⚠️ Not redundant. Focus raises the IME on most builds and silently does not on some, and a
        // search pill that opens without a keyboard is a search box you cannot type in until you tap
        // the thing you already tapped.
        keyboard?.show()
    }
}

/** Uppercase timeline heading. The caps are content, not a font feature, so they are applied here. */
@Composable
fun GridlinkSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    /** Slides with the rows when a selection opens, so the label never breaks the text's left edge.
     *  Owned by the screen; see [GridlinkMessageRow]'s note on why nothing animates this locally. */
    gutter: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Text(
        text = text.uppercase(),
        style = GridlinkType.sectionLabel,
        color = GridlinkTheme.colors.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.rowHorizontal + gutter,
                end = GridlinkSpacing.rowHorizontal,
                top = GridlinkSpacing.s20,
                bottom = GridlinkSpacing.s8,
            ),
    )
}

/**
 * The four places the nav pill can take you.
 *
 * ⚠️ Search used to be one of these and is not any more. It was the odd one out: the other three
 * are places the app *keeps* things, and search is something you *do* to them. Parking a verb in a
 * row of nouns cost a quarter of the bar to a control that is only ever wanted for a few seconds at
 * a time. It now lives in the header as [GridlinkSearchPill], which is where a momentary control
 * belongs. Calendar and Contacts took the space.
 *
 * [composeLabel] is here rather than in the screen because the floating button's meaning is entirely
 * a function of where you are: on mail it writes a message, on the other two it adds a thing.
 * Keeping the mapping on the destination means a fifth tab cannot be added without answering the
 * question.
 *
 * ⚠️ There is no `composeIcon` any more. Mail used to draw a pencil here and the other tabs a plus,
 * and Brandon's verdict on that was "having a 'write' button is confusing": one landmark that
 * changes its glyph reads as two different buttons that happen to share a spot. It is a "+"
 * everywhere now, and what the "+" makes is what this label says. The label is not decoration, it is
 * the button's whole content description, so it is the only thing a screen reader gets.
 */
enum class GridlinkDestination(
    val label: String,
    val icon: ImageVector,
    val composeLabel: String,
    /**
     * Whether the pill offers a seat for this destination.
     *
     * 🔴 False on exactly one of them, and it is not a fifth-tab overflow: Brandon asked for the
     * folders themselves to live in the drawer ("move all folders to the hamburger menu to popup on
     * slideout"), and a tab that opens a *list of folders* beside a drawer that opens *the folders*
     * is the two-navigation-systems problem [GridlinkMenuItem] used to warn about, now pointing the
     * other way. So the mailboxes are reached from the drawer and this destination is what the
     * drawer's "Manage folders" row opens: still a real screen, still the only place a mailbox is
     * renamed, created or moved, just not a place the thumb lands on by accident.
     */
    val inPill: Boolean = true,
) {
    INBOX("Inbox", Icons.Outlined.Inbox, "New message"),
    FOLDERS("Folders", Icons.Outlined.FolderOpen, "New message", inPill = false),
    CALENDAR("Calendar", Icons.Outlined.CalendarMonth, "New appointment"),
    CONTACTS("Contacts", Icons.Outlined.PeopleOutline, "New contact"),
}

/**
 * What the selection toolbar can do to the ticked rows.
 *
 * ⚠️ Not the brief's four. §6b specifies Reply, Archive, Delete, Spam, with Reply dimmed to 38%
 * above one selection. Brandon replaced that list directly: "just have it show Archive / Move /
 * Delete / Mark Read". His set has no single-selection member, so the dimmed-Reply state the brief
 * asks for has nothing to apply to and is not built. Bring it back with Reply, not before.
 *
 * [destructive] is on exactly one of these. §1 spends red on delete and on nothing else in the
 * entire app, which is what lets a single red glyph carry the warning without extra size or weight.
 */
enum class GridlinkSelectionAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
) {
    ARCHIVE("Archive", Icons.Outlined.Archive),
    MOVE("Move", Icons.Outlined.DriveFileMove),
    DELETE("Delete", Icons.Outlined.Delete, destructive = true),
    MARK_READ("Mark read", Icons.Outlined.MarkEmailRead),
}

/**
 * The floating navigation pill, and the selection toolbar it becomes.
 *
 * ⚠️ Compose used to be a destination here, because §9 bans a floating action button. Brandon
 * overrode that directly and asked for compose detached and floating, so it now lives in
 * [GridlinkComposeButton] beside this pill.
 *
 * ## Why both states are one composable
 * §6b: "Make it a transformation, not an arrival: the floating navigation pill morphs in place into
 * the action bar, same height, same corner radius, same horizontal inset. Contents cross-fade while
 * the container's shape holds." Two composables swapped by an `if` cannot satisfy that — the first
 * leaves and the second arrives, and no amount of matching dimensions hides the fact that the
 * container blinked. So the container is drawn once, here, and only its *contents* animate. The
 * shape does not hold because both states were carefully given the same numbers; it holds because
 * there is only ever one of it.
 *
 * 🔴 Four is the ceiling on both sides. At this width a fifth item puts "Contacts" under an
 * ellipsis, and an abbreviated nav label is worse than no label. A fifth destination means an
 * overflow, not a smaller font.
 */
@Composable
fun GridlinkNavPill(
    selected: GridlinkDestination,
    onSelect: (GridlinkDestination) -> Unit,
    modifier: Modifier = Modifier,
    /** True while rows are ticked: the contents cross-fade to [GridlinkSelectionAction]s. */
    selecting: Boolean = false,
    onSelectionAction: (GridlinkSelectionAction) -> Unit = {},
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Box(
        // 🔴 No fillMaxWidth. The pill now shares its row with the compose button, so the caller
        // sizes it with a weight and this must not fight that.
        modifier = modifier
            .height(GRIDLINK_PILL_HEIGHT)
            // The halo. Brandon's dashboards read as premium because action surfaces appear to
            // EMIT light rather than sit on it, and the halo is what does that. Suppressed in OLED
            // along with every other shadow.
            .gridlinkGlow(
                colors.actionGlow?.copy(alpha = 0.28f),
                // 0.4 of the pill's width puts the falloff a little under half a pill-height
                // outside it, which is a halo. The 0.85 this started at is a floodlight.
                radiusMultiplier = 0.4f,
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
    ) {
        AnimatedContent(
            targetState = selecting,
            // 🔴 Cross-fade only, and no SizeTransform. The container above is already sized, so
            // letting the content animate its own size would make the pill's insides breathe
            // inside a shell that is not moving. The brief's "contents cross-fade while the
            // container's shape holds" is exactly this and nothing more.
            transitionSpec = {
                fadeIn(GridlinkMotion.toolbarMorph()) togetherWith
                    fadeOut(GridlinkMotion.toolbarMorph())
            },
            label = "navToToolbar",
        ) { isSelecting ->
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelecting) {
                    GridlinkSelectionAction.entries.forEach { action ->
                        GridlinkPillItem(
                            label = action.label,
                            icon = action.icon,
                            tint = if (action.destructive) {
                                colors.destructive
                            } else {
                                colors.textPrimary
                            },
                            onClick = { onSelectionAction(action) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    // 🔴 Filtered, not `entries`. Folders has no seat any more (see
                    // [GridlinkDestination.inPill]) and it is still a destination the app can BE on,
                    // so nothing is highlighted while the folder screen is up. That is correct: the
                    // pill says where the four places are, and the manage-folders screen is reached
                    // from the drawer the same way Settings is.
                    GridlinkDestination.entries.filter { it.inPill }.forEach { destination ->
                        val active = destination == selected
                        GridlinkPillItem(
                            label = destination.label,
                            icon = destination.icon,
                            tint = if (active) {
                                colors.onAccent
                            } else {
                                colors.textSecondary
                            },
                            filled = active,
                            onClick = { onSelect(destination) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One item in the bottom pill: a nav destination, or a selection action.
 *
 * Both states share this so the cross-fade lands glyph-on-glyph and label-on-label. Four things at
 * the same four positions, in the same 20dp-icon-over-11sp-label stack, is what makes the morph
 * read as a container changing its mind rather than as two different bars.
 *
 * [filled] is the active nav capsule: a bright gradient with a white glyph. That is Brandon's
 * standing on/off vocabulary. 🔴 Never dim the unfilled one with alpha — he reads opacity-dimming
 * as broken rather than as off, and has said so more than once. Selection actions are never filled,
 * because none of the four is a state you are currently in.
 */
@Composable
private fun GridlinkPillItem(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    val fill = Modifier.background(gridlinkAccentFill(colors.accent), shape)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .clickable(onClick = onClick)
            // 🔴 No vertical padding here. The container's 8dp already insets the capsule, and
            // adding 8 more each side left 48dp of the pill's 64 for a 20dp icon plus an 11sp
            // label, which silently clipped every label in half.
            .then(if (filled) fill else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = GridlinkType.toolbarLabel,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Foreground colour for anything sitting on a fill whose colour is NOT the accent, which today
 * means the swipe tracks: green archive, amber mark-unread, red delete.
 *
 * Read off the fill's own luminance rather than off the mode, because Day's flat background is the
 * gradient's end stop and not a neutral, so using it here produced cyan text on blue.
 *
 * 🔴 For an accent fill, read [GridlinkColors.onAccent] instead. This heuristic returns white for
 * OLED's `#F97316` (luminance ≈ 0.32, under the threshold) and that fails contrast outright, which
 * is exactly why the palette now carries the answer.
 *
 * ⚠️ The threshold is left alone rather than replaced with a real contrast comparison. A contrast
 * chooser would flip the archive glyph from white to near-black on `#16A34A`, and the swipe screen
 * is signed off as it stands. If the tracks are ever retuned, revisit this then and not before.
 */
fun gridlinkOnAccent(fill: Color): Color =
    if (fill.luminance() > 0.5f) Color.Black else Color.White

/**
 * The "on" fill shared by the compose button and the selected nav destination.
 *
 * A 135-degree diagonal from the accent into a darkened accent, which is the dashboard's action
 * button treatment. 🔴 Both stops are fully opaque. An earlier version faded the second stop with
 * alpha instead of darkening it, which let whatever sat behind the control show through its own
 * fill and made a pressed-looking smudge on the darker half.
 */
fun gridlinkAccentFill(accent: Color, darken: Float = 0.32f): Brush = Brush.linearGradient(
    colors = listOf(accent, lerp(accent, Color.Black, darken)),
    start = Offset.Zero,
    end = Offset.Infinite,
)

/**
 * How far the action button's warm fill darkens toward its far corner. See
 * [app.gridlink.ui.theme.GridlinkColors.onAccentWarm] for why it is not the accent fill's 0.32:
 * Day's amber runs out of contrast at both ends of a full-depth ramp, and the shallower one is what
 * lets a single dark ink clear 4.5:1 across the whole button in every mode.
 */
const val GRIDLINK_WARM_FILL_DARKEN = 0.12f

/**
 * Compose, detached and floating.
 *
 * ⚠️ §9 of the brief bans a floating action button outright; Brandon overrode it. It is sized to
 * [GridlinkDimens.composeButton] so it shares a line and a baseline with the nav pill instead of
 * stacking above it, and it is the one control in the app that gets the full treatment — gradient
 * fill, white glyph, real halo — because it is the only thing down there that creates something
 * rather than navigating to it.
 *
 * ## 🔴 The glyph does NOT follow the tab, and neither does the label
 * The glyph used to: a pencil on mail, a plus on Calendar and Contacts, crossfading between them.
 * Brandon killed it — "having a 'write' button is confusing" — and he is right about why. A
 * landmark that changes its face is no longer a landmark; you have to read it before you trust it,
 * every time, which is the opposite of what a fixed button in a fixed place is for. It is always a
 * "+" now, and what the "+" makes is decided by the tab you are on and stated in
 * [GridlinkDestination.composeLabel].
 *
 * The label came later — "the + needs a text descriptor like edit, reply, etc so its not
 * confusing" — and it is "New" on every tab for the glyph's exact reason: "Write" here would put
 * the confusing word back, and a label that rewrote itself on a tab switch would be the pencil
 * problem again in text. The full per-tab meaning still reaches screen readers through
 * [contentDescription], which outranks the visible text.
 *
 * ## Why it delegates to [GridlinkDetailAccentButton] instead of drawing itself
 * "Like edit, reply" is the whole spec: the detail screens' accent circle already puts a 20dp
 * glyph over an 11sp label inside the same 64dp, same fill, same halo. Two hand-matched copies
 * would drift the first time one was touched, and that delegation is what made the 2026-08 recolour
 * a one-line change: the compose "+" went warm because the accent circle did, in the same edit.
 *
 * ## Where it sits is the scaffold's business, not this button's
 * It takes a [modifier] and uses it, and it does not position itself. [GridlinkScaffold] slides it
 * between the left edge in one pane and the far right of the window in two; see the note there.
 */
@Composable
fun GridlinkComposeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destination: GridlinkDestination = GridlinkDestination.INBOX,
) {
    GridlinkDetailAccentButton(
        icon = Icons.Outlined.Add,
        label = "New",
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = destination.composeLabel },
    )
}

/**
 * Height shared by the nav pill and the selection toolbar it morphs into.
 *
 * 🔴 Load-bearing for §6b: if these two ever disagree the morph becomes a resize, which is exactly
 * the "arrival" the brief rules out. Both read this constant.
 */
val GRIDLINK_PILL_HEIGHT = 64.dp

// The Auto / Day / Night / OLED override pill used to live here, summoned by a long-press on the
// screen title and dismissed on an idle timeout. It is gone: the control Brandon asked for is a
// segmented track inside the menu sheet (see GridlinkModeRow in GridlinkMenu.kt), and keeping a
// second one floating over every screenshot would have left two ways to set the same thing, one of
// them reachable only in a debug build.

// GRIDLINK_PILL_CLEARANCE used to live here: the bottom padding a list needed so its last row could
// scroll clear of a pill it passed behind. Nothing passes behind the pill any more, so there is
// nothing to clear.
