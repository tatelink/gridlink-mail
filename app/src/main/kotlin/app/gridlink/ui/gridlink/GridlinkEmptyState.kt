package app.gridlink.ui.gridlink

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import kotlinx.coroutines.delay

/**
 * §1g: what the mail list shows when there is no mail in it.
 *
 * ## Why this exists, and why it is a control rather than a picture
 * 🔴 It is the other half of the pull gesture. Brandon scoped pull-to-refresh to lists that have
 * something in them ("only IF mail is present, however otherwise theres nothing to pull down"),
 * which is right as a gesture and leaves a hole as a product: an empty inbox is exactly the state
 * where you most want to force a check, and it was the one state with no way to. So the refresh
 * moved into the empty state, on his instruction: "create an empty inbox then, with a beautiful
 * gridlink logo in the center click to refresh".
 *
 * Before this, an emptied list did not go blank, it went *broken*: four section labels (AUTOMATED,
 * TODAY, YESTERDAY, EARLIER) floating one under another with no rows beneath them. Section headings
 * for sections that do not exist. This replaces the list outright rather than filtering the labels,
 * because a list with nothing in it should not be a list.
 *
 * ## Why the mark is the button
 * A logo you tap is not a standard affordance and normally I would not invent one. Two things make
 * it work here. It is the only object on the screen, so there is nothing else a tap could plausibly
 * mean; and the mark's own metaphor is a single lit node, which gives the action somewhere honest to
 * put its feedback. Tapping it makes the node burn brighter. The subline still says so in words,
 * because "the only object on screen" is an argument about layout and not about discoverability.
 *
 * ## What the subline is for
 * The real question behind a blank inbox is never "is it empty", it is **"is it empty, or is it
 * broken"**. So the subline is the same sentence the drawer shows, sync state plus how long ago mail
 * last moved, and the affordance is glued onto the end of it. "Synced 4 min ago" and "Offline ·
 * synced 2 hr ago" are different situations and this is the screen where the difference matters.
 */

/**
 * How often the age in the subline is recomputed.
 *
 * The empty inbox is the one screen a person leaves open, so a frozen "Synced 4 min ago" would
 * quietly become a lie. 60s matches [gridlinkSyncAge]'s own finest granularity exactly: it never
 * ticks without the string being able to change, and it never lets the string be more than a minute
 * behind. This is a clock, not an animation.
 */
private const val EMPTY_AGE_TICK_MS = 60_000L

/** How far the whole group shrinks under the finger. Small: it is confirmation, not a squash. */
private const val EMPTY_PRESS_SCALE = 0.97f

/** Gap between the mark and the words. Wider than [GridlinkSpacing] goes, and it needs to be. */
private val EMPTY_MARK_GAP = 32.dp

@Composable
fun GridlinkEmptyInbox(
    sync: GridlinkSyncState,
    lastSyncedAt: Long?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    headline: String = "Nothing to read",
) {
    val colors = GridlinkTheme.colors
    val syncing = sync == GridlinkSyncState.SYNCING

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Re-keyed on the stamp as well as ticking, so a sync that completes updates the line on the
    // frame it completes rather than up to a minute later.
    LaunchedEffect(lastSyncedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(EMPTY_AGE_TICK_MS)
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) EMPTY_PRESS_SCALE else 1f,
        animationSpec = GridlinkMotion.standard(),
        label = "emptyPress",
    )
    val glow by animateFloatAsState(
        targetValue = if (syncing) 1f else 0f,
        animationSpec = GridlinkMotion.standard(),
        label = "emptyGlow",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // The whole group, not just the mark. The mark is what reads as the button, but a 96dp
            // target in the middle of an otherwise empty panel is a small thing to ask a thumb to
            // find, and there is nothing else here for a tap to hit.
            //
            // 🔴 No indication. A ripple across a panel this size is a flash, not a press state, and
            // the mark is a piece of artwork rather than a surface for one to spread across. The
            // scale below is the press state, and it is on the group so the words move with it.
            .clickable(
                interactionSource = interaction,
                indication = null,
                // Re-entrant syncs are dropped by the state holder anyway, but stopping the press
                // state from firing at all while one is in flight is the difference between a
                // control that is busy and one that looks unresponsive.
                enabled = !syncing,
                onClick = onRefresh,
            )
            .padding(GridlinkSpacing.chrome),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GridlinkMark(glow = glow)
            Text(
                text = headline,
                modifier = Modifier.padding(top = EMPTY_MARK_GAP),
                style = GridlinkType.threadTitle,
                color = colors.textPrimary,
            )
            Text(
                text = buildAnnotatedString {
                    append(gridlinkSyncSentence(sync, lastSyncedAt, now))
                    if (!syncing) {
                        append(" · ")
                        // Accent on the verb only. It is the one word here that describes something
                        // that will happen if you touch the screen, and colouring the whole line
                        // would make the status read as tappable too.
                        withStyle(SpanStyle(color = colors.accent)) {
                            append(
                                if (sync == GridlinkSyncState.OFFLINE) "tap to retry" else "tap to check",
                            )
                        }
                    }
                },
                modifier = Modifier.padding(top = GridlinkSpacing.s8),
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * What the list shows when the quick filters have narrowed it to nothing.
 *
 * ## 🔴 Why this is not [GridlinkEmptyInbox] with a different headline
 * They are answers to different questions and only one of them is about the server. "Nothing to
 * read · tap to check" tells you the mailbox is empty and offers a sync, and over a filtered list
 * every word of that is wrong: the inbox is full, the sync will change nothing, and the thing
 * standing between you and your mail is three chips you tapped a second ago. Worse, the tap does
 * the one thing that cannot help, so the screen would be actively steering you away from the fix.
 *
 * So this state names the cause and its whole affordance is the way out. Same mark, same press
 * scale, same shape on screen — it is the same kind of moment — with the accented verb pointing at
 * the filters instead of at the network.
 *
 * [summary] is the lit chips in words, because "no unread mail with attachments" and "no unread
 * mail" are different facts and the chips have scrolled out of mind by the time you read this.
 */
@Composable
fun GridlinkEmptyFiltered(
    summary: String,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) EMPTY_PRESS_SCALE else 1f,
        animationSpec = GridlinkMotion.standard(),
        label = "filteredPress",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // Same reasoning as the empty inbox: the whole group is the target, and no ripple.
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClearFilters,
            )
            .padding(GridlinkSpacing.chrome),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Unlit. The glow means "a sync is running", and nothing is running here.
            GridlinkMark(glow = 0f)
            Text(
                text = "Nothing matches",
                modifier = Modifier.padding(top = EMPTY_MARK_GAP),
                style = GridlinkType.threadTitle,
                color = colors.textPrimary,
            )
            Text(
                text = buildAnnotatedString {
                    append(summary)
                    append(" · ")
                    withStyle(SpanStyle(color = colors.accent)) {
                        append("tap to clear")
                    }
                },
                modifier = Modifier.padding(top = GridlinkSpacing.s8),
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
