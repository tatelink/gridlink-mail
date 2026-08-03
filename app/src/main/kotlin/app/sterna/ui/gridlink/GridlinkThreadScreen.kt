package app.sterna.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sterna.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType
import app.sterna.ui.theme.gridlinkSenderBarColor

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
) {
    val colors = GridlinkTheme.colors
    val panelShape = RoundedCornerShape(GridlinkRadii.card)
    var confirmingUnsubscribe by remember(message.id, initiallyConfirmingUnsubscribe) {
        mutableStateOf(initiallyConfirmingUnsubscribe)
    }

    GridlinkBackground(
        // 🔴 Swallows every touch that nothing inside handled. This screen is drawn OVER the message
        // list rather than replacing it, and Compose hit-testing walks every sibling under the
        // pointer, so a tap on a dead area of the thread used to land on whatever the list had in
        // the same place. That is not theoretical: the bottom-right of the thread's action pill sits
        // exactly over the list's Compose button, so tapping Archive opened the composer. Children
        // still win, because the main pass runs bottom-up and this only sees what they ignored.
        modifier = modifier.pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            GridlinkThreadHeader(subject = message.subject, onBack = onBack)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = GridlinkSpacing.chrome)
                    .clip(panelShape)
                    .background(colors.listSurface, panelShape)
                    .border(GridlinkDimens.hairline, colors.surfaceBorder, panelShape),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .gridlinkEdgeFade(fadeTop = false),
                ) {
                    GridlinkThreadSender(message)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(GridlinkDimens.hairline)
                            .background(colors.divider),
                    )

                    GridlinkThreadBody(
                        html = message.body,
                        modifier = Modifier.padding(
                            horizontal = GridlinkSpacing.s20,
                            vertical = GridlinkSpacing.s20,
                        ),
                    )

                    message.attachment?.let { attachment ->
                        GridlinkThreadAttachment(
                            attachment = attachment,
                            modifier = Modifier.padding(
                                start = GridlinkSpacing.s20,
                                end = GridlinkSpacing.s20,
                                bottom = GridlinkSpacing.s8,
                            ),
                        )
                    }

                    // Clears the bottom fade so the last line of prose is never half-dissolved.
                    Spacer(Modifier.height(GridlinkDimens.listFade))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = GridlinkSpacing.chrome,
                        top = GridlinkSpacing.s16,
                        end = GridlinkSpacing.chrome,
                        bottom = GridlinkSpacing.chrome,
                    ),
                horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GridlinkThreadActionPill(
                    message = message,
                    onAction = { action ->
                        // 🔴 Unsubscribe is the one action here that talks to someone else. Every
                        // other button rearranges mail that is already on the device, and this one
                        // tells a sender you exist and are reading. It gets asked first.
                        if (action == GridlinkThreadAction.UNSUBSCRIBE) {
                            confirmingUnsubscribe = true
                        } else {
                            onAction(action)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                GridlinkThreadReplyButton(
                    onClick = { onAction(GridlinkThreadAction.REPLY) },
                )
            }
        }
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
 * Back control plus the subject.
 *
 * The subject sits here rather than scrolling with the body on purpose. A subject that scrolls away
 * is fine in a client where the header collapses into a smaller copy of itself, and is just missing
 * in one where it does not: three screens into a long report you would have nothing on screen
 * saying which report. [GridlinkType.threadTitle] exists for this slot, at 18sp rather than the
 * 32sp every other screen title uses, because a real subject line promoted to 32sp wraps to three
 * lines and eats the top third of the window.
 */
@Composable
private fun GridlinkThreadHeader(
    subject: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = GridlinkSpacing.s40,
                bottom = GridlinkSpacing.s20,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GridlinkThreadCircleButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = "Back to the message list",
            onClick = onBack,
        )
        Text(
            text = subject,
            style = GridlinkType.threadTitle,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = GridlinkSpacing.s16),
        )
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
                text = "to $GRIDLINK_SAMPLE_ACCOUNT",
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The body, as rich text.
 *
 * `AnnotatedString.fromHtml` goes through `HtmlCompat` and then maps the spans Compose has an
 * equivalent for, which covers weight, slant, underline, strikethrough, size, sub, sup and links.
 * That is a real rich-text renderer and it is a genuinely limited one: **no tables and no images**,
 * and `BulletSpan`/`QuoteSpan` are dropped silently, so `<ul>` and `<blockquote>` render as plain
 * lines with no error to notice. [GridlinkSampleBodies] therefore sticks to a documented safe tag
 * set and writes bullets as literal characters.
 *
 * ## Why not a WebView
 * A WebView would render anything, and that is the problem. It loads remote content by default,
 * which means every tracking pixel in every marketing email reports back the moment you open it,
 * and it brings its own scroll container, its own text selection and its own idea of colour into
 * the middle of a Compose screen. Real marketing HTML will eventually need one, with remote content
 * blocked until the reader asks for it. That is a privacy decision with a UI attached, not a
 * renderer swap, and it belongs in its own change.
 *
 * 🔴 The link colour comes from the palette. Nothing in the markup is allowed to set a colour: a
 * body carrying `#000000` because it looked right in Day would be invisible in Night.
 */
@Composable
private fun GridlinkThreadBody(
    html: String,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val body = remember(html, colors.accent) {
        AnnotatedString.fromHtml(
            htmlString = html,
            linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = colors.accent,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
        )
    }
    Text(
        text = body,
        style = GridlinkType.body,
        color = colors.textPrimary,
        modifier = modifier,
    )
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
 * 🔴 [REPLY] is the accent circle and the other four share the pill. It is deliberately not in the
 * pill as well: two controls that do the same thing on one 64dp baseline is how you end up tapping
 * the wrong one, and the circle is already the loudest object on the screen.
 */
enum class GridlinkThreadAction { REPLY, REPLY_ALL, FORWARD, ARCHIVE, SPAM, UNSUBSCRIBE }

/**
 * The four secondary actions, in the same shell the nav pill uses on the list.
 *
 * The bottom of every screen in this app is one wide pill plus one accent circle, and a thread that
 * arranged its actions any other way would read as a different product. Reply gets the circle, which
 * is the same relationship the list has between navigation and Compose.
 *
 * ## 🔴 Four, and which four depends on the sender
 * Brandon asked for Reply, Reply all, Forward, Archive, Unsubscribe and Spam. Six will not fit: the
 * pill is about 323dp wide once the circle and the chrome pad are out of it, and six 11sp labels in
 * that space would need "Unsub". So the set is contextual, which is also more honest than a fixed
 * six:
 *
 * - **A person wrote it:** Reply all, Forward, Archive, Spam. There is no unsubscribe link in a mail
 *   from a colleague, and offering one that cannot work is worse than not offering it.
 * - **A machine sent it** ([GridlinkMessage.automated]): Forward, Archive, Unsubscribe, Spam. Reply
 *   all drops out, because replying to everyone on a billing statement means replying to a no-reply
 *   robot and a mailing list, and Reply is still on the circle for the rare one that does read them.
 *
 * ⚠️ The real signal is the `List-Unsubscribe` header, not [GridlinkMessage.automated]. The sample
 * data has no headers, and `automated` is the field that means the same thing here. Swap it when the
 * JMAP store lands, and expect the two to disagree: plenty of genuine bulk mail ships no header at
 * all, and the button has to disappear for those rather than fail.
 */
@Composable
private fun GridlinkThreadActionPill(
    message: GridlinkMessage,
    onAction: (GridlinkThreadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .height(GRIDLINK_PILL_HEIGHT)
            .gridlinkGlow(colors.actionGlow?.copy(alpha = 0.28f), radiusMultiplier = 0.4f)
            .clip(shape)
            // 🔴 Two fills, same as the nav pill. Every palette surface is translucent and a
            // floating control must be opaque, and §9 bans blurring what is behind it.
            .background(colors.background, shape)
            .background(colors.surface, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .padding(GridlinkSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.automated) {
            GridlinkThreadActionItem(
                label = "Unsubscribe",
                icon = Icons.Outlined.Unsubscribe,
                onClick = { onAction(GridlinkThreadAction.UNSUBSCRIBE) },
                modifier = Modifier.weight(1f),
            )
        } else {
            GridlinkThreadActionItem(
                label = "Reply all",
                icon = Icons.AutoMirrored.Outlined.ReplyAll,
                onClick = { onAction(GridlinkThreadAction.REPLY_ALL) },
                modifier = Modifier.weight(1f),
            )
        }
        GridlinkThreadActionItem(
            label = "Forward",
            icon = Icons.AutoMirrored.Outlined.Forward,
            onClick = { onAction(GridlinkThreadAction.FORWARD) },
            modifier = Modifier.weight(1f),
        )
        GridlinkThreadActionItem(
            label = "Archive",
            icon = Icons.Outlined.Archive,
            onClick = { onAction(GridlinkThreadAction.ARCHIVE) },
            modifier = Modifier.weight(1f),
        )
        GridlinkThreadActionItem(
            label = "Spam",
            icon = Icons.Outlined.Report,
            onClick = { onAction(GridlinkThreadAction.SPAM) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GridlinkThreadActionItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(GridlinkRadii.pill))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.textPrimary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = GridlinkType.toolbarLabel,
            color = colors.textPrimary,
            maxLines = 1,
            // "Unsubscribe" is the longest label in the app and it is within about 10dp of its
            // slot on a folded display. Ellipsis rather than a clip so if it ever does run out of
            // room it says so, instead of quietly dropping the last letter and reading as a typo.
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The one live action on the screen, in the slot the Compose button occupies everywhere else. */
@Composable
private fun GridlinkThreadReplyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val size = GridlinkDimens.composeButton
    val fill = gridlinkAccentFill(colors.accent)
    Box(
        modifier = modifier
            .size(size)
            .gridlinkGlow(colors.actionGlow?.copy(alpha = 0.40f), radiusMultiplier = 0.95f)
            .clip(CircleShape)
            .background(fill, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Reply,
            contentDescription = "Reply",
            // Measured against the accent itself, not the gradient built from it. The fill darkens
            // toward its far corner, so testing the brush is not possible and testing the dark end
            // would flip the glyph to white on a pale accent where the lit corner needs black.
            tint = gridlinkOnAccent(colors.accent),
            modifier = Modifier.size(size * 0.41f),
        )
    }
}

/**
 * Header control. Same shape as the composer's discard button, for the same reason: a circle at the
 * top-left of a full-window screen is this app's "get out of here".
 *
 * 🔴 Not a dimmed accent circle. Alpha never encodes state in this design.
 */
@Composable
private fun GridlinkThreadCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = GridlinkDimens.headerControl,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.surface, CircleShape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.textPrimary,
            modifier = Modifier.size(20.dp),
        )
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
 * The recipient is matched out of [GridlinkSampleContacts] by address so a reply to someone in the
 * address book carries their real card, and falls back to a card built from the message header when
 * it does not, which is what a reply to a no-reply robot should look like.
 */
internal fun gridlinkReplyTo(message: GridlinkMessage): GridlinkComposeRequest {
    val known = GridlinkSampleContacts.all.firstOrNull { it.email.equals(message.address, true) }
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
