package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Unsubscribe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.ui.emailhtml.EmailRemoteContent
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMode
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

    GridlinkDetailFrame(
        title = message.subject,
        onBack = onBack,
        modifier = modifier,
        embedded = embedded,
        bottom = {
            GridlinkThreadActionPill(
                onAction = dispatch,
                onMore = { showingMore = true },
                modifier = Modifier.weight(1f),
            )
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
            GridlinkThreadSender(message)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GridlinkDimens.hairline)
                    .background(colors.divider),
            )

            // Only when blocking would actually change what is on screen. A banner over a message
            // that never asked for anything is noise, and noise is how a privacy control stops
            // being read. The allowed-banner has the same condition for the same reason: there is
            // nothing to say about images on a message that has none.
            if (hasRemoteContent) {
                if (showRemote) {
                    // ⚠️ Only for the standing permission. A one-off "Show" needs no banner: the
                    // reader pressed it two seconds ago and nothing was remembered.
                    if (imagesAlwaysAllowed) {
                        GridlinkImagesAllowedBanner(
                            sender = message.sender,
                            onStopAllowing = { onAlwaysAllowImages(false) },
                        )
                    }
                } else {
                    GridlinkImagesBanner(
                        sender = message.sender,
                        onShowOnce = { showOnce = true },
                        onAlwaysAllow = { onAlwaysAllowImages(true) },
                    )
                }
            }

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

            message.attachment?.let { attachment ->
                // Pinned under the body rather than following it. It used to sit at the end of the
                // prose, which was fine when the whole screen scrolled as one; with the body
                // scrolling inside itself, an attachment placed after it would be unreachable.
                GridlinkThreadAttachment(
                    attachment = attachment,
                    modifier = Modifier.padding(
                        start = GridlinkSpacing.s20,
                        end = GridlinkSpacing.s20,
                        top = GridlinkSpacing.s8,
                        bottom = GridlinkSpacing.s12,
                    ),
                )
            }
        }
    }

    if (showingMore) {
        GridlinkThreadMoreSheet(
            message = message,
            onAction = { action ->
                showingMore = false
                dispatch(action)
            },
            onDismiss = { showingMore = false },
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
                text = "Sends a request to ${message.domain} and files this message. " +
                    "It also confirms to them that this address is real.",
                style = GridlinkType.body,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * Who sent it, from where, to whom, and when.
 *
 * 🔴 No avatar circle and no sender initials. §9 bans them in the list and the ban does not stop at
 * the list: the identity bar is this design's answer to "which sender is this at a glance", and an
 * avatar next to it would be a second, weaker answer to the same question.
 *
 * The address line is the reason this block is three lines instead of one. A display name is what a
 * phishing attempt controls completely, so a header that shows only "HR Benefits" has hidden the one
 * field worth checking.
 */
@Composable
private fun GridlinkThreadSender(
    message: GridlinkMessage,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(GridlinkDimens.senderBarWidth)
                .fillMaxHeight()
                .background(gridlinkSenderBarColor(mode, message.domain)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(
                    horizontal = GridlinkSpacing.rowHorizontal,
                    vertical = GridlinkSpacing.s16,
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
            }
            Text(
                text = message.address,
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 🔴 The signed-in address, not a constant. This line used to read the sample's,
                // which on a real account would have every message in the mailbox claim it was
                // addressed to somebody else — on the one screen whose job is to let you check
                // exactly that. It is still an approximation: it says who is READING, not what the
                // To header holds, so a message received via a list or a bcc says "to <you>"
                // because that is what the cache knows. The real recipients arrive with the body.
                text = "to " + LocalGridlinkChrome.current.config.account,
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

/**
 * The standing notice, once the reader has said yes to this sender for good.
 *
 * ⚠️ It is not decoration and it is not a success message. The banner above is how the allowlist is
 * joined, and without this there is no way to see from a message that the sender is on it, and no
 * way off it at all except from the settings screen upstream owns. A permission you cannot see and
 * cannot revoke where you granted it is not really a permission.
 */
@Composable
private fun GridlinkImagesAllowedBanner(
    sender: String,
    onStopAllowing: () -> Unit,
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
            imageVector = Icons.Outlined.Image,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Images always load from $sender.",
            style = GridlinkType.metadata,
            color = colors.textSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = GridlinkSpacing.s12),
        )
        GridlinkImagesBannerAction(label = "Stop", onClick = onStopAllowing)
    }
}

/**
 * The attachment, as a static chip.
 *
 * Deliberately not clickable. Nothing in this prototype can open a file, and a chip that highlights
 * under the thumb and then does nothing is a worse answer than one that plainly presents itself as
 * a label.
 */
@Composable
private fun GridlinkThreadAttachment(
    attachment: GridlinkAttachment,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceRaised, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .padding(horizontal = GridlinkSpacing.s16, vertical = GridlinkSpacing.s12),
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
 */
enum class GridlinkThreadAction { REPLY, REPLY_ALL, FORWARD, ARCHIVE, SPAM, UNSUBSCRIBE }

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
 * Brandon called it "way too crowded" and picked the overflow: the two actions you reach for while
 * reading (Forward, Archive) keep their one-tap slots, and the rare, deliberate ones (Reply all or
 * Unsubscribe, and Spam) sit one tap further away in [GridlinkThreadMoreSheet]. The same three
 * slots everywhere, full screen included — a pill that changed its population with the window would
 * be two bars to learn.
 */
@Composable
private fun GridlinkThreadActionPill(
    onAction: (GridlinkThreadAction) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GridlinkDetailActionPill(modifier = modifier) {
        GridlinkDetailActionItem(
            label = "Forward",
            icon = Icons.AutoMirrored.Outlined.Forward,
            onClick = { onAction(GridlinkThreadAction.FORWARD) },
            modifier = Modifier.weight(1f),
        )
        GridlinkDetailActionItem(
            label = "Archive",
            icon = Icons.Outlined.Archive,
            onClick = { onAction(GridlinkThreadAction.ARCHIVE) },
            modifier = Modifier.weight(1f),
        )
        GridlinkDetailActionItem(
            label = "More",
            icon = Icons.Outlined.MoreHoriz,
            onClick = onMore,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What the pill's More slot opens: the actions worth having that are not worth a permanent slot.
 *
 * ## 🔴 Which actions, and why it depends on the sender
 * - **A person wrote it:** Reply all, then Spam. There is no unsubscribe link in a mail from a
 *   colleague, and offering one that cannot work is worse than not offering it.
 * - **A machine sent it** ([GridlinkMessage.automated]): Unsubscribe, then Spam. Reply all drops
 *   out, because replying to everyone on a billing statement means replying to a no-reply robot and
 *   a mailing list, and Reply is still on the circle for the rare one that does read them.
 *
 * Spam is not tinted [app.gridlink.ui.theme.GridlinkColors.destructive]: red in this palette is
 * spent on delete and nothing else, and filing to Junk is recoverable.
 *
 * ⚠️ The real signal is the `List-Unsubscribe` header, not [GridlinkMessage.automated]. The sample
 * data has no headers, and `automated` is the field that means the same thing here. Swap it when the
 * JMAP store lands, and expect the two to disagree: plenty of genuine bulk mail ships no header at
 * all, and the row has to disappear for those rather than fail.
 */
@Composable
private fun GridlinkThreadMoreSheet(
    message: GridlinkMessage,
    onAction: (GridlinkThreadAction) -> Unit,
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
        if (message.automated) {
            GridlinkSheetAction(
                label = "Unsubscribe",
                icon = Icons.Outlined.Unsubscribe,
                onClick = { onAction(GridlinkThreadAction.UNSUBSCRIBE) },
            )
        } else {
            GridlinkSheetAction(
                label = "Reply all",
                icon = Icons.AutoMirrored.Outlined.ReplyAll,
                onClick = { onAction(GridlinkThreadAction.REPLY_ALL) },
            )
        }
        GridlinkSheetAction(
            label = "Spam",
            icon = Icons.Outlined.Report,
            onClick = { onAction(GridlinkThreadAction.SPAM) },
        )
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
            quoted = "Quoted — ${message.sender}, ${message.timestamp}",
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
 */
internal fun gridlinkForward(message: GridlinkMessage): GridlinkComposeRequest {
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
            quoted = "Quoted — ${message.sender}, ${message.timestamp}",
            attachments = listOfNotNull(message.attachment),
        ),
        focus = GridlinkComposeField.TO,
    )
}
