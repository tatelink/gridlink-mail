package app.gridlink.ui.gridlink

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContactSection
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import app.gridlink.ui.theme.gridlinkSenderBarColor
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Contacts: the address book, and the alphabet you steer it with.
 *
 * Tate's whole spec for this screen was one sentence, and it is a spec for the rail rather than
 * for the list: *"contacts just a phonebook with the alphabet on the right translucent that expands
 * when click-held and magnifies the selection as you slide down the alphabet to select a spot in
 * your address book"*. The list is therefore deliberately plain — the same dense, hairline-separated
 * rows the rest of the app uses, at the same 64dp two-line height as a message — and all of the
 * thinking is in [GridlinkAlphabetRail].
 *
 * The brief has no Contacts section at all, so everything not in that sentence comes from what the
 * app has already settled: no avatars (§9), so identity is the 3dp domain-coloured bar a message row
 * uses; [GridlinkSectionLabel] for the letter headings, because a letter heading is a section label;
 * the panel, the edge fades and the weighted fling from [GridlinkScaffold].
 */
@Composable
fun GridlinkContactsScreen(
    destination: GridlinkDestination,
    onSelectDestination: (GridlinkDestination) -> Unit,
    modifier: Modifier = Modifier,
    /** Gallery only: composes with the rail already held at this letter, for a still capture. */
    initialScrubLetter: Char? = null,
    onOpenContact: (GridlinkContact) -> Unit = {},
    onCompose: () -> Unit = {},
    /** §7's detail pane, or null when the window is too narrow for one. */
    sidePane: (@Composable () -> Unit)? = null,
    /**
     * The contact the pane is showing, so the list can mark it.
     *
     * 🔴 Null in one pane, always. A row highlighted for a card that is not on screen is a row that
     * looks stuck, and the same defect was already fixed once in the message list.
     */
    currentId: String? = null,
) {
    val book = LocalGridlinkBook.current
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    // Tate: "contacts single pane needs a way to change sorting easily, by first name last name,
    // lastname firstname". A device preference rather than per-launch state — flipping it every
    // visit would make it a chore, which is the opposite of "easily" — read once here and written
    // through on toggle. SharedPreferences directly, on MessageScreen's GPU-sentinel precedent: one
    // key does not earn a DataStore.
    var sort by remember {
        mutableStateOf(if (preview) GridlinkContactSort.LAST_FIRST else loadContactSort(context))
    }
    // LAST_FIRST is the book's own order, already sectioned and cached. FIRST_LAST regroups the same
    // list by the word the eye now leads with. Done here rather than in [GridlinkBook] because the
    // order only matters to this screen: the book's other consumers (pickers, domain lookups) never
    // show a sorted list.
    val sections = remember(book, sort) {
        when (sort) {
            GridlinkContactSort.LAST_FIRST -> book.sections
            GridlinkContactSort.FIRST_LAST -> book.contacts
                .sortedWith(
                    compareBy(
                        { it.firstNameKey.lowercase() },
                        { it.filedUnder.lowercase() },
                    ),
                )
                .groupBy { it.firstNameKey.first().uppercaseChar() }
                .toSortedMap()
                .map { (letter, people) -> GridlinkContactSection(letter, people) }
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Which lazy item each letter's heading is, so a scrub can jump straight to it. Headings and
    // rows share one index space, so this is a running count rather than anything cleverer.
    val headingIndex = remember(sections) {
        buildMap {
            var index = 0
            sections.forEach { section ->
                put(section.letter, index)
                index += 1 + section.contacts.size
            }
        }
    }
    val populated = remember(sections) { sections.map { it.letter }.toSet() }

    fun jumpTo(letter: Char) {
        val target = headingIndex[letter] ?: return
        // 🔴 scrollToItem, not animateScrollToItem. A scrub is direct manipulation: the list belongs
        // under the thumb. One animation queued per letter crossed means the list is still
        // travelling to G while the thumb is already at M, and then has to chase it through every
        // letter in between. Animating is right for a tap on a letter and wrong for a drag, and it
        // is the same code path, so the drag wins.
        scope.launch { listState.scrollToItem(target) }
    }

    // One pill used by whichever seat currently owns it, never both.
    val sortPill: @Composable () -> Unit = {
        GridlinkSortPill(
            sort = sort,
            onToggle = {
                sort = sort.other
                if (!preview) saveContactSort(context, sort)
                // The whole book just reshuffled, so wherever the list was is now a different
                // run of names. Top is the one position that still means something.
                scope.launch { listState.scrollToItem(0) }
            },
        )
    }

    // Measured, not guessed from the configuration, for [GridlinkModalDrawer]'s reasons. On a
    // folded phone the chrome row cannot hold the hamburger, the title, the count AND the pill —
    // the title is the row's designed flex victim, so it is "Cont..." that gives way, which is the
    // same defect Tate just reported on the calendar. Same cure, too: below this width the pill
    // moves down into its own line above the panel, where there is (his words) "plenty of vertical
    // room".
    BoxWithConstraints(modifier = modifier) {
        val pillFitsChrome = maxWidth >= SORT_PILL_CHROME_MIN_WIDTH
        GridlinkScaffold(
            destination = destination,
            onSelectDestination = onSelectDestination,
            onCompose = onCompose,
            sidePane = sidePane,
            header = {
                GridlinkHeader(
                    title = "Contacts",
                    unread = 0,
                    // Same reasoning as the folder tree's "N mailboxes": the second line summarises
                    // the screen, and a phonebook's summary is how many entries are in it. "Loading"
                    // while the first read is out, for the folder tree's reason as well: a count of
                    // zero is a claim that the account has nobody in it, which is a different thing
                    // from not having looked.
                    subline = if (book.contactsLoading) "Loading" else "${book.contacts.size} people and teams",
                )
            },
            trailing = if (pillFitsChrome) sortPill else null,
            belowHeader = if (pillFitsChrome) null else {
                {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        // At the end, where the pill lives on every wider window, so folding does
                        // not also teleport it to the other side of the screen.
                        horizontalArrangement = Arrangement.End,
                    ) {
                        sortPill()
                    }
                }
            },
        ) {
            LazyColumn(
                state = listState,
                flingBehavior = rememberGridlinkFlingBehavior(),
                modifier = Modifier
                    .fillMaxSize()
                    .gridlinkEdgeFade(),
                contentPadding = PaddingValues(
                    top = GridlinkDimens.listFade,
                    bottom = GridlinkDimens.listFade,
                ),
            ) {
                sections.forEach { section ->
                    item(key = "heading-${section.letter}") {
                        GridlinkSectionLabel(section.letter.toString())
                    }
                    itemsIndexed(
                        items = section.contacts,
                        key = { _, contact -> contact.id },
                    ) { index, contact ->
                        Column {
                            GridlinkContactRow(
                                contact = contact,
                                sort = sort,
                                onClick = { onOpenContact(contact) },
                                current = contact.id == currentId,
                            )
                            // No rule under the last row of a letter: the next thing down is a
                            // section label, which already separates. A hairline there reads as an
                            // orphaned line floating above a heading.
                            if (index != section.contacts.lastIndex) {
                                GridlinkRowDivider(
                                    // Stops where the rail starts. Run full width and the hairline
                                    // passes under the resting alphabet, striking through three or
                                    // four letters at a time — which reads as the letters being
                                    // crossed out.
                                    modifier = Modifier.padding(end = RAIL_RESTING),
                                    startInset = GridlinkSpacing.rowHorizontal +
                                        GridlinkDimens.senderBarWidth,
                                )
                            }
                        }
                    }
                }
            }

            GridlinkAlphabetRail(
                populated = populated,
                onScrubTo = ::jumpTo,
                initialScrubLetter = initialScrubLetter,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * One entry.
 *
 * Two lines, name over role, at the same height as a message row because it is the same shape of
 * thing: a heading and a qualifier under it. The role rather than the address on line two, because
 * the address is what you want once you have found somebody and the role is what tells you that you
 * have.
 *
 * The surname carries the weight. See [GridlinkSampleContacts] on why the list sorts by a word that
 * is not the first one you read, and why marking that word is the fix rather than reordering names.
 */
@Composable
private fun GridlinkContactRow(
    contact: GridlinkContact,
    sort: GridlinkContactSort,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when the detail pane beside this list is showing this contact. */
    current: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    // The same wash the message list uses for the row its pane is showing, on the same token and the
    // same curve, so "this is the one you are looking at" is one visual idea across the app rather
    // than two that nearly match. No tick disc: that belongs to multi-select, and there is no
    // multi-select here.
    val fill by animateColorAsState(
        targetValue = if (current) colors.selection else Color.Transparent,
        animationSpec = GridlinkMotion.standard(),
        label = "contactSelection",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.messageRowHeight)
            .background(fill)
            .clickable(onClick = onClick),
    ) {
        // The avatar's replacement, exactly as on a message row: identity as a colour, not a disc
        // with initials in it. A contact and their mail therefore carry the same stripe.
        Box(
            modifier = Modifier
                .width(GridlinkDimens.senderBarWidth)
                .fillMaxHeight()
                .background(gridlinkSenderBarColor(mode, contact.domain)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = GridlinkSpacing.rowHorizontal + GridlinkDimens.senderBarWidth,
                    // 🔴 Clears the rail. The rail floats over this list rather than sitting beside
                    // it, so without this a long role runs on underneath the alphabet and the
                    // letters land on top of the words.
                    end = GridlinkSpacing.rowHorizontal + RAIL_RESTING,
                    top = GridlinkSpacing.rowVertical,
                    bottom = GridlinkSpacing.rowVertical,
                ),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = buildAnnotatedString {
                    // The heavy word is always the letter's word, whichever order is on. Filed
                    // "Last, First", [GridlinkContact.filedUnder] is the letter's word and the given
                    // name leads it light; filed "First Last", the given name IS the letter's word,
                    // so the weight moves onto it and the surname trails light. Either way the word
                    // you scrubbed the rail to is the word that stands out. The given name appears
                    // only when it is not itself the thing being filed under (a person with no
                    // surname files under the given name, so writing it light AND heavy would print
                    // "Cher Cher"), and org cards read identically in both orders.
                    if (!contact.organization && contact.family.isNotEmpty()) {
                        when (sort) {
                            GridlinkContactSort.LAST_FIRST -> {
                                append("${contact.given} ")
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                    append(contact.filedUnder)
                                }
                            }
                            GridlinkContactSort.FIRST_LAST -> {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                    append(contact.given)
                                }
                                append(" ${contact.family}")
                            }
                        }
                    } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(contact.filedUnder)
                        }
                    }
                },
                style = GridlinkType.senderName.copy(fontWeight = FontWeight.Normal),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = contact.role,
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sort order
// ---------------------------------------------------------------------------------------------

/**
 * The two orders Tate asked for, and there are deliberately no more than two: "by first name
 * last name, lastname firstname". A toggle, not a menu.
 */
enum class GridlinkContactSort(
    /** What the pill says. Reads as the order itself, not as an instruction. */
    val label: String,
) {
    /** Phonebook order: the surname (or the company, for org cards) files the card. The default,
     *  because it is what a phonebook does and what the app has always done. */
    LAST_FIRST("Last, First"),

    /** Reading order: the given name files the card. Org cards and surname-less people keep filing
     *  under [GridlinkContact.filedUnder], which for them is the only word there is. */
    FIRST_LAST("First Last"),
    ;

    val other: GridlinkContactSort
        get() = if (this == LAST_FIRST) FIRST_LAST else LAST_FIRST
}

/**
 * The word FIRST_LAST files a card under.
 *
 * 🔴 `given.ifEmpty { filedUnder }`, never `given` bare and never `family` at all: a company-only
 * card has a blank given name with the whole name in `company`, and a surname-less person has the
 * name in `given` already. [GridlinkContact.filedUnder] is the one derivation that has resolved all
 * of that, so it is the fallback here for the same reason it is the sort key everywhere else.
 */
private val GridlinkContact.firstNameKey: String
    get() = given.ifEmpty { filedUnder }

private const val SORT_PREFS = "gridlink_contacts"
private const val SORT_KEY = "sort"

private fun loadContactSort(context: Context): GridlinkContactSort {
    val stored = context.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE)
        .getString(SORT_KEY, null)
    // firstOrNull, not valueOf: a value written by a build that later renamed the constant should
    // fall back to the default, not crash the contacts screen forever.
    return GridlinkContactSort.entries.firstOrNull { it.name == stored }
        ?: GridlinkContactSort.LAST_FIRST
}

private fun saveContactSort(context: Context, sort: GridlinkContactSort) {
    context.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE)
        .edit().putString(SORT_KEY, sort.name).apply()
}

/**
 * The order toggle, in the chrome row's trailing seat.
 *
 * Dressed exactly as the inbox's collapsed search pill (44dp, 14% surface, 35% hairline): the seat
 * is the same, so the treatment is the same, and the glass recipe already means "a control that
 * stays out of the way until you want it". The label states the CURRENT order — the pill describes
 * the list, and tapping it is how you get the other one. Labelling it with the order you would GET
 * was the alternative, and that reading makes the pill disagree with the list it sits above.
 */
@Composable
private fun GridlinkSortPill(
    sort: GridlinkContactSort,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .height(SORT_PILL_HEIGHT)
            .clip(shape)
            .background(colors.surface.copy(alpha = 0.14f), shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder.copy(alpha = 0.35f), shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
    ) {
        Icon(
            imageVector = Icons.Outlined.SwapHoriz,
            // The description carries the action; the visible label carries the state.
            contentDescription = "Change name order",
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = sort.label,
            style = GridlinkType.metadata,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** The collapsed search pill's height, so the two chrome-row pills read as siblings. */
private val SORT_PILL_HEIGHT = 44.dp

/**
 * Below this window width the pill leaves the chrome row for its own line. 600dp is the compact
 * boundary the platform uses for the same judgement; a folded Fold (~393dp) is well under it and an
 * unfolded one (~754dp) is comfortably over, and those are the two windows that exist here.
 */
private val SORT_PILL_CHROME_MIN_WIDTH = 600.dp

// ---------------------------------------------------------------------------------------------
// The rail
// ---------------------------------------------------------------------------------------------

/** Resting width, and therefore the end padding a row needs to keep its text out from under it. */
private val RAIL_RESTING = 24.dp

/** The pill's width once held, and the hit area at all times: 44dp is a real touch target, and the
 *  rail is thumb-driven, so it gets one whether or not it is currently drawn that wide. */
private val RAIL_EXPANDED = 44.dp

private val BUBBLE_SIZE = 76.dp
private val BUBBLE_GAP = 12.dp

/**
 * How much bigger the letter directly under the thumb gets: 1.15 takes a 10sp glyph to about 21sp.
 *
 * ⚠️ Bounded by the slot, not by taste. Twenty-six letters divide the rail's height, which on a
 * folded Fold is roughly 23dp each, and a glyph scaled past that starts colliding with the letters
 * either side of it — the lens stops magnifying and starts overlapping. Anything much above this
 * needs the rail to hold fewer letters, not a bigger number here.
 */
private const val MAGNIFY = 1.15f

/** Width of the magnification falloff, in letters. At 1.5 the two neighbours either side visibly
 *  swell and the third is back to normal, which is a lens rather than a highlight. */
private const val SIGMA = 1.5f

/**
 * The alphabet down the trailing edge.
 *
 * ## The gesture
 * Touch it and it is live at once, with no long-press delay. Tate said "click-held", and a hold
 * *is* how you use it, but gating on a timer would mean a straight tap on a letter (the other half
 * of how everybody uses these) does nothing at all, and a scrub that starts fast gets swallowed.
 * Touch down expands it, sliding scrubs it, lifting collapses it, and holding still is simply the
 * case where the thumb has not moved yet.
 *
 * ## The magnification
 * 🔴 Driven by the pointer's *fractional* position, not by which letter is currently selected. A
 * Gaussian centred on, say, letter 7.35 is continuous, so the lens slides with the thumb between
 * letters instead of snapping a letter at a time, and it costs zero animations: nothing here
 * interpolates, the scale is just a function of where the finger is. Twenty-six
 * `animateFloatAsState`s driven off an integer index was the obvious build, and it both stutters
 * and churns recomposition on every frame of a drag.
 *
 * The one thing that IS animated is [expansion], a single float for the whole rail, so growing and
 * collapsing is a movement rather than a pop. The magnification is multiplied by it, which means
 * the lens grows out of the flat rail and shrinks back into it for free.
 *
 * 🔴 The glyphs scale inside a `graphicsLayer`, so layout never changes: twenty-six fixed slots
 * dividing the height, and a letter that grows overflows its own slot instead of shoving its
 * neighbours down the rail. Scaling by re-measuring the text would make the whole alphabet breathe,
 * and the letter under the thumb would end up somewhere other than where the thumb is.
 *
 * ## The bubble
 * The thumb covers the rail, which is the flaw in magnifying in place: the thing you enlarged is
 * under a finger. So the active letter is also drawn large to the left of the rail, level with the
 * slot it belongs to. That is the part you can actually read while scrubbing.
 */
@Composable
private fun GridlinkAlphabetRail(
    populated: Set<Char>,
    onScrubTo: (Char) -> Unit,
    modifier: Modifier = Modifier,
    initialScrubLetter: Char? = null,
) {
    val colors = GridlinkTheme.colors
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val letters = GridlinkSampleContacts.alphabet

    val seedIndex = remember(initialScrubLetter) {
        initialScrubLetter?.let { letters.indexOf(it.uppercaseChar()) }?.takeIf { it >= 0 }
    }
    var scrubbing by remember { mutableStateOf(seedIndex != null) }
    // Deliberately kept after the lift, so the collapse has a centre to shrink towards instead of
    // throwing the lens back to letter zero on its way out.
    var focus by remember { mutableFloatStateOf(seedIndex?.toFloat() ?: 0f) }
    var activeIndex by remember { mutableIntStateOf(seedIndex ?: -1) }
    var railHeightPx by remember { mutableIntStateOf(0) }

    val expansion by animateFloatAsState(
        targetValue = if (scrubbing) 1f else 0f,
        animationSpec = GridlinkMotion.standard(),
        label = "railExpansion",
    )

    // The letter a scrub at this index should actually land on.
    //
    // Backwards first: dragging into an empty X holds at W, the way running a finger past the end
    // of a section in a paper index leaves you at the last thing there was. Forwards only when
    // there is nothing above, which is the A-is-empty case.
    fun resolve(index: Int): Char? {
        for (i in index downTo 0) if (letters[i] in populated) return letters[i]
        for (i in index..letters.lastIndex) if (letters[i] in populated) return letters[i]
        return null
    }

    LaunchedEffect(seedIndex) {
        seedIndex?.let { index -> resolve(index)?.let(onScrubTo) }
    }

    val pillShape = RoundedCornerShape(GridlinkRadii.pill)
    val bubbleShape = RoundedCornerShape(GridlinkRadii.card)

    Box(
        modifier = modifier
            .fillMaxHeight()
            // Wide enough to hold the bubble as well as the rail. Only the letter column takes
            // pointer input, so the empty part of this box does not steal touches from the list.
            .width(BUBBLE_SIZE + BUBBLE_GAP + RAIL_EXPANDED)
            .padding(vertical = GridlinkDimens.listFade),
    ) {
        // The pill. Invisible at rest — "translucent" is Tate's word for the letters, and a bar
        // sitting behind them permanently would be a second vertical edge inside a panel that
        // already has one.
        if (expansion > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(lerp(RAIL_RESTING, RAIL_EXPANDED, expansion))
                    .fillMaxHeight()
                    .graphicsLayer { alpha = expansion }
                    .clip(pillShape)
                    // Two fills, for the reason [GridlinkModalCard] documents: Day's raised surface
                    // is 72% white, and 28% of a scrolling contact list showing through is exactly
                    // the noise the pill exists to remove from behind the letters.
                    .background(colors.background, pillShape)
                    .background(colors.surfaceRaised, pillShape)
                    .border(GridlinkDimens.hairline, colors.surfaceBorder, pillShape),
            )
        }

        if (activeIndex >= 0 && expansion > 0.01f && railHeightPx > 0) {
            val slot = railHeightPx / letters.size.toFloat()
            val bubblePx = with(density) { BUBBLE_SIZE.toPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (slot * (activeIndex + 0.5f) - bubblePx / 2f).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        alpha = expansion
                        // Grows out of the rail rather than fading in on the spot, which reads as
                        // the lens being drawn off the alphabet.
                        scaleX = 0.85f + 0.15f * expansion
                        scaleY = 0.85f + 0.15f * expansion
                        transformOrigin = TransformOrigin(1f, 0.5f)
                    }
                    .size(BUBBLE_SIZE)
                    .clip(bubbleShape)
                    .background(colors.background, bubbleShape)
                    .background(colors.surfaceRaised, bubbleShape)
                    .border(GridlinkDimens.hairline, colors.surfaceBorder, bubbleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letters[activeIndex].toString(),
                    style = GridlinkType.screenTitle.copy(fontSize = 40.sp),
                    color = colors.accent,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(RAIL_EXPANDED)
                .fillMaxHeight()
                .onSizeChanged { railHeightPx = it.height }
                .pointerInput(populated) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 🔴 Consumed, or the list scrolls underneath the scrub and the two
                            // fight over the same finger.
                            down.consume()
                            scrubbing = true

                            fun track(y: Float) {
                                val height = size.height.toFloat()
                                if (height <= 0f) return
                                val slot = height / letters.size
                                val fractional = (y / slot).coerceIn(0f, letters.size - 0.001f)
                                focus = fractional
                                val index = fractional.toInt()
                                if (index == activeIndex) return
                                activeIndex = index
                                // A tick per letter crossed. Without it the only confirmation the
                                // scrub moved is the list moving, and the list is the thing the
                                // hand is covering.
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                resolve(index)?.let(onScrubTo)
                            }

                            track(down.position.y)
                            var pointerId = down.id
                            while (true) {
                                val change = awaitPointerEvent().changes
                                    .firstOrNull { it.id == pointerId }
                                if (change == null || !change.pressed) break
                                change.consume()
                                track(change.position.y)
                                pointerId = change.id
                            }
                            scrubbing = false
                        }
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEachIndexed { index, letter ->
                val has = letter in populated
                val distance = index - focus
                val lens = exp(-(distance * distance) / (2f * SIGMA * SIGMA))
                val scale = 1f + expansion * MAGNIFY * lens
                Box(
                    modifier = Modifier
                        .width(RAIL_EXPANDED)
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = letter.toString(),
                        style = GridlinkType.badge.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        // ⚠️ An alpha ramp, and it is not dimming a control. A rail letter is a
                        // signpost: the ramp says "translucent until you touch me" (Tate's word
                        // for the resting state) and "nobody is filed under X". Neither of those is
                        // an on/off state being greyed out, which is the thing that is banned.
                        color = if (has) {
                            colors.textSecondary.copy(alpha = lerpF(0.55f, 1f, expansion))
                        } else {
                            colors.textSecondary.copy(alpha = lerpF(0.22f, 0.40f, expansion))
                        },
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            }
        }
    }
}

private fun lerpF(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
