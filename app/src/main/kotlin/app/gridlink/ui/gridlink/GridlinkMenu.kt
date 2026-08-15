package app.gridlink.ui.gridlink

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInbox
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkColors
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMode
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * The app's own row of chrome, above every screen, and the sheet the menu opens.
 *
 * ## One line now, not a row above a header
 * This started as a dedicated strip above a stacked 32sp header, chosen over an inline title
 * precisely because "a 32sp ExtraBold title next to two 44dp circles has nowhere to wrap". Brandon
 * overrode that with the restructure that made both panes taller: "leave the hamburger menu where it
 * is, put INBOX on the same horizontal line, and put the search icon on the same line. then extend
 * both side panes upward." So the title moved INTO this row (the [header] slot), the screen's
 * trailing control (search, the calendar steppers) moved to its far end, and the vertical space the
 * stacked header spent is now panel. The wrap problem is answered with `maxLines = 1` and ellipsis
 * in [GridlinkHeader], which is the trade he picked.
 *
 * ## Why it lives in the scaffold and not in the screens
 * The hamburger and the sync chip belong to the app and say nothing about where you are: the same
 * menu and the same sync state on all four tabs. Four screens each passing the same two arguments
 * would work until one forgot, and that would silently lose the app's only route to Settings. The
 * screens supply only what is theirs: the title block and the trailing control.
 */

/** How the connection is doing, as the trailing edge of the chrome row states it. */
enum class GridlinkSyncState(val label: String) {
    SYNCED("Synced"),
    SYNCING("Syncing"),
    OFFLINE("Offline"),
    ;

    /**
     * 🔴 [GridlinkColors.destructive] is not an option for [OFFLINE], however much it looks like the
     * obvious choice. Red is spent on delete and on nothing else in this app, and an offline mailbox
     * is not a destroyed one: the mail is still there, it is just not moving.
     *
     * 🔴 Nor is [GridlinkColors.caution], which this used to be and which Brandon caught on sight:
     * "shouldnt be orange". Caution amber is not a general-purpose warning in this palette, it is
     * stage one of the two-stage destructive swipe, and it is warm precisely so that escalating into
     * red reads as escalation. Offline escalates into nothing. Borrowing the colour put a swipe-track
     * signal in the app's permanent chrome, where it looks like the app is about to do something.
     *
     * So the dot goes inert instead: the same [GridlinkColors.textSecondary] as the label beside it.
     * Synced and Syncing are lit, offline is not lit, and the absence of colour IS the state. That
     * also means the chip only ever spends a semantic colour when something is actually working.
     */
    @Composable
    fun dotColor(): Color = when (this) {
        SYNCED -> GridlinkTheme.colors.positive
        SYNCING -> GridlinkTheme.colors.accent
        OFFLINE -> GridlinkTheme.colors.textSecondary
    }
}

/** The dot in front of the sync label. Small enough to be punctuation rather than a badge. */
private val SYNC_DOT = 8.dp

/** Height of the sync chip. Well under the 44dp circle beside it, because it is a readout. */
private val SYNC_CHIP_HEIGHT = 28.dp

/**
 * Menu on the left, the screen's title beside it, sync state and the trailing control on the right.
 *
 * The hamburger is a 44dp circle and reads as a control; the 28dp chip with no press state is a
 * readout and reads as one. Making the sync chip tappable would be inventing a behaviour the design
 * does not specify, and the state it would have to open is already the first line of the menu sheet.
 *
 * ## 🔴 The chip sits LEFT of [trailing], and it animates its width
 * Brandon's ask pins the search icon to the far right of the line, so the corner is [trailing]'s and
 * the chip slides in beside it. It expands and shrinks rather than only fading, because it shares a
 * Row with a weight-sized title block: a chip that claimed its width on frame one would shove the
 * trailing control sideways in a single jump every time a sync started.
 *
 * ## 🔴 Nothing is shown when everything is fine
 * Brandon, on the inbox: *"get rid of 'synced' its redundant"*. It was. A chip that says "Synced"
 * is on screen every second of every normal day to report that nothing is wrong, which is the
 * default assumption anyway, and it spends the app's one permanent status slot saying it. Worse, a
 * readout that is always present stops being read, so the two states you actually need to notice
 * (a sync running, and a mailbox that is not moving) arrive in a slot the eye has already learned
 * to skip.
 *
 * So [GridlinkSyncState.SYNCED] renders nothing at all and the chip becomes an exception report.
 * The absence of the chip is the healthy state, and its appearance means something.
 *
 * ⚠️ This does not remove the sync readout from the app, and it must not: "is it empty or is it
 * broken" is a real question and it still has two answers, both of them worded by
 * [gridlinkSyncSentence]. The drawer carries "Synced 4 min ago" where you go looking for it, and
 * [GridlinkEmptyState] carries it where you would otherwise have to guess.
 */
@Composable
fun GridlinkChromeRow(
    onOpenMenu: () -> Unit,
    sync: GridlinkSyncState,
    modifier: Modifier = Modifier,
    /** The screen's title block, drawn beside the hamburger. See [GridlinkHeader]. */
    header: (@Composable () -> Unit)? = null,
    /** The screen's far-right control: the inbox's search pill, the calendar's steppers. */
    trailing: (@Composable () -> Unit)? = null,
    /**
     * Non-null while a selection is open: the leading circle becomes the way out of it.
     *
     * §6b puts the close control on the header's leading edge, and this is the seat it means. It
     * cannot go in the nav pill, which is at its documented four-item ceiling on both sides, and it
     * is not a fifth selection action anyway: the four in the pill do something to the messages, this
     * one puts the mail down.
     *
     * ⚠️ The menu is unreachable while it is a close, which is the trade §6b asks for and is worth
     * naming. A selection is a mode, the app's other modes (search, the drawer) are already mutually
     * exclusive with it, and a hamburger that opened a drawer over eleven ticked rows would be
     * offering to leave the screen mid-action. One tap gets it back.
     */
    onCloseSelection: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = GridlinkSpacing.chrome,
                // The gap between this line and the glass panels below it. The stacked header used
                // to spend s20 under its subline; the single line spends s16, which is the same
                // breath the panels get from the pad line at their sides.
                bottom = GridlinkSpacing.s16,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GridlinkMenuButton(
            onClick = onCloseSelection ?: onOpenMenu,
            closing = onCloseSelection != null,
        )
        // The weight is what pins [trailing] to the corner AND what lets the search pill open
        // leftward: the pill growing simply takes width back from this box, so its right edge never
        // moves off the pad line.
        Box(modifier = Modifier.weight(1f).padding(start = GridlinkSpacing.s16)) {
            header?.invoke()
        }
        // Width animates along with the fade — see the header comment on why the chip must not
        // claim its space in one frame.
        AnimatedVisibility(
            visible = sync != GridlinkSyncState.SYNCED,
            enter = fadeIn(GridlinkMotion.standard()) + expandHorizontally(GridlinkMotion.standard()),
            exit = fadeOut(GridlinkMotion.standard()) + shrinkHorizontally(GridlinkMotion.standard()),
        ) {
            GridlinkSyncChip(sync = sync, modifier = Modifier.padding(start = GridlinkSpacing.s12))
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = GridlinkSpacing.s12)) {
                trailing()
            }
        }
    }
}

/**
 * The hamburger, wearing the search pill's collapsed treatment.
 *
 * Same 44dp [GridlinkDimens.headerControl] circle, same translucent fill, same hairline at 35%. The
 * two are the only chrome circles on the screen and they sit at opposite corners of the header, so
 * anything that made them different treatments would read as one of them being broken.
 *
 * ⚠️ One deliberate difference: the glyph is [GridlinkColors.textSecondary] at full strength, where
 * search's is 55% of it. Search is ghosted on purpose, because it is a verb you want four seconds a
 * day and it sits on top of the list you are reading. The menu is the app's only route to Settings,
 * Drafts and Scheduled, and a route that is hard to see is a route people do not find.
 *
 * ## 🔴 [closing] swaps the glyph, and only the glyph
 * The circle, its fill, its hairline and its click target are drawn ONCE and outside the animation.
 * §6b's rule for the nav pill ("a transformation, not an arrival") is the same rule here for the same
 * reason: an `if` around the whole control is one circle leaving and another arriving in the corner
 * the eye is already resting on, and no amount of matching dimensions hides the blink.
 *
 * ⚠️ [AnimatedContent] is keyed on the Boolean and NOT on [onClick]. The lambda is a new object on
 * every recomposition, so keying on it would restart the cross-fade on every frame that touched this
 * row, which is every frame a selection changes size.
 */
@Composable
fun GridlinkMenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** True while a selection is open: the hamburger becomes the close that ends it. */
    closing: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .size(GridlinkDimens.headerControl)
            .background(colors.surface.copy(alpha = 0.14f), CircleShape)
            .border(
                width = GridlinkDimens.hairline,
                color = colors.surfaceBorder.copy(alpha = 0.35f),
                shape = CircleShape,
            )
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = closing,
            // Cross-fade with no SizeTransform: both glyphs are 20dp inside a fixed 44dp circle, so
            // there is no size to animate and asking for one only risks the icon scaling inside a
            // container that is not moving.
            transitionSpec = {
                fadeIn(GridlinkMotion.standard()) togetherWith fadeOut(GridlinkMotion.standard())
            },
            label = "menuGlyph",
        ) { isClosing ->
            Icon(
                imageVector = if (isClosing) Icons.Outlined.Close else Icons.Outlined.Menu,
                contentDescription = if (isClosing) "Clear selection" else "Menu",
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Select all, in the chrome row's trailing seat while a selection is open.
 *
 * §6b: "a close control on the leading edge and select-all on the trailing edge". It wears the search
 * pill's collapsed treatment because it takes the search pill's actual seat — the inbox hides search
 * for the duration of a selection — and two different circles trading places in the same corner would
 * read as the header having been rebuilt rather than as one control changing job.
 *
 * ## 🔴 [all] fills it rather than dimming it
 * Everything-is-picked is an on/off state, and Brandon's on/off vocabulary is the accent fill, the
 * same one the active nav destination wears. ⚠️ Never express the off state by dropping the glyph's
 * alpha: he reads opacity-dimming as broken rather than as off, and has said so more than once.
 *
 * ## Why it toggles instead of going inert
 * With everything already ticked a pure "select all" has nothing left to do, and a control that
 * cannot act is worse in the corner than not being there. So the filled state clears instead, which
 * is not a second close button by accident: unticking the last row has always ended the selection
 * (see the list's row tap), so "none selected" already means "not selecting" everywhere else.
 */
@Composable
fun GridlinkSelectAllButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when every message on screen is already ticked. */
    all: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .size(GridlinkDimens.headerControl)
            .then(
                if (all) {
                    Modifier.background(gridlinkAccentFill(colors.accent), CircleShape)
                } else {
                    Modifier.background(colors.surface.copy(alpha = 0.14f), CircleShape)
                },
            )
            .border(
                width = GridlinkDimens.hairline,
                color = if (all) Color.Transparent else colors.surfaceBorder.copy(alpha = 0.35f),
                shape = CircleShape,
            )
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.DoneAll,
            contentDescription = if (all) "Clear selection" else "Select all",
            tint = if (all) colors.onAccent else colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Live connection state, as a dot and a word.
 *
 * The dot carries the state and the word confirms it, rather than the dot carrying it alone. A
 * coloured dot on its own is a thing you have to have learned; three of them differ only by hue, and
 * two of those hues are already spoken for elsewhere in the app.
 */
@Composable
fun GridlinkSyncChip(
    sync: GridlinkSyncState,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .height(SYNC_CHIP_HEIGHT)
            .background(colors.surface.copy(alpha = 0.14f), shape)
            .border(
                width = GridlinkDimens.hairline,
                color = colors.surfaceBorder.copy(alpha = 0.35f),
                shape = shape,
            )
            .padding(horizontal = GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(SYNC_DOT)
                .background(sync.dotColor(), CircleShape),
        )
        Text(
            text = sync.label,
            modifier = Modifier.padding(start = GridlinkSpacing.s8),
            style = GridlinkType.metadata,
            color = colors.textSecondary,
        )
    }
}

/**
 * What the menu sheet can take you to.
 *
 * Five, and they are no longer the whole drawer. These are the three mailboxes that are states rather
 * than places (a draft is a message you have not finished, a scheduled message is one you have not
 * sent, a snoozed one is mail you have not dealt with yet) plus the two rows that configure the app.
 *
 * ⚠️ This used to argue that listing mailboxes here "would be a second navigation system disagreeing
 * with the first one". Brandon settled that the other way: the mailboxes ARE the drawer now (see
 * [MenuFolders]) and the nav pill gave up its Folders seat, so there is still exactly one place to
 * go looking for a mailbox. The rows in this enum are what is left that is not one.
 *
 * 🔴 Declaration order is the render order WITHIN a group, and [MAILBOX_ITEMS] is what splits the
 * list. [SETTINGS] is drawn out of order, pinned at the top of the panel beside the appearance
 * control; everything else not named in that list falls into the group at the bottom by default
 * rather than disappearing, which is the failure mode worth designing against: a menu row that
 * compiles and is simply never drawn.
 */
enum class GridlinkMenuItem(
    val label: String,
    val icon: ImageVector,
    /** What a count under this row is counting. Null on rows that cannot have one. */
    val countNoun: String? = null,
) {
    SCHEDULED("Scheduled", Icons.Outlined.Schedule, countNoun = "waiting"),
    SNOOZED("Snoozed", Icons.Outlined.Snooze, countNoun = "waiting"),
    DRAFTS("Drafts", Icons.Outlined.Drafts, countNoun = "unsent"),
    ACCOUNTS("Accounts", Icons.Outlined.ManageAccounts),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/**
 * The rows that are mailboxes rather than places, drawn as their own group under the folder tree.
 *
 * ⚠️ This no longer means "above the appearance control". Appearance and Settings were pinned to the
 * top of the drawer on 2026-08-12, so this list is now only the split between the mailbox group and
 * the leftovers below it.
 *
 * Snoozed sits beside Scheduled because they are the same kind of thing (mail waiting on a clock)
 * and because it is otherwise unreachable: snoozed mail is hidden from every list by definition, so
 * this row is the only route to it in the app.
 */
private val MAILBOX_ITEMS =
    listOf(GridlinkMenuItem.SCHEDULED, GridlinkMenuItem.SNOOZED, GridlinkMenuItem.DRAFTS)

/**
 * ⚠️ Sample data, and the only piece of it that is hard-coded outside `GridlinkSample*`. A real
 * build reads the address off the JMAP session object. It lives here rather than in the sample files
 * because it is not sample *mail*, it is the one identity the whole prototype claims to be, and
 * every screenshot Brandon reviews has it in the menu.
 */
const val GRIDLINK_SAMPLE_ACCOUNT = "brandon@gridlink.me"

/**
 * Sample counts for the two mailboxes that carry one. Same status as [GRIDLINK_SAMPLE_ACCOUNT].
 *
 * 🔴 Drafts is COUNTED, not typed. It was written as a literal 4 while the sample had no drafts at
 * all, so the drawer advertised four unsent messages and the Drafts folder one tap away said
 * "Nothing in Drafts" — the app contradicting itself on two surfaces of the same screen, which is
 * the exact failure [GridlinkSampleFolders.unreadIn] exists to prevent for folder badges. Reading
 * the fixture means the row cannot claim mail that is not there.
 *
 * ⚠️ Scheduled is still a literal, and it is the one that is defensible: [gridlinkSampleScheduled]
 * builds its two rows against a `now` that only the composable has, so counting them here would
 * mean either passing a clock into a constant or freezing the times this menu is not allowed to
 * own. `GridlinkSampleMenuCountsTest` holds it to the fixture instead.
 */
val GRIDLINK_SAMPLE_MENU_COUNTS = mapOf(
    GridlinkMenuItem.SCHEDULED to 2,
    // Same literal, same defence as Scheduled: gridlinkSampleSnoozed builds its rows against a
    // `now` this constant does not have.
    GridlinkMenuItem.SNOOZED to 2,
    GridlinkMenuItem.DRAFTS to GridlinkSample.draftMessages.size,
)

/**
 * The panel behind the hamburger: who you are, when the mail last moved, the four things that are
 * not tabs, and the palette.
 *
 * 🔴 A full-height [GridlinkSlideOutPanel] off the leading edge, not a sheet. This came up from the
 * bottom for one round and Brandon rejected it on sight: "the hamburger menu should open a full top
 * to bottom slide out panel, not the current way it does from the bottom. clicking off it closes".
 * The container is where that lives; everything below is the same content it had as a sheet.
 *
 * ## Why the account line is a real row and not a title
 * A single-account client still has to say which account, because the first thing anyone does when
 * mail stops arriving is check they are looking at the right mailbox. The address answers that, and
 * the status line under it answers the follow-up, in the same colour as the chip in the chrome row
 * the panel was opened from, so the two are visibly the same fact.
 *
 * ## Why the last-synced time is on that line and not a row of its own
 * Brandon asked for a last-synced time in this menu. It is not a destination and it is not a
 * setting, so a row would have made it look like one; it is the second half of the sentence the
 * status line was already saying. "JMAP · Synced" answers "is it working", and "JMAP · Synced
 * 4 min ago" answers "is it working *now*", which is the question that was actually being asked.
 *
 * ## Why the mode control is here at all
 * 🔴 It used to be deliberately absent, on the reasoning that a theme control belongs in Settings
 * and Settings does not exist yet. Brandon overruled that directly: "put the mode pill inside the
 * hamburger menu somehow, your choice on how its selected". So it is here, as a segmented track
 * rather than the old floating pill, and it sits between the mailboxes and the two configuration
 * rows because that is what it is: a preference, not a place.
 *
 * [counts] and [accountCount] are display-only sample data; a real build reads these off the
 * mailboxes and the session.
 */
@Composable
fun GridlinkMenuPanel(
    account: String,
    sync: GridlinkSyncState,
    mode: GridlinkMode,
    followingClock: Boolean,
    onSelectMode: (GridlinkMode?) -> Unit,
    onSelect: (GridlinkMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    lastSyncedAt: Long? = null,
    counts: Map<GridlinkMenuItem, Int> = emptyMap(),
    accountCount: Int = 1,
    /**
     * The account's mailboxes, nested as the server nests them. Empty draws no folder group at all,
     * which is the honest state before anything has said what the mailboxes are.
     */
    folders: List<GridlinkFolder> = emptyList(),
    onSelectFolder: (GridlinkFolder) -> Unit = {},
    /** Opens the folder screen, the only place a mailbox is created, renamed or moved. */
    onManageFolders: () -> Unit = {},
    /**
     * The merged-inbox pair, or null to draw neither row.
     *
     * 🔴 Null is the ONE-account state and not a fallback. "All inboxes" over a single account is
     * that account's inbox with an account marker repeated down every row, so the row is absent
     * rather than present-and-pointless. See [GridlinkUnifiedInbox].
     */
    unified: GridlinkUnifiedInbox? = null,
    /** True merges every account's inbox, false returns to the bound account's. */
    onSelectUnified: (Boolean) -> Unit = {},
) {
    val colors = GridlinkTheme.colors
    // Frozen at the moment the sheet opens rather than read on every recomposition. The sheet is a
    // momentary object, so "4 min ago" cannot go stale while it is up, and picking a palette (which
    // recomposes everything in here) must not silently re-time the line underneath.
    val openedAt = remember { System.currentTimeMillis() }

    GridlinkSlideOutPanel(onDismiss = onDismiss, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = GridlinkSpacing.chrome,
                    end = GridlinkSpacing.chrome,
                    top = GridlinkSpacing.chrome,
                    bottom = GridlinkSpacing.s16,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.AlternateEmail,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.padding(start = GridlinkSpacing.s12)) {
                // Blank means nobody has told the chrome who is signed in, and the row says nothing
                // rather than reserving a line for an empty string. It cannot happen in a signed-in
                // build (the host always passes one) but the default is empty on purpose now, so
                // the failure of a caller to pass it must not look like a rendering glitch.
                if (account.isNotBlank()) {
                    Text(
                        text = account,
                        style = GridlinkType.senderName,
                        color = colors.textPrimary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(SYNC_DOT)
                            .background(sync.dotColor(), CircleShape),
                    )
                    Text(
                        // The protocol, not the server. Which host it is talking to belongs in
                        // Settings; whether it is talking JMAP at all is the thing that changes what
                        // the app can do, and it is the one line of this sheet a support question
                        // would ever ask for.
                        text = "JMAP · " + gridlinkSyncSentence(sync, lastSyncedAt, openedAt),
                        modifier = Modifier.padding(start = GridlinkSpacing.s8),
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        // 🔴 Directly under the account row, above everything else. Brandon, 2026-08-12: "pin
        // appearance, auto, and settings at the top of the sidebar below accounts." They used to sit
        // at the bottom, which was fine while the drawer held four rows and became wrong the moment
        // folders moved into it: a mailbox tree is as long as the account has mailboxes, so the two
        // controls that are not mail were pushed off the bottom of a scrolling panel on any real
        // account. The things that are always the same distance away belong where the drawer opens.
        GridlinkSheetDivider()
        GridlinkModeRow(
            mode = mode,
            followingClock = followingClock,
            onSelect = onSelectMode,
        )
        MenuRow(GridlinkMenuItem.SETTINGS, counts, accountCount, onSelect)

        if (unified != null || folders.isNotEmpty()) {
            GridlinkSheetDivider()
            if (unified != null) {
                MenuUnifiedRows(unified, folders, onSelectUnified)
            }
            // 🔴 The tree's own Inbox is suppressed while the pair above is drawn, for exactly the
            // reason Drafts is suppressed inside [MenuFolders]: the pair already offers that
            // mailbox, with a better subline and, unlike the tree row, landing on the mail list
            // instead of the folder screen. Two rows for one mailbox, disagreeing about where they
            // go, is worse than either alone.
            MenuFolders(folders, hideInbox = unified != null, onSelectFolder, onManageFolders)
        }

        GridlinkSheetDivider()
        MAILBOX_ITEMS.forEach { item -> MenuRow(item, counts, accountCount, onSelect) }

        // Whatever is left over, which today is Accounts alone. ⚠️ Written as a subtraction rather
        // than a literal list so a row added to [GridlinkMenuItem] still appears somewhere: the
        // failure mode this file already warns about is a menu row that compiles and is never drawn.
        GridlinkSheetDivider()
        GridlinkMenuItem.entries
            .filter { it !in MAILBOX_ITEMS && it != GridlinkMenuItem.SETTINGS }
            .forEach { item -> MenuRow(item, counts, accountCount, onSelect) }
    }
}

/**
 * Every mailbox, depth-first, above the two rows that are states rather than places.
 *
 * 🔴 This is the drawer's whole reason to be tall now. Brandon: "move all folders to the hamburger
 * menu to popup on slideout". They used to be a nav-pill tab over a management screen, which meant
 * the fastest route from the Inbox to a mailbox was two taps through a tree you first had to expand.
 * Here they are one tap from anywhere in the app, on every screen, because the drawer is app chrome.
 *
 * ## Why the tree is flattened rather than expandable
 * A drawer is a list you scan, not a thing you operate. Collapsible branches would mean the mailbox
 * you want is sometimes one tap away and sometimes three, depending on state left over from the last
 * time the drawer was open, and the indent already says everything a twisty would. The folder SCREEN
 * still expands and collapses, because that is where a tree is edited.
 *
 * ⚠️ Drafts is filtered out, and it is the one omission. It is right above this group as a menu row
 * carrying its unsent count, opening the very same mailbox by role, so listing it here would be the
 * drawer offering one mailbox twice, once with a better subline than the other.
 */
@Composable
private fun MenuFolders(
    folders: List<GridlinkFolder>,
    hideInbox: Boolean,
    onSelectFolder: (GridlinkFolder) -> Unit,
    onManageFolders: () -> Unit,
) {
    // Nothing to manage and nothing to list. The group is drawn for the unified pair alone in this
    // state, and a lone "Manage folders" under it would offer a screen with no mailboxes on it.
    if (folders.isEmpty()) return
    folders.forEach { folder ->
        MenuFolderRow(folder, depth = 0, hideInbox = hideInbox, onSelectFolder = onSelectFolder)
    }
    GridlinkSheetAction(
        label = "Manage folders",
        icon = Icons.Outlined.CreateNewFolder,
        // The only route to the folder screen now that the pill has no seat for it. Named for the
        // verb rather than the place ("Folders" would read as a sixth mailbox in a list of mailboxes).
        onClick = onManageFolders,
        tint = GridlinkTheme.colors.textSecondary,
    )
}

/**
 * What the drawer needs to draw the merged-inbox pair, and nothing more.
 *
 * 🔴 [unread] is a SUM across accounts, not the bound account's number, and that is the whole point
 * of the row: it is the only place in the app that answers "how much mail is waiting for me"
 * rather than "for this address". It is passed in rather than derived from `folders`, because the
 * folder tree the drawer holds belongs to one account and cannot know about the others.
 *
 * [accountLabel] is the bound account, shown under the Inbox row so the way back names where it
 * goes. Blank draws no subline rather than an empty one.
 */
@Immutable
data class GridlinkUnifiedInbox(
    val active: Boolean,
    val unread: Int,
    val accountLabel: String,
)

/**
 * "All inboxes" and the way back, as one pair above the folder tree.
 *
 * ## Why two rows and not a switch
 * Brandon settled the placement directly: an All inboxes row at the top of the drawer with the
 * combined unread on it, tapped to merge, and Inbox tapped to come back. A switch in Settings would
 * have put the control two screens away from the list it changes, and a mode you can only leave by
 * going to Settings is a mode you get stuck in.
 *
 * ## Why the active row is tinted rather than dimmed or badged
 * [accent] owns selection everywhere else in the app, and the app's rule is that opacity may never
 * carry on/off. So the row you are in is drawn in the accent and the other in the ordinary ink; no
 * new state, no third style, and nothing that reads as disabled.
 *
 * ⚠️ Both rows stay tappable in both states. Tapping the row you are already in re-states a fact,
 * which is harmless, and disabling it would mean the drawer sometimes has a dead row where it
 * usually has a control.
 */
@Composable
private fun MenuUnifiedRows(
    unified: GridlinkUnifiedInbox,
    folders: List<GridlinkFolder>,
    onSelectUnified: (Boolean) -> Unit,
) {
    val colors = GridlinkTheme.colors
    GridlinkSheetAction(
        label = "All inboxes",
        icon = Icons.Outlined.AllInbox,
        // Counted, absent at zero, exactly as the folder rows do it. See [MenuFolderRow].
        subline = unified.unread.takeIf { it > 0 }?.let { "$it unread" },
        onClick = { onSelectUnified(true) },
        tint = if (unified.active) colors.accent else null,
    )
    GridlinkSheetAction(
        label = "Inbox",
        icon = Icons.Outlined.Inbox,
        // The account, not a count. The number belongs to the row above (it is the sum) and the
        // tree's own Inbox is suppressed while this pair is up, so what this row is missing is not
        // "how much" but "whose" — which is the only thing that is ambiguous once accounts merge.
        subline = unified.accountLabel.takeIf { it.isNotBlank() },
        onClick = { onSelectUnified(false) },
        tint = if (unified.active) null else colors.accent,
    )
}

/** One mailbox and everything under it. Recursive, so the indent is the depth and nothing tracks it. */
@Composable
private fun MenuFolderRow(
    folder: GridlinkFolder,
    depth: Int,
    hideInbox: Boolean,
    onSelectFolder: (GridlinkFolder) -> Unit,
) {
    val suppressed = folder.role == GridlinkFolderRole.DRAFTS ||
        (hideInbox && folder.role == GridlinkFolderRole.INBOX)
    if (!suppressed) {
        GridlinkSheetAction(
            label = folder.name,
            icon = folder.role.icon(),
            // Counted, never a bare dot, for [MenuRow]'s reason: a number is a fact and a dot is a
            // question. Absent at zero rather than "0 unread", which is a claim nobody asked for.
            subline = folder.unread.takeIf { it > 0 }?.let { "$it unread" },
            onClick = { onSelectFolder(folder) },
            indent = FOLDER_INDENT * depth,
        )
    }
    folder.children.forEach { child ->
        // 🔴 The children of a filtered-out Drafts still draw, at the depth Drafts would have given
        // them. A server that nests mailboxes under the drafts role is unusual and not forbidden,
        // and silently dropping a whole branch is not something a drawer may do.
        MenuFolderRow(child, depth = depth + 1, hideInbox = hideInbox, onSelectFolder = onSelectFolder)
    }
}

/** How far one level of nesting shifts a mailbox row. Half the row's own leading pad: legible, cheap. */
private val FOLDER_INDENT = 16.dp

@Composable
private fun MenuRow(
    item: GridlinkMenuItem,
    counts: Map<GridlinkMenuItem, Int>,
    accountCount: Int,
    onSelect: (GridlinkMenuItem) -> Unit,
) {
    GridlinkSheetAction(
        label = item.label,
        icon = item.icon,
        // Counted, never a bare badge. "3 waiting" is a fact; a dot on Scheduled is a question you
        // have to open the screen to answer. Accounts counts itself rather than carrying a noun,
        // because "1 accounts" is the kind of thing that ships.
        subline = when (item) {
            GridlinkMenuItem.ACCOUNTS -> if (accountCount == 1) "1 account" else "$accountCount accounts"
            else -> item.countNoun?.let { noun ->
                counts[item]?.takeIf { it > 0 }?.let { "$it $noun" }
            }
        },
        onClick = { onSelect(item) },
    )
}

/**
 * Turns the connection state and the last successful sync into one readable line.
 *
 * 🔴 Offline keeps the timestamp and Syncing drops it. Those are the two cases that matter: an
 * offline mailbox is exactly when you need to know how old what you are looking at is, and a sync
 * in flight is exactly when the previous timestamp is about to be wrong. Stating "Synced 4 min ago"
 * during a sync would be true for another second and misleading for the rest of the day it stayed
 * on screen after the sync failed.
 *
 * Shared with [GridlinkEmptyInbox], which asks the same question of the same state and must not
 * answer it in different words. That is also why [now] is a parameter: the drawer freezes it at the
 * moment it opens, the empty state ticks it, and neither of those belongs in here.
 */
internal fun gridlinkSyncSentence(sync: GridlinkSyncState, lastSyncedAt: Long?, now: Long): String {
    val age = lastSyncedAt?.let { gridlinkSyncAge(now - it) }
    return when (sync) {
        GridlinkSyncState.SYNCING -> "Syncing all accounts"
        GridlinkSyncState.SYNCED -> if (age == null) "Synced" else "Synced $age"
        GridlinkSyncState.OFFLINE -> if (age == null) "Offline · never synced" else "Offline · synced $age"
    }
}

/**
 * Coarse relative age, and coarse on purpose. This line is read to answer "is my mail current",
 * which needs a magnitude and not a duration: "2 hr ago" and "2 hr 14 min ago" lead to the same
 * decision and only one of them is short enough to sit under an email address.
 */
internal fun gridlinkSyncAge(elapsedMs: Long): String {
    val minutes = elapsedMs / 60_000L
    return when {
        // Negative is possible: the stamp is a wall clock and the wall clock can move backwards.
        // "just now" is the honest answer to a sync in the future, and it beats "-3 min ago".
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 60L * 24L -> "${minutes / 60L} hr ago"
        else -> "${minutes / (60L * 24L)} d ago"
    }
}

/** Padding inside the segmented track, so the active segment's pill clears the track's own edge. */
private val MODE_TRACK_PAD = 4.dp

/**
 * Day / Night / OLED, plus Auto, as one segmented track across the sheet.
 *
 * ## Why a segmented track and not a row that opens a picker
 * This is a control you use by looking at the result, and every extra screen between the tap and the
 * repaint is a screen covering the thing you are trying to judge. All four choices are on one line
 * and the sheet stays open while you try them, so the palette changes *under* the sheet and you can
 * see it happen. A picker would have been two taps to see one colour.
 *
 * ## Why "Auto" is a segment and its resolved mode is a readout
 * The four segments are equal width, which leaves no room for the old pill's "Auto · Day" label.
 * Splitting it works out better than it sounds: the segment says what the setting IS and the line
 * above says what it currently RESOLVES to, which were always two facts sharing one chip. The
 * readout only appears while Auto is on, because with a mode picked the lit segment already says it
 * and repeating it would invite the eye to compare two things that cannot disagree.
 *
 * ⚠️ Inactive segments are not dimmed. The app's rule is that opacity must never carry on/off, so
 * the active segment is marked by a filled pill and everything else keeps full-strength secondary
 * text.
 */
@Composable
private fun GridlinkModeRow(
    mode: GridlinkMode,
    followingClock: Boolean,
    onSelect: (GridlinkMode?) -> Unit,
) {
    val colors = GridlinkTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GridlinkSpacing.chrome, vertical = GridlinkSpacing.s12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Brightness4,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Appearance",
                modifier = Modifier
                    .padding(start = GridlinkSpacing.s16)
                    .weight(1f),
                style = GridlinkType.senderName,
                color = colors.textPrimary,
            )
            if (followingClock) {
                Text(
                    text = "Auto · ${mode.label}",
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(GridlinkSpacing.s12))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Indented to the same line the row labels start on, so the track reads as this
                // row's content rather than as a band across the sheet.
                .padding(start = 20.dp + GridlinkSpacing.s16)
                .background(colors.surface.copy(alpha = 0.14f), MODE_TRACK_SHAPE)
                .border(
                    width = GridlinkDimens.hairline,
                    color = colors.surfaceBorder.copy(alpha = 0.35f),
                    shape = MODE_TRACK_SHAPE,
                )
                .padding(MODE_TRACK_PAD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeChoice(
                label = "Auto",
                active = followingClock,
                onClick = { onSelect(null) },
                modifier = Modifier.weight(1f),
            )
            GridlinkMode.entries.forEach { candidate ->
                ModeChoice(
                    label = candidate.label,
                    active = !followingClock && candidate == mode,
                    onClick = { onSelect(candidate) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private val MODE_TRACK_SHAPE = RoundedCornerShape(GridlinkRadii.pill)

/** How the three palettes name themselves anywhere a user can see them. */
private val GridlinkMode.label: String
    get() = when (this) {
        GridlinkMode.DAY -> "Day"
        GridlinkMode.NIGHT -> "Night"
        GridlinkMode.OLED -> "OLED"
    }

@Composable
private fun ModeChoice(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .background(
                color = if (active) colors.accent else Color.Transparent,
                shape = MODE_TRACK_SHAPE,
            )
            .clip(MODE_TRACK_SHAPE)
            .clickable(onClick = onClick)
            .padding(vertical = GridlinkSpacing.s8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = GridlinkType.toolbarLabel,
            color = if (active) colors.onAccent else colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
