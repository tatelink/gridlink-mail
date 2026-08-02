package app.sterna.ui.gridlink

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.sterna.ui.theme.GridlinkMode
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme

/**
 * Screen 1 and 2 of the brief: the message list, mixed read and unread, with the automated-sender
 * bundle collapsed and expanded.
 *
 * ## Why the robots sit above the timeline instead of inside it
 * §5 says the list must separate people from robots, and the brief's own bundle mock reads
 * "14 new" against a content sample of six. Threading one bundle per day heading would fragment
 * that count into meaningless pieces and put a "Reports" row in three places. Hoisting a single
 * bundle above the timeline instead leaves the timeline as what the user came for: the four
 * messages a human wrote. Expanding it pushes the robots in below, indented, without disturbing
 * anything under it.
 *
 * ## Why the header does not scroll
 * §3 assigns the gradient and the glow to the header and says the list scrolls on a flat surface,
 * which is only true if they are separate layers. Making the header the list's first row put the
 * whole list on the Day gradient, which cost about half the contrast of the dark body text. The
 * trade is real and worth stating: a fixed header costs roughly one visible row against the
 * brief's target of 13 on a folded screen.
 *
 * ## What is deliberately absent
 * No pull-to-refresh (JMAP pushes; the gesture would be theatre), no FAB (compose is in the nav
 * pill), no snippet, no avatars, no card. All four are §9 anti-requirements.
 */
@Composable
fun GridlinkMessageListScreen(
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    onOpenMessage: (GridlinkMessage) -> Unit = {},
) {
    var bundleExpanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    var destination by rememberSaveable { mutableStateOf(GridlinkDestination.INBOX) }
    val listState = rememberLazyListState()

    val bundle = remember { GridlinkSample.reportsBundle }
    val humans = remember { GridlinkSample.humanMessages }
    val needsYou = remember { humans.count { it.unread } }

    GridlinkBackground(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Column(Modifier.fillMaxSize()) {
                GridlinkHeader(
                    title = "Inbox",
                    needsYou = needsYou,
                    reports = bundle.unreadCount,
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(
                            color = gridlinkListSurface(),
                            // The only rounded corners in the dense half of the app, and they are
                            // on the container rather than on any row: it marks where spacious
                            // chrome stops and the list begins. Rows stay square and hairlined.
                            shape = RoundedCornerShape(
                                topStart = GridlinkRadii.card,
                                topEnd = GridlinkRadii.card,
                            ),
                        ),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Rows scroll UNDER the floating pill by design; this only guarantees the
                        // last one can clear it.
                        contentPadding = PaddingValues(bottom = GRIDLINK_PILL_CLEARANCE),
                    ) {
                        item(key = "label-automated") {
                            GridlinkSectionLabel(GridlinkSection.AUTOMATED.label)
                        }
                        item(key = "bundle") {
                            Column {
                                GridlinkBundleRow(
                                    bundle = bundle,
                                    expanded = bundleExpanded,
                                    onToggle = { bundleExpanded = !bundleExpanded },
                                )
                                GridlinkRowDivider()
                            }
                        }
                        item(key = "bundle-children") {
                            // The outer Column is not decoration: expandVertically resolves to the
                            // ColumnScope overload, and a LazyColumn item is not one.
                            Column {
                                // One AnimatedVisibility around the whole group, so expanding reads
                                // as the bundle opening rather than as six rows arriving in turn.
                                AnimatedVisibility(
                                    visible = bundleExpanded,
                                    enter = expandVertically(
                                        animationSpec = GridlinkMotion.standard(),
                                    ) + fadeIn(),
                                    exit = shrinkVertically(
                                        animationSpec = GridlinkMotion.rowCollapse(),
                                    ) + fadeOut(),
                                ) {
                                    Column {
                                        bundle.messages.forEach { child ->
                                            GridlinkBundledChildRow(
                                                message = child,
                                                onClick = { onOpenMessage(child) },
                                            )
                                            GridlinkRowDivider(
                                                startInset = GridlinkSpacing.bundleIndent +
                                                    GridlinkSpacing.rowHorizontal,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // The timeline: people only.
                        GridlinkSection.entries
                            .filter { it != GridlinkSection.AUTOMATED }
                            .forEach { section ->
                                val inSection = humans.filter { it.section == section }
                                if (inSection.isEmpty()) return@forEach
                                item(key = "label-${section.name}") {
                                    GridlinkSectionLabel(section.label)
                                }
                                items(
                                    count = inSection.size,
                                    key = { index -> inSection[index].id },
                                ) { index ->
                                    val message = inSection[index]
                                    Column {
                                        GridlinkMessageRow(
                                            message = message,
                                            onClick = { onOpenMessage(message) },
                                        )
                                        GridlinkRowDivider()
                                    }
                                }
                            }
                    }
                }
            }

            GridlinkNavPill(
                selected = destination,
                onSelect = { destination = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = GridlinkSpacing.chrome,
                        end = GridlinkSpacing.chrome,
                        bottom = GridlinkSpacing.chrome,
                    ),
            )
        }
    }
}

/**
 * The flat fill the list scrolls on.
 *
 * Day is the one mode where this does real work: the translucent white surface composites over the
 * gradient exactly once, statically, which is what makes near-black body text legible without the
 * live blur §9 forbids. Night and OLED are already flat, so this returns their background and the
 * seam is invisible.
 */
@Composable
private fun gridlinkListSurface(): Color {
    val colors = GridlinkTheme.colors
    return when (GridlinkTheme.mode) {
        GridlinkMode.DAY -> colors.surface
        GridlinkMode.NIGHT, GridlinkMode.OLED -> colors.background
    }
}
