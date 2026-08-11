package app.gridlink.ui.gridlink

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
 * ## A card first, a mail history second
 * This screen was read-only while nothing behind it could hold an edit; that store exists now
 * ([GridlinkContactWriter], JMAP ContactCard with a CardDAV fallback), so the card leads with what
 * a contact card holds — every address, every phone number, the employer, the note — and Edit is
 * the accent action. Recent mail stays, but below the fields: this is a person's card that also
 * shows their mail, not a mail query wearing a name.
 *
 * ## What is derived and what is invented
 * Nothing here is invented. The identity colour is [GridlinkContact.domain] through the same
 * [gridlinkSenderBarColor] the message rows use, so a counterparty is the same colour on their card
 * as in the list. Recent mail is [GridlinkSample.messagesFrom], matched by
 * [GridlinkSampleContacts.forSender] rather than by address, which is what stops the card from
 * saying "no recent mail" about someone with four messages in the inbox.
 *
 * ## The field rows act, they don't just display
 * Tapping an address opens the composer already addressed to it (each address, not just the
 * primary — that is the point of listing them); tapping a number opens the dialler with it typed
 * in, through [gridlinkDial], which shows the number rather than ringing it. Copy and Write hold the
 * pill's two slots and the accent slot goes to Edit: on a card, changing the card is the headline
 * act. Share used to sit between them and no longer does, for the width reason recorded at the pill.
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
    onEdit: (GridlinkContact) -> Unit,
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
            // 🔴 TWO slots, not three. Tate reported the pill spilling out of the reading pane on
            // the unfolded Fold, and it is these labels that do it: "Copy address" is nearly twice
            // the width of the thread pill's "Forward", so the same three-slot shape that fits over
            // there runs out of room here. Beside the pill the pane also spends a whole
            // [GridlinkDimens.composeButton] on the accent circle plus the travelling "+", so the
            // pill gets less width than the thread's does on the same screen. Two is the ceiling for
            // this card at every width, folded included, so the card does not rearrange itself when
            // the device opens.
            //
            // Share is the one that lost its slot: Copy has no other route to the clipboard, and
            // Write is the card's whole point in a mail app, while sharing a contact is the rarest
            // of the three. It is gone rather than hidden — a More sheet for a single item would be
            // a second tap to reach one action, which is worse than the thread's case where More
            // covers four.
            GridlinkDetailActionPill(modifier = Modifier.weight(1f)) {
                GridlinkDetailActionItem(
                    label = "Copy address",
                    icon = Icons.Outlined.ContentCopy,
                    onClick = { clipboard.setText(AnnotatedString(contact.email)) },
                    modifier = Modifier.weight(1f),
                )
                GridlinkDetailActionItem(
                    label = "Write",
                    icon = Icons.Outlined.Email,
                    onClick = { onWrite(contact) },
                    modifier = Modifier.weight(1f),
                )
            }
            GridlinkDetailAccentButton(
                icon = Icons.Outlined.Edit,
                label = "Edit",
                onClick = { onEdit(contact) },
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
            // answer here. A real photograph is different in kind — it is card DATA the user put
            // there, not decoration invented for them — so when the card carries one it sits at
            // the header's trailing edge, and when it does not, nothing stands in for it.
            val photoBitmap = rememberGridlinkContactPhoto(contact.photo)
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
                    // Absent rather than blank: most real cards carry no address, and an empty
                    // metadata line under the role reads as a rendering bug, not a fact.
                    if (contact.email.isNotBlank()) {
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
                if (photoBitmap != null) {
                    Image(
                        bitmap = photoBitmap,
                        contentDescription = "Contact photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(
                                end = GridlinkSpacing.rowHorizontal,
                                top = GridlinkSpacing.s16,
                                bottom = GridlinkSpacing.s16,
                            )
                            .size(64.dp)
                            .clip(RoundedCornerShape(GridlinkSpacing.s8)),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GridlinkDimens.hairline)
                    .background(colors.divider),
            )

            // The card's fields, each row doing the thing you would want that value for. The
            // company/title pair collapses into the header's role line when there is only one of
            // them, so the row appears only when it adds something the header has not said.
            val fields = buildList {
                contact.allEmails.forEach { address ->
                    add(GridlinkContactField("Email", address) { onWrite(contact.copy(email = address)) })
                }
                contact.phones.forEach { number ->
                    add(GridlinkContactField("Phone", number) { leaveOnce { gridlinkDial(context, number) } })
                }
                // Same map handoff as an event's Where row, same `geo:` reasoning. Read-only
                // display: the edit form has no address field, and ADR belongs to no
                // ContactCardGroup, so a patched card keeps its ADR lines byte-for-byte.
                contact.addresses.forEach { place ->
                    add(GridlinkContactField("Address", place) { leaveOnce { openMap(context, place) } })
                }
                if (contact.company.isNotBlank() && contact.company != contact.role) {
                    add(GridlinkContactField("Company", contact.company, null))
                }
                // User-defined fields, after the built-ins: the label is the user's own word for
                // the value, so it renders exactly as typed, in the same caption slot "Email" uses.
                contact.customFields.forEach { field ->
                    add(GridlinkContactField(field.label, field.value, null))
                }
            }
            if (fields.isNotEmpty()) {
                GridlinkSectionLabel(text = "Details")
                // No divider between rows: each field is its own contained box now, and the boxes'
                // margins do the separating, the same language as the contact form.
                fields.forEach { field ->
                    GridlinkContactFieldRow(field)
                }
            }

            if (contact.note.isNotBlank()) {
                GridlinkSectionLabel(text = "Note")
                Text(
                    text = contact.note,
                    style = GridlinkType.body,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(
                        start = GridlinkSpacing.rowHorizontal,
                        end = GridlinkSpacing.rowHorizontal,
                        bottom = GridlinkSpacing.s16,
                    ),
                )
            }

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
 * Built here rather than copied from [GridlinkComposeDraft.Fresh] because the title differs: this is
 * a "New message" to a named person, not the compose button's blank sheet.
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

/** One field on the card: what it is, what it says, and what tapping it does (null: nothing). */
private class GridlinkContactField(
    val label: String,
    val value: String,
    val onClick: (() -> Unit)?,
)

/**
 * One field on the card: a [GridlinkFieldLabelPill] naming it, the value in a contained box below.
 * The box is [GridlinkFieldBoxShape] with a hairline border and no underline — a card is read, not
 * typed into, and the underline is reserved for the keyboard's fields. When the field does
 * something on tap (write, dial), the whole box is the target.
 */
@Composable
private fun GridlinkContactFieldRow(
    field: GridlinkContactField,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.s8,
            ),
    ) {
        GridlinkFieldLabelPill(field.label)
        Spacer(Modifier.height(GridlinkSpacing.s8))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GridlinkFieldBoxShape)
                .background(colors.fieldFill)
                .border(GridlinkDimens.hairline, colors.surfaceBorder, GridlinkFieldBoxShape)
                .let { if (field.onClick != null) it.clickable(onClick = field.onClick) else it }
                .padding(
                    horizontal = GridlinkSpacing.s16,
                    vertical = GridlinkSpacing.s12,
                ),
        ) {
            Text(
                text = field.value,
                style = GridlinkType.body,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One message on a contact card.
 *
 * 🔴 Not [GridlinkMessageRow]. That row leads with the sender name and an identity bar, and on a card
 * about one person both are the same on every row: a column of "Miriam Ridley" under a heading that
 * already says Miriam Ridley, striped with four copies of one colour. What is left when those go is
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
