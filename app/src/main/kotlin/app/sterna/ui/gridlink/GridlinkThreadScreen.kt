package app.sterna.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val panelShape = RoundedCornerShape(GridlinkRadii.card)

    GridlinkBackground(modifier = modifier) {
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
                GridlinkThreadActionPill(modifier = Modifier.weight(1f))
                GridlinkThreadReplyButton(onClick = onReply)
            }
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
 * The three secondary actions, in the same shell the nav pill uses on the list.
 *
 * The bottom of every screen in this app is one wide pill plus one accent circle, and a thread that
 * arranged its actions any other way would read as a different product. So: Reply all, Forward and
 * Archive share the pill, and Reply gets the circle, which is the same relationship the list has
 * between navigation and Compose.
 *
 * ⚠️ All three are inert. Archive in particular cannot work yet: the list owns its own copy of the
 * messages, so nothing here can remove a row from it. Wiring these means hoisting that state out of
 * [GridlinkMessageListScreen], which is the same change the real JMAP store will force anyway.
 */
@Composable
private fun GridlinkThreadActionPill(modifier: Modifier = Modifier) {
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
        GridlinkThreadActionItem(
            label = "Reply all",
            icon = Icons.AutoMirrored.Outlined.ReplyAll,
            modifier = Modifier.weight(1f),
        )
        GridlinkThreadActionItem(
            label = "Forward",
            icon = Icons.AutoMirrored.Outlined.Forward,
            modifier = Modifier.weight(1f),
        )
        GridlinkThreadActionItem(
            label = "Archive",
            icon = Icons.Outlined.Archive,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GridlinkThreadActionItem(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(GridlinkRadii.pill)),
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
 * 🔴 It does not reuse [GridlinkComposeDraft.Reply], which is hard-wired to the Ridley thread for
 * the brief's §1d mockup. Opening the Dalton Energy statement and getting a reply addressed to Ridley
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
