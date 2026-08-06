package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.rememberLeaveOnce
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import app.gridlink.ui.theme.gridlinkSenderBarColor

/**
 * One contact, opened from the Contacts tab.
 *
 * ## 🔴 Read-only, on purpose, and this is the whole reason
 * Brandon chose read-only over an edit mode when asked, and the reasoning is worth keeping: nothing
 * in this app talks to a server yet, so a Save button would write a change into memory, show a
 * success, and lose it on the next launch. That is not an unfinished feature, it is a screen that
 * lies about what it did. The card can be honest about everything it shows because everything it
 * shows is already true: a name, a role, an address, and the mail that actually arrived.
 *
 * Edit lands when there is a CardDAV or JMAP contacts store behind it to fail against.
 *
 * ## What is derived and what is invented
 * Nothing here is invented. The identity colour is [GridlinkContact.domain] through the same
 * [gridlinkSenderBarColor] the message rows use, so a counterparty is the same colour on their card
 * as in the list. Recent mail is [GridlinkSample.messagesFrom], matched by
 * [GridlinkSampleContacts.forSender] rather than by address, which is what stops the card from
 * saying "no recent mail" about someone with four messages in the inbox.
 *
 * ## Why the actions are Copy, Share and Write, and not Find mail
 * Copy and Share are the two things you actually do with someone else's address, and both are real:
 * one uses the system clipboard, the other the system share sheet. Write opens the composer already
 * addressed to them, through the same [GridlinkComposeRequest] a reply uses.
 *
 * ⚠️ A "Find mail" button was considered and dropped. The search field is private state inside
 * [GridlinkMessageListScreen], so prefilling it means hoisting search state through the scaffold to
 * serve one button, and the recent-mail list already answers the question that button would ask.
 */
@Composable
fun GridlinkContactScreen(
    contact: GridlinkContact,
    onBack: () -> Unit,
    onOpenMessage: (GridlinkMessage) -> Unit,
    onWrite: (GridlinkContact) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // The share sheet is the one place these cards leave the app, and a card is not a nav
    // destination, so this is the activity-scoped guard: it latches until we are RESUMED again.
    val leaveOnce = rememberLeaveOnce()
    val recent = remember(contact.id) { GridlinkSample.messagesFrom(contact) }

    GridlinkDetailFrame(
        title = contact.displayName,
        onBack = onBack,
        modifier = modifier,
        embedded = embedded,
        bottom = {
            GridlinkDetailActionPill(modifier = Modifier.weight(1f)) {
                GridlinkDetailActionItem(
                    label = "Copy address",
                    icon = Icons.Outlined.ContentCopy,
                    onClick = { clipboard.setText(AnnotatedString(contact.email)) },
                    modifier = Modifier.weight(1f),
                )
                GridlinkDetailActionItem(
                    label = "Share",
                    icon = Icons.Outlined.Share,
                    onClick = {
                        leaveOnce {
                            gridlinkShare(
                                context = context,
                                subject = contact.displayName,
                                text = "${contact.displayName}\n${contact.email}",
                                chooserTitle = "Share contact",
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            GridlinkDetailAccentButton(
                icon = Icons.Outlined.Edit,
                label = "Write",
                onClick = { onWrite(contact) },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .gridlinkEdgeFade(fadeTop = false),
        ) {
            // The identity block. No avatar and no initials disc: §9's ban on them is about the
            // list, but the reason behind it is not, and a card that answers "who is this" twice
            // answers it worse. The bar is the answer everywhere else in the app, so it is the
            // answer here.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    modifier = Modifier
                        .width(GridlinkDimens.senderBarWidth)
                        .fillMaxHeight()
                        .background(gridlinkSenderBarColor(mode, contact.domain)),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            horizontal = GridlinkSpacing.rowHorizontal,
                            vertical = GridlinkSpacing.s16,
                        ),
                ) {
                    Text(
                        text = contact.role,
                        style = GridlinkType.senderName,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = contact.email,
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = GridlinkSpacing.s4),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GridlinkDimens.hairline)
                    .background(colors.divider),
            )

            GridlinkSectionLabel(text = "Recent mail")

            if (recent.isEmpty()) {
                Text(
                    // ⚠️ States what is missing, not what went wrong. Most of the address book has
                    // never written, and a card that reads as an error for the ordinary case teaches
                    // the user to distrust the ones that are correct.
                    text = "Nothing from this address yet.",
                    style = GridlinkType.body,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(
                        start = GridlinkSpacing.rowHorizontal,
                        end = GridlinkSpacing.rowHorizontal,
                        bottom = GridlinkSpacing.s16,
                    ),
                )
            } else {
                recent.forEachIndexed { index, message ->
                    GridlinkContactMessageRow(
                        message = message,
                        onClick = { onOpenMessage(message) },
                    )
                    if (index != recent.lastIndex) {
                        GridlinkRowDivider(startInset = GridlinkSpacing.rowHorizontal)
                    }
                }
            }

            // Clears the bottom fade so the last row is never half-dissolved.
            Spacer(Modifier.height(GridlinkDimens.listFade))
        }
    }
}

/**
 * Opens the composer addressed to [contact].
 *
 * The recipient is already a chip, so focus starts on the subject rather than on TO. Anything else
 * would put the caret in a field that is finished and make the first keystroke look like it is
 * about to add a second person.
 *
 * 🔴 Not [GridlinkComposeDraft.Fresh] with a recipient stuck on. Fresh deliberately seeds the query
 * "ma" to demonstrate the suggestion list; carried into a message you asked to address to one
 * specific person, that opens a composer with a half-typed search for somebody else in it.
 */
internal fun gridlinkWriteTo(contact: GridlinkContact): GridlinkComposeRequest = GridlinkComposeRequest(
    draft = GridlinkComposeDraft(
        title = "New message",
        recipients = listOf(contact),
        recipientQuery = "",
        subject = "",
        body = "",
        quoted = null,
        attachments = emptyList(),
    ),
    focus = GridlinkComposeField.SUBJECT,
)

/**
 * One message on a contact card.
 *
 * 🔴 Not [GridlinkMessageRow]. That row leads with the sender name and an identity bar, and on a card
 * about one person both are the same on every row: a column of "Marisol Rivera" under a heading that
 * already says Marisol Rivera, striped with four copies of one colour. What is left when those go is
 * the subject and when it arrived, which is exactly what you are scanning this list for, so it fits
 * on one line at [GridlinkDimens.compactRow] instead of two at 64.
 *
 * The read and unread treatment is the list's, unchanged: a colour and weight step on the text, an
 * [app.gridlink.ui.theme.GridlinkColors.attention] dot hard against the trailing edge, never an alpha
 * fade.
 */
@Composable
private fun GridlinkContactMessageRow(
    message: GridlinkMessage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.compactRow)
            .clickable(onClick = onClick)
            .padding(horizontal = GridlinkSpacing.rowHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message.subject,
            style = GridlinkType.subject.copy(
                fontWeight = if (message.unread) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (message.unread) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (message.hasAttachment) {
            Icon(
                imageVector = Icons.Filled.AttachFile,
                contentDescription = "Has attachment",
                tint = colors.textSecondary,
                modifier = Modifier
                    .padding(start = GridlinkSpacing.s8)
                    .size(14.dp),
            )
        }
        Text(
            text = message.timestamp,
            style = GridlinkType.timestamp,
            color = if (message.unread) colors.attention else colors.textSecondary,
            modifier = Modifier.padding(start = GridlinkSpacing.s8),
        )
        if (message.unread) {
            Spacer(Modifier.width(GridlinkSpacing.s8))
            Box(
                modifier = Modifier
                    .size(GridlinkDimens.unreadDot)
                    .background(colors.attention, CircleShape),
            )
        }
    }
}
