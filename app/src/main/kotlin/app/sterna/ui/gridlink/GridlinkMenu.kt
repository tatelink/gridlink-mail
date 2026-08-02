package app.sterna.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkColors
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType

/**
 * The app's own row of chrome, above every screen title, and the sheet the menu opens.
 *
 * ## Why a whole row rather than a fifth thing in the title row
 * The design handoff put the menu control up for a vote and this is the option that won: "a
 * dedicated chrome row above the 32sp title, menu on the leading edge, live sync state on the
 * trailing edge. The title keeps its full measure, the menu lands under the left thumb, and the row
 * gives the sync indicator a home it does not currently have." Brandon picked it in the same words,
 * "hamburger menu top left above inbox area".
 *
 * The alternative was pairing it with the search pill on the title row's trailing edge, which costs
 * no height. It was rejected for two reasons that both cost more than the height does: a 32sp
 * ExtraBold title next to two 44dp circles has nowhere to wrap, and a menu on the trailing edge of a
 * folded Fold is the one corner a right thumb has to stretch for.
 *
 * ## Why it lives in the scaffold and not in [GridlinkHeader]
 * The header belongs to a screen and says what that screen is. This row belongs to the app and says
 * nothing about where you are: the same menu and the same sync state on all four tabs. Putting it in
 * the header would mean four screens each passing the same two arguments, and the first one to
 * forget would silently lose the app's only route to Settings.
 *
 * 🔴 [GridlinkHeader]'s top padding was cut from 40 to 16 when this landed. Every Gridlink screen
 * goes through [GridlinkScaffold], so the header now never renders without this row above it, and
 * keeping the old 40 stacked two lots of breathing room into 104dp of empty glass before the title.
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

/**
 * App-level chrome state: what the sync chip says, and whether the menu sheet starts open.
 *
 * 🔴 A CompositionLocal and not a parameter, because the alternative is four screens accepting two
 * arguments they never read and forwarding them to [GridlinkScaffold] verbatim. That kind of
 * pass-through survives exactly until someone adds a fifth screen, and what it fails at is silent:
 * the new screen renders a hard-coded "Synced" and nobody notices until the day the server is down.
 *
 * [sync] is a real app fact and a real build will provide it from the JMAP session.
 * [menuOpenAtStart] is a harness affordance, so a launch can screenshot the sheet without a tap; it
 * seeds the state and does not pin it, so dismissing the sheet actually dismisses it.
 */
@Immutable
data class GridlinkChromeState(
    val sync: GridlinkSyncState = GridlinkSyncState.SYNCED,
    val menuOpenAtStart: Boolean = false,
)

val LocalGridlinkChrome = staticCompositionLocalOf { GridlinkChromeState() }

/** The dot in front of the sync label. Small enough to be punctuation rather than a badge. */
private val SYNC_DOT = 8.dp

/** Height of the sync chip. Well under the 44dp circle beside it, because it is a readout. */
private val SYNC_CHIP_HEIGHT = 28.dp

/**
 * Menu on the left, sync state on the right, nothing in between.
 *
 * The two ends are deliberately different kinds of object: a 44dp circle is a control and reads as
 * one, a 28dp chip with no press state is a readout and reads as one. Making the sync chip tappable
 * would be inventing a behaviour the design does not specify, and the state it would have to open is
 * already the first line of the menu sheet.
 */
@Composable
fun GridlinkChromeRow(
    onOpenMenu: () -> Unit,
    sync: GridlinkSyncState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = GridlinkSpacing.chrome,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        GridlinkMenuButton(onClick = onOpenMenu)
        GridlinkSyncChip(sync = sync)
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
 */
@Composable
fun GridlinkMenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
        Icon(
            imageVector = Icons.Outlined.Menu,
            contentDescription = "Menu",
            tint = colors.textSecondary,
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
 * Three, and deliberately not more. Everything that is a *place mail lives* is already in the nav
 * pill; what is left over are the two mailboxes that are states rather than places (a draft is a
 * message you have not finished, a scheduled message is one you have not sent) plus Settings. A
 * menu that also listed Inbox and Folders would be a second navigation system disagreeing with the
 * first one about which of them is the way around the app.
 */
enum class GridlinkMenuItem(
    val label: String,
    val icon: ImageVector,
    /** What a count under this row is counting. Null on rows that cannot have one. */
    val countNoun: String? = null,
) {
    SCHEDULED("Scheduled", Icons.Outlined.Schedule, countNoun = "waiting"),
    DRAFTS("Drafts", Icons.Outlined.Drafts, countNoun = "unsent"),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/**
 * ⚠️ Sample data, and the only piece of it that is hard-coded outside `GridlinkSample*`. A real
 * build reads the address off the JMAP session object. It lives here rather than in the sample files
 * because it is not sample *mail*, it is the one identity the whole prototype claims to be, and
 * every screenshot Brandon reviews has it in the menu.
 */
const val GRIDLINK_SAMPLE_ACCOUNT = "brandon@gridlink.me"

/**
 * Sample counts for the two mailboxes that carry one. Same status as [GRIDLINK_SAMPLE_ACCOUNT].
 */
val GRIDLINK_SAMPLE_MENU_COUNTS = mapOf(
    GridlinkMenuItem.SCHEDULED to 2,
    GridlinkMenuItem.DRAFTS to 4,
)

/**
 * The sheet behind the hamburger: who you are, and the three things that are not tabs.
 *
 * ## Why the account line is a real row and not a title
 * A single-account client still has to say which account, because the first thing anyone does when
 * mail stops arriving is check they are looking at the right mailbox. The address answers that, and
 * the status line under it answers the follow-up, in the same colour as the chip in the chrome row
 * the sheet was opened from, so the two are visibly the same fact.
 *
 * ⚠️ The design also puts the Day / Night / OLED mode pill in this sheet and it is NOT here. Brandon
 * asked for that control to be hidden "to live in settings later", and the mode override currently
 * lives in the gallery harness rather than in the app, so wiring it in would mean giving production
 * a theme control before the screen that is supposed to own it exists. Settings is one row below.
 *
 * [counts] is display-only sample data; a real build reads these off the mailboxes.
 */
@Composable
fun GridlinkMenuSheet(
    account: String,
    sync: GridlinkSyncState,
    onSelect: (GridlinkMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    counts: Map<GridlinkMenuItem, Int> = emptyMap(),
) {
    val colors = GridlinkTheme.colors
    GridlinkBottomSheet(onDismiss = onDismiss, modifier = modifier) {
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
                Text(
                    text = account,
                    style = GridlinkType.senderName,
                    color = colors.textPrimary,
                )
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
                        text = "JMAP · ${sync.label}",
                        modifier = Modifier.padding(start = GridlinkSpacing.s8),
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                    )
                }
            }
        }
        GridlinkSheetDivider()
        GridlinkMenuItem.entries.forEach { item ->
            if (item == GridlinkMenuItem.SETTINGS) GridlinkSheetDivider()
            GridlinkSheetAction(
                label = item.label,
                icon = item.icon,
                // Counted, never a bare badge. "3 waiting" is a fact; a dot on Scheduled is a
                // question you have to open the screen to answer.
                subline = item.countNoun?.let { noun ->
                    counts[item]?.takeIf { it > 0 }?.let { "$it $noun" }
                },
                onClick = { onSelect(item) },
            )
        }
        GridlinkSheetFooterSpace()
    }
}
