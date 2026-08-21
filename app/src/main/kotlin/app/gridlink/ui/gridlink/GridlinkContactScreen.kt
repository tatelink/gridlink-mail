package app.gridlink.ui.gridlink

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * ⚠️ And that panel is FIXTURE-ONLY, gated on [GridlinkBook.contactsLive] exactly as the event
 * screen gates its mail panel: [GridlinkSample.messagesFrom] can only ever answer for the sample's
 * people, so on a real address book it would say "Nothing from this address yet." about everyone,
 * a confident wrong statement about somebody who may have written. A live card draws no Recent
 * mail section at all; the real version is a mailbox query, a different piece of work.
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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // The share sheet is the one place these cards leave the app, and a card is not a nav
    // destination, so this is the activity-scoped guard: it latches until we are RESUMED again.
    val leaveOnce = rememberLeaveOnce()
    val book = LocalGridlinkBook.current
    // See the header: fixture-only, and absent (not empty) on a live address book.
    val recent = remember(contact.id, book) {
        if (book.contactsLive) emptyList() else GridlinkSample.messagesFrom(contact)
    }

    GridlinkDetailFrame(
        // 🔴 No pane title in two panes: the hero prints this same name at four times the size a few
        // pixels below it. *"top of screen is duplicate name of person - its not needed."* The
        // standing screen keeps it, because there the title shares a row with the back button and a
        // lone arrow over a photo says nothing about where back goes. A thread needs the band (its
        // subject is nowhere else once the header moved out of the glass); a contact card does not.
        title = if (embedded) "" else contact.displayName,
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
                // 🔴 "Copy", not "Copy address". The longer words did not fit their slot on the
                // unfolded Fold and the pill's clip sliced the last letter in half: *"'copy address'
                // is truncated"*, 2026-08-16. Ellipsis (now fixed to actually fire) would only have
                // turned a sliced letter into "Copy addre…", which is not better. On a card whose
                // subject IS an address, beside a copy glyph, one word says it. The spoken label
                // keeps the full phrase.
                GridlinkDetailActionItem(
                    label = "Copy",
                    contentDescription = "Copy address",
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
            GridlinkContactHero(contact = contact)

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

            if (!book.contactsLive) {
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

/**
 * The card's hero: the photograph full-bleed across the top, the person's name across it.
 *
 * ## Why this replaced the old header
 * Tate, looking at a real card: *"in contacts, the image needs to be a hero card along with a
 * huge name of the person. Right now its very plain and boring."* The old header was a 64dp thumbnail
 * parked at the trailing edge of a two-line block, which is the treatment a list row gets. On the
 * screen that exists to be about ONE person, the photograph is the content and it was being filed as
 * metadata. The name was not on the card at all — only in the frame's title bar, at chrome size,
 * where it reads as a label for the screen rather than as the person.
 *
 * ## 🔴 This does not reopen §9's ban on avatars
 * That ban is about the list, and about INVENTED identity: initials discs and generated blobs
 * standing in for people. Nothing here is invented. The photograph is card data the user or the
 * server put there, and the card with no photograph gets no fake one — it gets a field of that
 * contact's own identity colour, the same [gridlinkSenderBarColor] their bar has in every list in
 * the app, so the card is recognisably theirs without pretending to a picture that does not exist.
 * The bar itself stays down the leading edge in both cases, which is what ties the hero back to the
 * rest of the app.
 *
 * ## Legibility over a photograph nobody vetted
 * A contact photo is arbitrary: it can be a white wall or a snowfield, and white text on it would
 * be gone. Hence the scrim, a vertical wash to near-black at the bottom where the text sits, plus a
 * soft shadow on the glyphs themselves for the case where the photo is busy rather than bright. Both
 * are cheap and neither depends on inspecting the image.
 *
 * ⚠️ The text over the hero is white in all three modes, and that is not a palette leak: it is white
 * because it sits on the scrim, which is black in all three modes for the reason above. Pulling
 * `textPrimary` in here would make Day mode's near-black text invisible on its own scrim.
 */
@Composable
private fun GridlinkContactHero(
    contact: GridlinkContact,
    modifier: Modifier = Modifier,
) {
    val mode = GridlinkTheme.mode
    val identity = gridlinkSenderBarColor(mode, contact.domain)
    val photo = rememberGridlinkContactPhoto(contact.photo)
        ?: rememberGridlinkSampleContactPhoto(contact.id)
    val name = contact.displayName
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Taller with a picture than without. A card holding a photograph is showing you
            // something and wants the room; a card with only a colour field would be spending the
            // same 300dp saying nothing, which is how a hero turns into dead space.
            .height(if (photo != null) HERO_PHOTO_HEIGHT else HERO_PLAIN_HEIGHT),
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = "Photo of $name",
                contentScale = ContentScale.Crop,
                // Faces sit in the top half of a portrait, and the bottom is where the name lands.
                // Cropping from the top keeps the head and spends the floor.
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(identity.deepen(HERO_FIELD_TOP), identity.deepen(HERO_FIELD_BOTTOM)),
                        ),
                    ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        HERO_SCRIM_KNEE to Color.Black.copy(alpha = HERO_SCRIM_MID),
                        1f to Color.Black.copy(alpha = HERO_SCRIM_FOOT),
                    ),
                ),
        )
        // The identity bar, unchanged in job and width, running the full height of the image. It is
        // drawn OVER the photo rather than beside it so the picture stays full-bleed: a hero inset
        // by 3dp on one side reads as a photo that failed to load square.
        Box(
            modifier = Modifier
                .width(GridlinkDimens.senderBarWidth)
                .fillMaxHeight()
                .background(identity),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = GridlinkSpacing.rowHorizontal,
                    end = GridlinkSpacing.rowHorizontal,
                    bottom = GridlinkSpacing.s16,
                ),
        ) {
            Text(
                text = name,
                style = gridlinkHeroNameStyle(name),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (contact.role.isNotBlank()) {
                Text(
                    text = contact.role,
                    style = GridlinkType.senderName,
                    color = Color.White.copy(alpha = HERO_ROLE_ALPHA),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = GridlinkSpacing.s8),
                )
            }
            // Absent rather than blank: most real cards carry no address, and an empty metadata
            // line under the role reads as a rendering bug, not a fact.
            if (contact.email.isNotBlank()) {
                Text(
                    text = contact.email,
                    style = GridlinkType.metadata,
                    color = Color.White.copy(alpha = HERO_EMAIL_ALPHA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = GridlinkSpacing.s4),
                )
            }
        }
    }
}

/**
 * [GridlinkType.heroName] stepped down to fit the name it has to carry.
 *
 * 🔴 Steps, not [androidx.compose.foundation.text.BasicText]'s auto-sizing. A ladder of three sizes
 * means two cards opened one after the other either match or differ visibly, where continuous
 * shrinking gives every card its own slightly-off size and the type scale stops being a scale.
 * The thresholds are the width the longest of them fills on a folded phone, which is the narrowest
 * this card is ever drawn at, so a name that fits here fits everywhere.
 */
private fun gridlinkHeroNameStyle(name: String): TextStyle = when {
    name.length <= HERO_NAME_FULL -> GridlinkType.heroName
    name.length <= HERO_NAME_MID -> GridlinkType.heroName.copy(fontSize = 32.sp, lineHeight = 36.sp)
    else -> GridlinkType.heroName.copy(fontSize = 26.sp, lineHeight = 30.sp)
}

/**
 * [this] multiplied toward black by [factor].
 *
 * Multiplying rather than blending toward a fixed dark keeps the hue: an identity colour is the one
 * thing on this card that says WHICH person, and a blend to charcoal walks the light end of the ramp
 * (`#94A3B8`, `#7DD3FC`) into the same grey. It also guarantees the result is dark enough for white
 * text no matter where in the ramp it came from, which a blend cannot promise.
 */
private fun Color.deepen(factor: Float): Color =
    Color(red = red * factor, green = green * factor, blue = blue * factor, alpha = alpha)

private val HERO_PHOTO_HEIGHT = 300.dp
private val HERO_PLAIN_HEIGHT = 168.dp

private const val HERO_FIELD_TOP = 0.62f
private const val HERO_FIELD_BOTTOM = 0.26f

/** Where the scrim starts doing real work: above this the photograph is untouched. */
private const val HERO_SCRIM_KNEE = 0.45f
private const val HERO_SCRIM_MID = 0.20f
private const val HERO_SCRIM_FOOT = 0.78f

private const val HERO_ROLE_ALPHA = 0.88f
private const val HERO_EMAIL_ALPHA = 0.72f

private const val HERO_NAME_FULL = 16
private const val HERO_NAME_MID = 26

/** One field on the card: what it is, what it says, and what tapping it does (null: nothing). */
private class GridlinkContactField(
    val label: String,
    val value: String,
    val onClick: (() -> Unit)?,
)

/**
 * One field on the card: its name in a caption, the value under it, both inside one
 * [GridlinkFieldBoxShape] box with a hairline border.
 *
 * Deliberately the same object the form's rows are, since 2026-08-16 — reading a contact and
 * editing one are two states of the same card, and they used to disagree about where a label goes.
 * When the field does something on tap (write, dial), the whole box is the target.
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GridlinkFieldBoxShape)
                .background(colors.fieldFill)
                .border(GridlinkDimens.hairline, colors.fieldBorder, GridlinkFieldBoxShape)
                .let { if (field.onClick != null) it.clickable(onClick = field.onClick) else it }
                .padding(
                    horizontal = GridlinkSpacing.s16,
                    vertical = GridlinkSpacing.s12,
                ),
        ) {
            Text(
                text = field.label,
                style = GridlinkType.fieldLabel,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GridlinkSpacing.s4))
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
