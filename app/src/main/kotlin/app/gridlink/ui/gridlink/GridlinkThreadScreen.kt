package app.gridlink.ui.gridlink

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Unsubscribe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.core.data.settings.ThreadToolbarAction
import app.gridlink.ui.emailhtml.EmailRemoteContent
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMode
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import app.gridlink.ui.theme.gridlinkSenderBarColor

/**
 * Reading one message. Brief deliverable §11.5.
 *
 * ## Why this is not a [GridlinkScaffold]
 * Same reason [GridlinkComposeScreen] is not: the scaffold's job is one of the four peer
 * destinations, and it carries the chrome row and the nav pill that say so. A thread is somewhere
 * you went *from* the inbox and come back out of, so it takes the whole window and offers exactly
 * one way out. What it must not do is look like a different app, so it copies the scaffold's
 * metrics exactly: the same `chrome` pad down both edges, the same 28dp glass panel filling the
 * remaining height, and the same 64dp control baseline underneath it.
 *
 * ## The animation lives in [GridlinkRoot], not here
 * This composable draws a thread at rest and knows nothing about how it arrived. The slide, the
 * parallax on the list behind it and the back gesture are all one shared 0..1 value owned by the
 * root, because the entrance and the drag out have to be the same number read forwards and
 * backwards. Putting any of it here would give the gesture a second source of truth.
 *
 * ## ⚠️ §7's shared element morph is deferred
 * The brief asks for the tapped row's colour bar and sender line to morph into the thread header.
 * What lands here instead is a plain horizontal push, with continuity carried by the identity bar:
 * the same [GridlinkDimens.senderBarWidth] in the same domain colour is the first thing in the
 * panel, so the eye has something to follow even though nothing literally travels. A real morph
 * needs `SharedTransitionLayout` driven by a `SeekableTransitionState` (so the back gesture can
 * scrub it), and interpolating between two text sizes means re-laying-out the text every frame.
 * That is a real piece of work and it is worth doing, but it is worth doing on its own, not
 * smuggled into the screen that has to exist first. Same trade already recorded for the composer's
 * send button.
 */
@Composable
fun GridlinkThreadScreen(
    message: GridlinkMessage,
    onBack: () -> Unit,
    onAction: (GridlinkThreadAction) -> Unit,
    modifier: Modifier = Modifier,
    initiallyConfirmingUnsubscribe: Boolean = false,
    /**
     * True when this is §7's reading pane rather than a screen over the list. Forwarded straight to
     * [GridlinkDetailFrame], which is where what it changes is written down.
     */
    embedded: Boolean = false,
    /**
     * Whether this sender is on the standing images allowlist.
     *
     * 🔴 Passed in rather than read here. The list lives in the app's settings store, and nothing in
     * this package is allowed to know that a settings store exists — the same rule that keeps the
     * debug gallery drawing every screen with no account and no database. Defaulting to false is
     * also the right default for a preview: blocked.
     */
    imagesAlwaysAllowed: Boolean = false,
    /** Add or remove this sender from that list. No-op by default, for the gallery. */
    onAlwaysAllowImages: (allowed: Boolean) -> Unit = {},
    /**
     * Open the tapped attachment in a viewer. 🔴 Null, the default, keeps the chips as the plain
     * labels the gallery has always drawn — see [GridlinkRoot]'s parameter of the same name for why
     * this is nullable where the screen's other callbacks are no-ops.
     */
    onOpenAttachment: ((GridlinkAttachment) -> Unit)? = null,
    /**
     * Keep the tapped attachment somewhere the phone's file manager can find it. Nullable for
     * [onOpenAttachment]'s reason: null draws no save button at all, rather than one that cannot
     * save anything.
     */
    onSaveAttachment: ((GridlinkAttachment) -> Unit)? = null,
    /** One line under the chips about the download in flight, or null — which is almost always. */
    attachmentStatus: String? = null,
    /**
     * Which actions the reader put on the bottom bar.
     *
     * 🔴 Passed in for [imagesAlwaysAllowed]'s reason and it is the same rule: nothing in this
     * package knows a settings store exists, so this arrives as a plain value and the default is
     * [ThreadToolbarAction.DEFAULTS] — what the bar held before it was customisable, which is what
     * the debug gallery and every preview should draw.
     */
    toolbarActions: Set<ThreadToolbarAction> = ThreadToolbarAction.DEFAULTS,
    /**
     * Put a tag on this message, or take it off.
     *
     * 🔴 Nullable, and null hides the Tags row in the More sheet entirely — [onOpenAttachment]'s
     * rule. The debug gallery has no account and no repository, so a picker there could tick a box
     * and change nothing; an action that cannot act should not be offered. Reading the tags is
     * always on, because that half needs nothing but the message.
     */
    onSetTag: ((keyword: String, applied: Boolean) -> Unit)? = null,
    /** Open the tag manager in Settings. Null in the gallery, where Settings is not reachable. */
    onManageTags: (() -> Unit)? = null,
    /**
     * The meeting invitation this message carries, or null for mail that carries none — which is
     * almost all of it, and which draws no card and costs no space.
     *
     * 🔴 A finished [GridlinkInvite], not a parsed .ics: the loader converts, for the package rule
     * that keeps the calendar layer out of here. See [GridlinkInviteCard].
     */
    invite: GridlinkInvite? = null,
    /** Send an RSVP (ACCEPTED / TENTATIVE / DECLINED). Null draws no buttons — [onOpenAttachment]'s rule. */
    onRespondToInvite: ((partstat: String) -> Unit)? = null,
    /** Hand the event to the phone's calendar app. Null draws no button. */
    onAddToCalendar: (() -> Boolean)? = null,
    /** Hand the raw .ics to whatever can open it, for an invitation this app could not read. */
    onOpenInvitation: (() -> Unit)? = null,
    /** The sender's read-receipt request, or null. See [GridlinkReceiptRow]: never answered for them. */
    receipt: GridlinkReceipt? = null,
    /** Send the receipt. Null draws no button, so the gallery cannot send mail from no account. */
    onSendReceipt: (() -> Unit)? = null,
    /** This message's S/MIME verdict, or null for mail carrying no signature. See [GridlinkSignedRow]. */
    signed: GridlinkSigned? = null,
    /**
     * True while this message has the whole display: no list beside it, no chrome above it, and no
     * system bars. Brandon asked for a maximize that "blows up the email to literally full screen",
     * in BOTH the folded and the unfolded layout, which is why this is a flag on the screen rather
     * than something the two-pane host does on its own.
     *
     * 🔴 Owned by the caller, not remembered here. In two panes the scaffold has to stop drawing the
     * list and take the pane's detail over the whole window, so it is the scaffold's state; a copy
     * kept here as well would be a second answer to "is this maximized" that could disagree with the
     * layout the user is actually looking at.
     */
    maximized: Boolean = false,
    /**
     * Toggle [maximized]. 🔴 Null draws NO maximize button at all, the same rule every other
     * nullable callback here follows: the debug gallery draws this screen with nothing behind it, so
     * a button that could take the message full screen but leave the gallery's own chrome in place
     * is a control that lies about what it does.
     */
    onToggleMaximize: (() -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    var confirmingUnsubscribe by remember(message.id, initiallyConfirmingUnsubscribe) {
        mutableStateOf(initiallyConfirmingUnsubscribe)
    }
    // 🔴 Keyed on the message id, so it resets when a different message opens. "Show images" is a
    // decision about the message in front of you and it must not travel: in the reading pane the
    // screen is not disposed between messages, so a remembered `true` would silently un-block the
    // next sender's tracking pixels.
    var showOnce by remember(message.id) { mutableStateOf(false) }
    // Keyed on the message id like everything else here: a More sheet left open in the reading pane
    // must not survive into a different message's actions.
    var showingMore by remember(message.id) { mutableStateOf(false) }
    // Same key, same reason: a picker left open must not follow the reading pane onto another
    // message and put the next reader's taps on the wrong mail.
    var showingTags by remember(message.id) { mutableStateOf(false) }
    val tagDefinitions = rememberMailTagDefinitions()
    val tags = remember(message.tags, tagDefinitions) { resolveTags(message.tags, tagDefinitions) }
    val hasRemoteContent = remember(message.body) { EmailRemoteContent.referencedBy(message.body) }
    val showRemote = showOnce || imagesAlwaysAllowed
    // 🔴 Unsubscribe is the one action here that talks to someone else. Every other button
    // rearranges mail that is already on the device, and this one tells a sender you exist and are
    // reading. It gets asked first, wherever it was tapped from.
    val dispatch: (GridlinkThreadAction) -> Unit = { action ->
        if (action == GridlinkThreadAction.UNSUBSCRIBE) {
            confirmingUnsubscribe = true
        } else {
            onAction(action)
        }
    }

    // 🔴 What the star shows until the mailbox catches up with it.
    //
    // [message] is resolved live out of the cached rows on every recomposition, so `message.starred`
    // IS the truth and it updates on its own — but not until the write has been round-tripped to the
    // server and written back to Room, which is a few hundred milliseconds of a button that looks
    // dead. This holds the answer the user just gave in the meantime.
    //
    // It clears itself the moment the truth agrees, rather than on a timer, so there is exactly one
    // source of this state for all but that handful of frames. ⚠️ A write that FAILS never agrees,
    // so the star stays lit on a message that was not starred until the thread is closed and
    // reopened (`remember(message.id)`). That is the same shape of failure every other action here
    // has — [onAction] reports nothing back — and fixing it properly means an error surface on the
    // thread, which does not exist yet for archive either.
    //
    // It is also what makes the star work in the debug gallery, where nothing is wired and
    // `message.starred` can never change.
    var pendingStar by remember(message.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(message.starred) {
        if (pendingStar == message.starred) pendingStar = null
    }
    val starred = pendingStar ?: message.starred

    GridlinkDetailFrame(
        title = message.subject,
        onBack = onBack,
        modifier = modifier,
        embedded = embedded,
        maximized = maximized,
        titleAction = {
            // Two controls in the frame's one slot, as a Row rather than a second slot on the frame.
            // The slot is specified as "one [GridlinkDimens.headerControl] of height beside the
            // title", and a row of two circles is still exactly that height, so both layouts place
            // it the way they already place one.
            Row(
                horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 🔴 Before the star, so the star keeps the trailing position it has always had.
                // Muscle memory for the star is worth more than the new button being first.
                if (onToggleMaximize != null) {
                    GridlinkDetailCircleButton(
                        icon = Icons.Outlined.OpenInFull,
                        label = "Maximize",
                        onClick = onToggleMaximize,
                    )
                }
                GridlinkDetailToggleButton(
                    icon = Icons.Outlined.StarBorder,
                    activeIcon = Icons.Filled.Star,
                    // Names the state it will move TO, like every other toggle in the app. A control
                    // labelled with what it currently is reads as a status line to a screen reader
                    // and leaves "what happens if I press it" unanswered.
                    label = if (starred) "Remove star" else "Star",
                    active = starred,
                    onClick = {
                        val next = !starred
                        pendingStar = next
                        onAction(
                            if (next) GridlinkThreadAction.STAR else GridlinkThreadAction.UNSTAR,
                        )
                    },
                )
            }
        },
        // 🔴 Embedded only, and it is the same block either way — not a second copy of the sender
        // written for the pane. In two panes the frame draws it above the glass with the subject
        // (see [GridlinkDetailFrame]); folded, there is no band to put it in, so it stays the first
        // thing inside the panel exactly as before.
        header = if (embedded) {
            { GridlinkThreadSender(message, banded = true) }
        } else {
            null
        },
        bottom = {
            // ⚠️ Recomputed per message, not remembered against the setting alone: `hasUnsubscribe`
            // is a property of the message and it arrives LATE, with the body fetch. The bar
            // correctly changes shape when it lands (see [GridlinkThreadMoreSheet]).
            val layout = gridlinkToolbarLayout(
                enabled = toolbarActions,
                hasUnsubscribe = message.unsubscribe != null,
                slots = if (embedded) PANE_SLOTS else SLOTS,
            )
            if (layout.inBar.isEmpty() && !layout.showMore) {
                // Every switch off and nothing contextual to offer. An empty pill is a control that
                // does nothing sitting where controls go, so the row gives its width to nothing and
                // Reply keeps its circle at the end. Turning everything off is allowed, and this is
                // what it is allowed to look like.
                Spacer(Modifier.weight(1f))
            } else {
                GridlinkThreadActionPill(
                    layout = layout,
                    starred = starred,
                    onAction = dispatch,
                    onMore = { showingMore = true },
                    modifier = Modifier.weight(1f),
                )
            }
            GridlinkDetailAccentButton(
                icon = Icons.AutoMirrored.Filled.Reply,
                label = "Reply",
                onClick = { onAction(GridlinkThreadAction.REPLY) },
            )
        },
    ) {
        // 🔴 No outer verticalScroll any more, and that is the whole shape of this screen. The body
        // is a WebView that owns its own scroll so Blink can cull what is offscreen (see
        // [GridlinkMessageBody]); an outer scroll would have to measure it to its full content
        // height, which defeats that and puts two scroll containers on one drag. So everything else
        // is pinned and the body takes the space that is left.
        Column(modifier = Modifier.fillMaxSize()) {
            // 🔴 Above the sender block, which is what "under the subject" means here: the subject
            // is the frame's title, so this is the first thing inside the panel and the tags read as
            // belonging to the subject rather than to the person who sent it. Put below the sender
            // they would read as facts about the sender, which is exactly what they are not.
            //
            // It draws nothing at all when there are no tags — no reserved row, no empty box. Most
            // mail carries none, and a permanent gap under every subject would cost the reading room
            // the body wants for the sake of a feature that is off on that message.
            if (tags.isNotEmpty()) {
                GridlinkTagChipRow(
                    tags = tags,
                    modifier = Modifier.padding(
                        start = GridlinkSpacing.rowHorizontal,
                        end = GridlinkSpacing.rowHorizontal,
                        top = GridlinkSpacing.s12,
                    ),
                )
            }

            if (!embedded) {
                GridlinkThreadSender(message)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GridlinkDimens.hairline)
                        .background(colors.divider),
                )
            }

            // Only when blocking would actually change what is on screen. A banner over a message
            // that never asked for anything is noise, and noise is how a privacy control stops
            // being read.
            //
            // 🔴 There is no banner once images ARE allowed. There used to be: a standing bar
            // reading "Images always load from X." with a Stop action, on the argument that a
            // permission you cannot see and cannot revoke where you granted it is not really a
            // permission. Brandon killed it, and the argument was answering the wrong question.
            // The bar was permanent, sat above the first line of every message from every sender
            // ever allowed, and said nothing the loaded pictures underneath it did not already say.
            // Revoke lives in Settings → Privacy, which lists every allowed sender with a per-sender
            // remove and a clear-all, so the permission is both visible and revocable — in the one
            // place you would go to audit it, rather than smeared across every message it affects.
            // 🔴 First, above the images banner and the invitation both. Whether this message is
            // really from the person it names governs how everything under it should be read, and a
            // verdict discovered halfway down a long mail is a verdict discovered too late.
            signed?.let { GridlinkSignedRow(signed = it) }

            if (hasRemoteContent && !showRemote) {
                GridlinkImagesBanner(
                    sender = message.sender,
                    onShowOnce = { showOnce = true },
                    onAlwaysAllow = { onAlwaysAllowImages(true) },
                )
            }

            // 🔴 Above the body, and pinned like everything else here rather than scrolling with it.
            // A meeting request's prose is usually the organiser's client talking to itself — the
            // same times again in a table, or nothing at all — so the part that answers "what is
            // this and when" goes where the eye lands, and the message keeps its place underneath.
            invite?.let {
                GridlinkInviteCard(
                    invite = it,
                    onRespond = onRespondToInvite,
                    onAddToCalendar = onAddToCalendar,
                    onOpenInvitation = onOpenInvitation,
                )
            }

            // Under the invitation and above the body: it is about this message rather than part of
            // it, and a reader who scrolls to the end of a long mail has already decided what to do.
            receipt?.let { GridlinkReceiptRow(receipt = it, onSend = onSendReceipt) }

            GridlinkMessageBody(
                html = message.body,
                blockRemote = !showRemote,
                // The palette's text and accent, so nothing in the markup can paint itself
                // invisible. 🔴 A body carrying `#000000` because it looked right in Day would be
                // unreadable in Night, which is what the whole-page invert in a dark theme is for.
                // There is no background to pass: the renderer is transparent so the frosted panel
                // reads through it. See [GridlinkMessageBody].
                text = colors.textPrimary,
                link = colors.accent,
                dark = mode != GridlinkMode.DAY,
                plainText = message.bodyIsPlainText,
                inlineImages = message.inlineImages,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .gridlinkEdgeFade(fadeTop = false),
            )

            if (message.attachments.isNotEmpty()) {
                // Pinned under the body rather than following it. It used to sit at the end of the
                // prose, which was fine when the whole screen scrolled as one; with the body
                // scrolling inside itself, an attachment placed after it would be unreachable.
                // A Column, one chip per file: the body above has the weight, so a long list costs
                // reading room rather than pushing the action bar off screen.
                Column(
                    modifier = Modifier.padding(
                        start = GridlinkSpacing.s20,
                        end = GridlinkSpacing.s20,
                        top = GridlinkSpacing.s8,
                        bottom = GridlinkSpacing.s12,
                    ),
                    verticalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
                ) {
                    message.attachments.forEach { attachment ->
                        GridlinkThreadAttachment(
                            attachment = attachment,
                            onOpen = onOpenAttachment?.let { open -> { open(attachment) } },
                            onSave = onSaveAttachment?.let { save -> { save(attachment) } },
                        )
                    }
                    attachmentStatus?.let { line ->
                        Text(
                            text = line,
                            style = GridlinkType.timestamp,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }

        // 🔴 Restore floats over the message instead of sitting where Maximize was. The header band
        // is exactly what maximizing gave up, so the control that undoes it cannot live there, and
        // the alternative — keeping a strip of chrome so the button has a home — is not the "literally
        // full screen" he asked for. Top-right, over the message's own top margin, which is the one
        // part of a mail that is reliably empty.
        //
        // ⚠️ It does NOT fade out after a delay. A control that hides itself is a control the reader
        // has to remember exists, and this one is the way out of a mode with no system bars in it.
        if (maximized && onToggleMaximize != null) {
            GridlinkDetailCircleButton(
                icon = Icons.Outlined.CloseFullscreen,
                label = "Restore",
                onClick = onToggleMaximize,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(GridlinkSpacing.s12),
            )
        }
    }

    // Hides the status and navigation bars while this message owns the display, and puts them back
    // on the way out however the reader leaves. See [GridlinkImmersive] for why that is a
    // DisposableEffect rather than a pair of calls.
    GridlinkImmersive(enabled = maximized)

    if (showingMore) {
        GridlinkThreadMoreSheet(
            message = message,
            // The same split the bar made, computed the same way from the same three inputs, so the
            // sheet can never offer an action that is also sitting on the bar behind it. 🔴 `slots`
            // has to match the bar's or the two disagree about what overflowed.
            layout = gridlinkToolbarLayout(
                enabled = toolbarActions,
                hasUnsubscribe = message.unsubscribe != null,
                slots = if (embedded) PANE_SLOTS else SLOTS,
            ),
            starred = starred,
            onAction = { action ->
                showingMore = false
                dispatch(action)
            },
            onTags = onSetTag?.let {
                {
                    showingMore = false
                    showingTags = true
                }
            },
            onDismiss = { showingMore = false },
        )
    }

    if (showingTags && onSetTag != null) {
        GridlinkTagPickerSheet(
            definitions = tagDefinitions,
            applied = message.tags,
            onSetTag = onSetTag,
            onManageTags = onManageTags?.let {
                {
                    showingTags = false
                    it()
                }
            },
            onDismiss = { showingTags = false },
        )
    }

    if (confirmingUnsubscribe) {
        GridlinkDialog(
            title = "Unsubscribe from ${message.sender}?",
            confirmLabel = "Unsubscribe",
            onConfirm = {
                confirmingUnsubscribe = false
                onAction(GridlinkThreadAction.UNSUBSCRIBE)
            },
            onDismiss = { confirmingUnsubscribe = false },
        ) {
            Text(
                // ⚠️ States the side effect, which is the entire reason this dialog exists. An
                // unsubscribe request is a signed confirmation to a bulk sender that the address is
                // live and monitored, and for a sender acting in bad faith that is worth more than
                // the mail they were sending. Anyone who cares should archive instead, and they can
                // only decide that if the app says so.
                //
                // 🔴 Three sentences, because the three methods genuinely do three different things
                // and one shared sentence would be wrong for two of them. The old copy said "sends
                // a request" for all of them, which was a plain lie about the mailto path, where
                // nothing goes anywhere until the reader presses send on a draft they can read.
                text = gridlinkUnsubscribeWarning(message),
                style = GridlinkType.body,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * Who sent it and when, on one line, with the raw address and the to-line a tap away.
 *
 * 🔴 No avatar circle and no sender initials. §9 bans them in the list and the ban does not stop at
 * the list: the identity bar is this design's answer to "which sender is this at a glance", and an
 * avatar next to it would be a second, weaker answer to the same question.
 *
 * ## 🔴 Why the address hides, when the whole point of showing it was that it should not
 * This block was three lines, and the reasoning was sound: a display name is what a phishing attempt
 * controls completely, so a header showing only "HR Benefits" has hidden the one field worth
 * checking. What that argument missed is the cost. Brandon, on a folded display: the header "takes
 * up a huge part of screen real estate". Three metadata lines plus a banner before a word of the
 * message is a header that has to be scrolled past on every single message to pay for a check that
 * is worth making on a few.
 *
 * So it collapses rather than disappears, and the chevron is load-bearing: it is the difference
 * between a field the app is hiding and a field the reader has not opened yet. Anyone who wants to
 * verify a sender is one tap from the full address, in the place they already look for it, and the
 * other ninety-nine messages cost one line. A checkbox in Settings would have been the wrong
 * answer, because the reader who needs this needs it on *this* message and cannot know in advance
 * which one that is.
 *
 * ⚠️ Collapsed state is per-composition and deliberately not remembered across messages. Expanding
 * one sender is a question about that sender, not a preference.
 */
/*
 * [banded] draws the same block above the reading pane's glass instead of inside it, which is where
 * it lives in two panes now that the subject moved up there ("take the subject and the header out of
 * the window and display above in the newly created space"). Two things change and nothing else:
 * the sender-domain colour bar goes, because it is an edge marker for a row inside a panel and in
 * the band it would sit outside the pane's left margin, and the horizontal padding goes with it so
 * the sender name starts on the same pixel column as the subject directly above it. The vertical
 * padding tightens because the band already pads itself; the point of the move was reading room.
 */
@Composable
private fun GridlinkThreadSender(
    message: GridlinkMessage,
    modifier: Modifier = Modifier,
    banded: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    var expanded by remember(message.address) { mutableStateOf(false) }
    val chevronTurn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = GridlinkMotion.standard(),
        label = "senderChevron",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        if (!banded) {
            Box(
                modifier = Modifier
                    .width(GridlinkDimens.senderBarWidth)
                    .fillMaxHeight()
                    .background(gridlinkSenderBarColor(mode, message.domain)),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                // The whole block is the target, not the chevron. A 24dp glyph is a miserable thing
                // to hit on a phone, and there is nothing else in this header to tap.
                .clickable { expanded = !expanded }
                .padding(
                    horizontal = if (banded) 0.dp else GridlinkSpacing.rowHorizontal,
                    vertical = if (banded) GridlinkSpacing.s4 else GridlinkSpacing.s16,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.sender,
                    style = GridlinkType.senderName,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = message.timestamp,
                    style = GridlinkType.timestamp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = GridlinkSpacing.s8),
                )
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    // Names the thing behind the chevron rather than the chevron, because a screen
                    // reader user cannot see that the address is the part being withheld.
                    contentDescription = if (expanded) "Hide sender details" else "Show sender details",
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .padding(start = GridlinkSpacing.s8)
                        .size(18.dp)
                        .rotate(chevronTurn),
                )
            }
            if (expanded) {
                Text(
                    text = message.address,
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = GridlinkSpacing.s8),
                )
                // 🔴 The signed-in address, not a constant. This line used to read the sample's,
                // which on a real account would have every message in the mailbox claim it was
                // addressed to somebody else — on the one screen whose job is to let you check
                // exactly that. It is still an approximation: it says who is READING, not what the
                // To header holds, so a message received via a list or a bcc says "to <you>"
                // because that is what the cache knows. The real recipients arrive with the body.
                //
                // Blank is now the chrome's default (see [GridlinkChromeConfig]), and "to " on its
                // own is worse than no line at all, so the whole row goes.
                val readerAddress = LocalGridlinkChrome.current.config.account
                if (readerAddress.isNotBlank()) {
                    Text(
                        text = "to $readerAddress",
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The blocked-images notice, and the two ways out of it.
 *
 * 🔴 It is in the message, not in a menu. Upstream puts the same two controls behind the overflow,
 * which is a defensible place for a setting and the wrong place for this: the reader is looking at a
 * message with holes in it and needs to be told why, in the same glance. A control the reader has to
 * go looking for is one they will only find after deciding the app is broken.
 *
 * The two actions are genuinely different promises and the labels say so. **Show** is once, for this
 * message, and is forgotten the moment it closes. **Always** writes the sender into a list that
 * outlives the app being killed, and from then on their mail loads pictures on open. That second one
 * is a standing permission granted to somebody else, so it names them rather than saying "always".
 */
@Composable
private fun GridlinkImagesBanner(
    sender: String,
    onShowOnce: () -> Unit,
    onAlwaysAllow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised)
            .padding(
                start = GridlinkSpacing.s20,
                end = GridlinkSpacing.s12,
                top = GridlinkSpacing.s8,
                bottom = GridlinkSpacing.s8,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.HideImage,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            // States the consequence, which is the entire reason the block exists. "Images blocked"
            // alone reads as a limitation of the app; this reads as a thing it did on purpose.
            text = "Images blocked so $sender can't tell you opened this.",
            style = GridlinkType.metadata,
            color = colors.textSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = GridlinkSpacing.s12),
        )
        GridlinkImagesBannerAction(label = "Show", onClick = onShowOnce)
        GridlinkImagesBannerAction(label = "Always", onClick = onAlwaysAllow)
    }
}

/** One word in the banner, tappable. A text button, because two of them beside prose is enough. */
@Composable
private fun GridlinkImagesBannerAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Text(
        text = label,
        style = GridlinkType.chip,
        color = colors.accent,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(GridlinkRadii.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = GridlinkSpacing.s8, vertical = GridlinkSpacing.s8),
    )
}

// GridlinkImagesAllowedBanner used to live here: a standing "Images always load from X." bar with a
// Stop action, shown on every message from an allowed sender. It is gone. The reasoning for it was
// that a permission you cannot see and cannot revoke where you granted it is not really a
// permission — true, and satisfied by Settings → Privacy, which lists every allowed sender with a
// remove and a clear-all. What the bar actually did was spend a permanent line at the top of every
// affected message restating what the loaded images below it already showed.

/**
 * One attachment, as a chip.
 *
 * Clickable exactly when [onOpen] is non-null. The rule this replaces ("deliberately not clickable,
 * nothing in this prototype can open a file") survives as the null branch: a fixture's chip, with
 * no bytes anywhere behind it, still refuses to highlight under the thumb and then do nothing.
 *
 * ## 🔴 Save is a separate button, not a long-press
 * The body of the chip keeps doing exactly what it did — one tap, opens. The review corpus's
 * clearest single warning is against changing a gesture people already have in their fingers
 * ("the long press timing has been changed so now opens an email if you don't hold it long
 * enough and has ruined [it]", and a whole cluster of 1-stars behind it), so saving gets its own
 * visible target instead of a hidden hold. It is also the discoverable answer: nobody long-presses
 * a file to find out whether an app they just installed can keep it.
 */
@Composable
private fun GridlinkThreadAttachment(
    attachment: GridlinkAttachment,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceRaised, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            // 🔴 The padding shrinks around the save button so the chip does NOT get taller for
            // having one. The button is a 36dp target that already contains its own breathing room,
            // and 12dp of chip padding on top of it would grow a 44dp chip to 60 — a third again
            // for one icon, in the one place a message with four files can least afford it.
            .padding(
                start = GridlinkSpacing.s16,
                end = if (onSave != null) GridlinkSpacing.s4 else GridlinkSpacing.s16,
                top = if (onSave != null) GridlinkSpacing.s4 else GridlinkSpacing.s12,
                bottom = if (onSave != null) GridlinkSpacing.s4 else GridlinkSpacing.s12,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.AttachFile,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = attachment.name,
            style = GridlinkType.chip,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = GridlinkSpacing.s8),
        )
        Text(
            text = attachment.size,
            style = GridlinkType.timestamp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = GridlinkSpacing.s12),
        )
        if (onSave != null) {
            Box(
                modifier = Modifier
                    .padding(start = GridlinkSpacing.s8)
                    .size(36.dp)
                    .clip(CircleShape)
                    // Its own clickable INSIDE the chip's: Compose gives the tap to the innermost
                    // handler, so the save target does not also open the file behind it.
                    .clickable(onClick = onSave),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    // Named for where it ends up, not for the mechanism: "download" is what the
                    // tap already did when it opened the file, and the difference the user cares
                    // about is that this one is still there tomorrow.
                    contentDescription = "Save ${attachment.name} to the phone",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Everything you can do to an open message.
 *
 * 🔴 [REPLY] is the accent circle, [FORWARD] and [ARCHIVE] share the pill with a More slot, and the
 * rest live behind More ([GridlinkThreadMoreSheet]). Reply is deliberately not in the pill as well:
 * two controls that do the same thing on one 64dp baseline is how you end up tapping the wrong one,
 * and the circle is already the loudest object on the screen. The circle carries the word "Reply"
 * for the same reason, see [GridlinkDetailAccentButton].
 *
 * ## 🔴 [STAR] and [UNSTAR] are the exception: they are up at the title, not down at the baseline
 * Everything else in this enum is a one-way verb that ends with the message somewhere else, so the
 * baseline is where they belong. Starring is a switch on the message you are looking at, it is
 * reversible, and its whole point is that you can see the answer without pressing anything. None of
 * that fits a row of verbs. The two other places it could have gone are closed: the pill is settled
 * at three slots after Brandon called four "way too crowded" (see [GridlinkThreadActionPill]), and
 * burying a switch under More would hide the state as well as the control. So it is a lit circle
 * beside the subject, which is [GridlinkDetailFrame]'s `titleAction` slot.
 *
 * Two entries rather than one TOGGLE because everything downstream is a set-a-value call, not a
 * flip-whatever-you-find call. A single TOGGLE would have to re-read the current state somewhere
 * else in the chain, and that is exactly where a double tap turns into a lost write.
 */
enum class GridlinkThreadAction {
    REPLY,
    REPLY_ALL,
    FORWARD,
    ARCHIVE,
    SPAM,
    UNSUBSCRIBE,
    STAR,
    UNSTAR,
    DELETE,
    MOVE,
    MARK_UNREAD,
    PRINT,
    SNOOZE,
}

/**
 * Where a [ThreadToolbarAction] the reader enabled actually goes when it is pressed.
 *
 * Two enums rather than one, and the seam is deliberate. [ThreadToolbarAction] is a preference: it
 * is persisted by name, it is exported in backups, and it must not change shape because the UI grew
 * a case. This one is a dispatch instruction, and it has entries no switch will ever offer (REPLY
 * owns the accent circle, UNSTAR is the other half of a toggle, UNSUBSCRIBE is contextual). Fusing
 * them would put the settings screen's stability at the mercy of the reading screen's wiring.
 *
 * 🔴 STAR is the one entry that cannot be mapped without knowing the message. Everything downstream
 * is a set-a-value call rather than a flip-what-you-find call (see [GridlinkThreadAction]), so the
 * caller passes [starred] and gets the action that moves it to the other state.
 */
internal fun ThreadToolbarAction.gridlinkThreadAction(starred: Boolean): GridlinkThreadAction = when (this) {
    ThreadToolbarAction.REPLY_ALL -> GridlinkThreadAction.REPLY_ALL
    ThreadToolbarAction.FORWARD -> GridlinkThreadAction.FORWARD
    ThreadToolbarAction.ARCHIVE -> GridlinkThreadAction.ARCHIVE
    ThreadToolbarAction.DELETE -> GridlinkThreadAction.DELETE
    ThreadToolbarAction.MOVE -> GridlinkThreadAction.MOVE
    ThreadToolbarAction.MARK_UNREAD -> GridlinkThreadAction.MARK_UNREAD
    ThreadToolbarAction.STAR -> if (starred) GridlinkThreadAction.UNSTAR else GridlinkThreadAction.STAR
    ThreadToolbarAction.PRINT -> GridlinkThreadAction.PRINT
    ThreadToolbarAction.JUNK -> GridlinkThreadAction.SPAM
    ThreadToolbarAction.SNOOZE -> GridlinkThreadAction.SNOOZE
}

/**
 * The words on the button, matched to the words on the switch that put it there.
 *
 * 🔴 Star is the only one whose label depends on state, for [GridlinkDetailToggleButton]'s reason:
 * a control names what it will DO, so on a starred message the button says "Remove star". Every
 * other action reads the same in the bar, in the More sheet and in settings, and it has to: the
 * settings screen is a map of this bar.
 */
internal fun gridlinkToolbarLabel(action: ThreadToolbarAction, starred: Boolean): String = when (action) {
    ThreadToolbarAction.REPLY_ALL -> "Reply all"
    ThreadToolbarAction.FORWARD -> "Forward"
    ThreadToolbarAction.ARCHIVE -> "Archive"
    ThreadToolbarAction.DELETE -> "Delete"
    ThreadToolbarAction.MOVE -> "Move"
    ThreadToolbarAction.MARK_UNREAD -> "Mark unread"
    ThreadToolbarAction.STAR -> if (starred) "Remove star" else "Star"
    ThreadToolbarAction.PRINT -> "Print"
    ThreadToolbarAction.JUNK -> "Junk"
    ThreadToolbarAction.SNOOZE -> "Snooze"
}

/** The glyph for each, shared by the bar and the sheet so one action never wears two icons. */
internal fun gridlinkToolbarIcon(action: ThreadToolbarAction, starred: Boolean): ImageVector = when (action) {
    ThreadToolbarAction.REPLY_ALL -> Icons.AutoMirrored.Outlined.ReplyAll
    ThreadToolbarAction.FORWARD -> Icons.AutoMirrored.Outlined.Forward
    ThreadToolbarAction.ARCHIVE -> Icons.Outlined.Archive
    ThreadToolbarAction.DELETE -> Icons.Outlined.Delete
    ThreadToolbarAction.MOVE -> Icons.Outlined.DriveFileMove
    ThreadToolbarAction.MARK_UNREAD -> Icons.Outlined.MarkEmailUnread
    ThreadToolbarAction.STAR -> if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder
    ThreadToolbarAction.PRINT -> Icons.Outlined.Print
    ThreadToolbarAction.JUNK -> Icons.Outlined.Report
    ThreadToolbarAction.SNOOZE -> Icons.Outlined.Snooze
}

/**
 * How the enabled actions are split between the bar's slots and its More sheet.
 *
 * 🔴 Three slots total and the third is More whenever anything is left over, which is the shape
 * Brandon settled on when four labelled controls made the reading pane "way too crowded". So the
 * honest reading of "shows the first three" is: three when three is all there is, otherwise two and
 * a door to the rest. A bar that grew a fourth slot for a reader who enabled a fourth action would
 * reintroduce the crowding the overflow was built to fix.
 *
 * ⚠️ Unsubscribe counts toward the overflow without being in [enabled]. It is contextual — it exists
 * only on a message carrying a `List-Unsubscribe` header — so it can never hold a permanent slot,
 * but it is real content in the sheet and the More button has to appear for it. That is why a
 * message with an unsubscribe header can show two actions where the one below it shows three: the
 * bar reflects what THIS message can do.
 */
internal data class GridlinkToolbarLayout(
    val inBar: List<ThreadToolbarAction>,
    val inSheet: List<ThreadToolbarAction>,
    val showMore: Boolean,
)

internal fun gridlinkToolbarLayout(
    enabled: Set<ThreadToolbarAction>,
    hasUnsubscribe: Boolean,
    slots: Int = SLOTS,
): GridlinkToolbarLayout {
    // The enum's order, never the set's. A Set has no order worth trusting, and the whole
    // fixed-order decision rests on this line.
    val ordered = ThreadToolbarAction.entries.filter { it in enabled }
    val showMore = ordered.size > slots || hasUnsubscribe
    val room = if (showMore) slots - 1 else slots
    val inBar = when {
        ordered.size <= room -> ordered
        // 🔴 One slot is the one case enum order gets wrong. It would hand the last control to
        // Forward, and Brandon named the survivor when he cut the pane to two: *"its gonna only be
        // able to hold one control + more. so do archive and more"*. Forward is a compose action
        // that opens a whole screen anyway, so a tap through More costs it nothing; Archive is the
        // one that has to be reachable without opening anything.
        room == 1 && ThreadToolbarAction.ARCHIVE in ordered -> listOf(ThreadToolbarAction.ARCHIVE)
        else -> ordered.take(room)
    }
    // ⚠️ Filtered, not dropped. `drop(inBar.size)` was right only while the bar was always a
    // prefix of [ordered], and the one-slot rule above breaks that.
    return GridlinkToolbarLayout(
        inBar = inBar,
        inSheet = ordered.filterNot { it in inBar },
        showMore = showMore,
    )
}

/** Slots on the bar, More included. See [GridlinkThreadActionPill]. */
private const val SLOTS = 3

/**
 * The same bar in the two-pane reading pane, which is ~380dp narrower than a folded screen.
 *
 * Three labelled controls plus Reply plus the parked "+" fit there arithmetically and still read as
 * cramped, which is a thing only visible on the real device: *"the bottom menu bar that currently
 * contains forward, archive, and more - its gonna only be able to hold one control + more. three
 * causes it to still be cramped."* The folded bar is not crowded and does not change.
 */
private const val PANE_SLOTS = 2

/**
 * The secondary actions, in the same shell the nav pill uses on the list.
 *
 * The bottom of every screen in this app is one wide pill plus one accent circle, and a thread that
 * arranged its actions any other way would read as a different product. Reply gets the circle, which
 * is the same relationship the list has between navigation and Compose.
 *
 * ## 🔴 Three slots, the third being More
 * This pill used to hold four contextual actions, and in §7's reading pane four labelled slots plus
 * the Reply circle plus the scaffold's parked "+" put six controls on one baseline about 460dp wide.
 * Brandon called it "way too crowded" and picked the overflow. The same three slots everywhere, full
 * screen included — a pill that changed its population with the window would be two bars to learn.
 *
 * ## What is IN those slots is now the reader's
 * Brandon, 2026-08-10: "the dynamic control bar at the bottom in unfolded mode should be
 * customizable, make that an option in settings." The count did not change and the crowding verdict
 * still stands; only the contents moved into [ThreadToolbarAction], and [gridlinkToolbarLayout] does
 * the fitting. This composable is now pure rendering: it is handed a decided layout and draws it.
 *
 * ⚠️ Deliberately NOT a reorderable list. He picked fixed order over drag-to-arrange, so there is
 * nothing here that reads a stored position — see [ThreadToolbarAction] for why the enum's own
 * declaration order is the whole ordering mechanism.
 */
@Composable
private fun GridlinkThreadActionPill(
    layout: GridlinkToolbarLayout,
    starred: Boolean,
    onAction: (GridlinkThreadAction) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GridlinkDetailActionPill(modifier = modifier) {
        layout.inBar.forEach { action ->
            GridlinkDetailActionItem(
                label = gridlinkToolbarLabel(action, starred),
                icon = gridlinkToolbarIcon(action, starred),
                onClick = { onAction(action.gridlinkThreadAction(starred)) },
                // 🔴 Equal weights, so two enabled actions each take half the pill rather than
                // sitting at its left end. The pill is the same width whatever is in it, and a bar
                // whose buttons moved when a setting changed would be a different bar every time.
                modifier = Modifier.weight(1f),
            )
        }
        if (layout.showMore) {
            GridlinkDetailActionItem(
                label = "More",
                icon = Icons.Outlined.MoreHoriz,
                onClick = onMore,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * What the pill's More slot opens: the actions worth having that are not worth a permanent slot.
 *
 * ## 🔴 Which actions, and why it depends on the message
 * Two sources, in this order: the message's own Unsubscribe if it has one, then whatever the
 * reader's enabled set could not fit on the bar ([gridlinkToolbarLayout]). The first is contextual
 * and cannot be configured; the second is entirely their choice, Reply all and Junk included, which
 * is why this sheet no longer names a single action of its own.
 *
 * This used to hard-code "Reply all OR Unsubscribe, then Spam", and the OR was the interesting part:
 * on a bulk mailing, replying to everyone means replying to a no-reply robot and a mailing list. It
 * is gone because Reply all is now a switch — a reader who never wants it turns it off everywhere
 * rather than having the app guess per message — and Reply is still on the circle regardless.
 *
 * 🔴 The signal is the message's own `List-Unsubscribe` header and nothing else. It used to be
 * [GridlinkMessage.automated] — a guess off the local part of the address — with a note here saying
 * to swap it when real mail landed, and the two do disagree exactly as that note predicted: plenty
 * of bulk mail ships no header, and plenty of newsletters come from a named human. The header
 * arrives with the body, so on a message whose fetch has not answered yet this shows Reply all and
 * changes when it does. Appearing late is the honest failure; offering it on a guess is not.
 */
@Composable
private fun GridlinkThreadMoreSheet(
    message: GridlinkMessage,
    layout: GridlinkToolbarLayout,
    starred: Boolean,
    onAction: (GridlinkThreadAction) -> Unit,
    onTags: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    GridlinkCenterSheet(onDismiss = onDismiss) {
        // Restates which message is being acted on, same rule as the folder sheet: the sheet covers
        // the thing it acts on, and on the reading pane that thing may not be the only message on
        // screen.
        GridlinkSheetHeading(
            title = message.sender,
            icon = Icons.Outlined.Email,
            subline = message.subject,
        )
        GridlinkSheetDivider()
        // 🔴 First, above the reader's own overflow. It is the only row here that is about THIS
        // message rather than about the reader's layout, and it is the one they opened the sheet
        // for on a message that has it.
        if (message.unsubscribe != null) {
            GridlinkSheetAction(
                label = "Unsubscribe",
                icon = Icons.Outlined.Unsubscribe,
                onClick = { onAction(GridlinkThreadAction.UNSUBSCRIBE) },
            )
        }
        // 🔴 Tags live here and not on the bar, deliberately. The bar is the reader's own set of
        // one-tap verbs and every entry in it is a switch in Settings; tagging is not a verb but a
        // second sheet, so a slot for it would be a button that opens another button. It sits above
        // the overflow with Unsubscribe for the same reason that one does: both are about THIS
        // message rather than about the reader's chosen layout.
        if (onTags != null) {
            GridlinkSheetAction(
                label = "Tags",
                icon = Icons.Outlined.Sell,
                onClick = onTags,
            )
        }
        // Everything the bar could not fit, in the same order it would have shown them. The sheet is
        // the continuation of the bar, not a second menu with its own opinions, so an action never
        // changes name or icon on the way in here.
        layout.inSheet.forEach { action ->
            GridlinkSheetAction(
                label = gridlinkToolbarLabel(action, starred),
                icon = gridlinkToolbarIcon(action, starred),
                onClick = { onAction(action.gridlinkThreadAction(starred)) },
            )
        }
        GridlinkSheetFooterSpace()
    }
}

/**
 * Builds a reply to [message].
 *
 * 🔴 It does not reuse [GridlinkComposeDraft.Reply], which is hard-wired to the Rivera thread for
 * the brief's §1d mockup. Opening the Duke Energy statement and getting a reply addressed to Rivera
 * with a schedule PDF quoted under it would be a demo that lies, which is the one thing the sample
 * data rules in [GridlinkSample] exist to prevent.
 *
 * The recipient is matched out of [GridlinkSampleContacts] by sender and domain (see
 * [GridlinkSampleContacts.forSender]) so a reply to someone in the address book carries their real
 * card, and falls back to a card built from the message header when it does not, which is what a
 * reply to a no-reply robot should look like.
 */
internal fun gridlinkReplyTo(message: GridlinkMessage): GridlinkComposeRequest {
    val known = GridlinkSampleContacts.forSender(message.sender, message.domain)
    val recipient = known ?: GridlinkContact(
        id = message.id,
        given = "",
        family = message.sender,
        role = message.domain,
        email = message.address,
    )
    val subject = if (message.subject.startsWith("Re: ", ignoreCase = true)) {
        message.subject
    } else {
        "Re: ${message.subject}"
    }
    return GridlinkComposeRequest(
        draft = GridlinkComposeDraft(
            title = "Reply",
            recipients = listOf(recipient),
            recipientQuery = "",
            subject = subject,
            body = "",
            quoted = gridlinkReplyQuote(message),
            attachments = emptyList(),
        ),
        focus = GridlinkComposeField.BODY,
    )
}

/**
 * Builds a reply-all to [message].
 *
 * ⚠️ **Right now this addresses exactly the same people [gridlinkReplyTo] does, and that is not a
 * bug in the button, it is the sample model.** [GridlinkMessage] carries one sender and no To or Cc
 * list, so there is literally nobody else on the message to add. Inventing a second recipient to
 * make the button look busier would put a name in a real-looking composer that no message ever
 * addressed, which §9 forbids and which is the sort of demo detail that survives into shipping code.
 *
 * What it does change is the title, so the composer says which of the two you pressed. When the JMAP
 * store lands this becomes `to + cc` minus your own identities, and it is the *minus* that matters:
 * every mail client that gets reply-all wrong gets it wrong by leaving you on the list.
 */
internal fun gridlinkReplyAllTo(message: GridlinkMessage): GridlinkComposeRequest {
    val reply = gridlinkReplyTo(message)
    return reply.copy(draft = reply.draft.copy(title = "Reply all"))
}

/**
 * Builds a forward of [message].
 *
 * Three things differ from a reply and each is load-bearing. There is no recipient, because a
 * forward is the one compose action where the app cannot guess who it is for, so the TO field takes
 * focus instead of the body. The attachment travels, because a forwarded invoice without the invoice
 * is the most annoying possible outcome. And the subject is prefixed "Fwd: " rather than "Re: ",
 * which is the only thing telling the far end this is a relay and not a conversation.
 *
 * 🔴 [attachments] comes from the caller, and it must be chips something is actually holding the
 * bytes behind (see [GridlinkAttacher.adopt]). It used to be `message.attachments`, which is the
 * READER's chips: those carry a part index as their id ("0", "1"), no attacher ever minted them, and
 * so every forward of a message with a file refused to send with "has no file behind it. Remove and
 * attach again to send." The file travelling is the whole point of the paragraph above, so the
 * caller stages it first and hands the staged chips in. Defaults to none for the screenshot build,
 * where there is no attacher and therefore no honest chip to show.
 */
internal fun gridlinkForward(
    message: GridlinkMessage,
    attachments: List<GridlinkAttachment> = emptyList(),
): GridlinkComposeRequest {
    val subject = if (message.subject.startsWith("Fwd: ", ignoreCase = true)) {
        message.subject
    } else {
        "Fwd: ${message.subject}"
    }
    return GridlinkComposeRequest(
        draft = GridlinkComposeDraft(
            title = "Forward",
            recipients = emptyList(),
            recipientQuery = "",
            subject = subject,
            body = "",
            quoted = gridlinkForwardQuote(message),
            attachments = attachments,
        ),
        focus = GridlinkComposeField.TO,
    )
}
