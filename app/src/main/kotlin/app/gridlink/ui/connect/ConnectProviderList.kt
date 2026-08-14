package app.gridlink.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.R
import app.gridlink.ui.gridlink.gridlinkAccentFill
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * 🔴 [GridlinkDimens.messageRowHeight], not a number of its own. These rows are the same object the
 * dense list is made of (an icon, a name, a second line), and a setup screen that quietly used a
 * different row height would be the only place in the app where a row is not 64dp.
 */
private val ROW_HEIGHT = GridlinkDimens.messageRowHeight

/** The logo well. Sized to leave the row's own padding intact at [ROW_HEIGHT]. */
private val CHIP_SIZE = 40.dp
private val LOGO_SIZE = 26.dp

/** The tick disc on the chosen row. Matches the dense list's selection disc. */
private val TICK_DISC = 24.dp
private val TICK_GLYPH = 16.dp

/**
 * 🔴 [GridlinkRadii.field], NOT [GridlinkRadii.card]. These boxes sit directly above the form's
 * entry fields on the same screen, and the two disagreeing about their corners is the single most
 * visible way a screen stops looking like it was drawn by one hand.
 */
private val ROW_SHAPE = RoundedCornerShape(GridlinkRadii.field)
private val CHIP_SHAPE = RoundedCornerShape(GridlinkRadii.field)

/**
 * Brand colours, used ONLY as the lettermark background while [MailProvider.logoRes] is null, which
 * today is no provider at all. Kept for the next one added before its artwork arrives: a column of
 * identical grey squares would be no more scannable than the text chips this list replaced.
 */
private val PROVIDER_BRAND_COLORS: Map<String, Color> = mapOf(
    "Gmail" to Color(0xFFEA4335),
    "Outlook" to Color(0xFF0078D4),
    "Yahoo" to Color(0xFF7D2EFF),
    "iCloud" to Color(0xFF3693F3),
    "Fastmail" to Color(0xFF0067B9),
    "Proton Bridge" to Color(0xFF6D4AFF),
)

/**
 * The list of setup choices: JMAP, IMAP, then every known provider, one full-width row each.
 *
 * This is one question where there used to be two. The protocol lived in a chip row and the
 * providers in a second row that only existed while IMAP was selected, so the shortcut for the
 * commonest case (a Gmail address) was hidden behind a decision about protocols that the person
 * adding the account has no reason to have an opinion about.
 *
 * Rows rather than tiles because a row can afford to say what it will do: the logo, the provider's
 * name and the server it is about to dial, all on one line the eye reads left to right. A tile that
 * fits three across can only afford the name, and half the names had to be ellipsised to fit.
 */
@Composable
internal fun SetupChoiceList(
    selected: SetupChoice,
    onChoose: (SetupChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Plain Column, not LazyColumn: this sits inside the form's verticalScroll, and a lazy list
    // nested in a scrolling parent has no bounded height to measure against.
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
    ) {
        SETUP_CHOICES.forEach { choice ->
            SetupChoiceRow(
                choice = choice,
                selected = choice == selected,
                onClick = { onChoose(choice) },
            )
        }
    }
}

/**
 * One row: logo chip, name, the server it fills in, and a tick when it is the chosen one.
 *
 * ## 🔴 Built from the palette tokens, with no shadows and no invented colours
 * The first cut of this list was hand-mixed glass: white-alpha gradients, a gradient edge and a
 * coloured drop shadow. It looked good in exactly the one mode it was tuned against, and it broke
 * OLED's rule outright — that mode defines a surface BY its hairline, never by a lighter fill, and
 * sets `usesShadows = false`. A resting row is therefore [GridlinkColors.fieldFill] (the token whose
 * whole job is lifting a contained box off the panel in a form) inside a [GridlinkDimens.hairline]
 * of [GridlinkColors.surfaceBorder], and a chosen one is [GridlinkColors.selection] inside a
 * hairline of the accent. Both follow the palette wherever Tate takes it.
 */
@Composable
private fun SetupChoiceRow(
    choice: SetupChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(ROW_SHAPE)
            .background(if (selected) colors.selection else colors.fieldFill, ROW_SHAPE)
            .border(
                GridlinkDimens.hairline,
                if (selected) colors.accent else colors.surfaceBorder,
                ROW_SHAPE,
            )
            // Selection is announced by the fill, the edge and the tick, and none of the three
            // reaches TalkBack; this is what makes the list read as a set of choices.
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .clickable(onClick = onClick)
            .padding(horizontal = GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s12),
    ) {
        ChoiceChip(choice)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (choice) {
                    SetupChoice.Jmap -> stringResource(R.string.connect_jmap)
                    SetupChoice.Imap -> stringResource(R.string.connect_imap_smtp)
                    // Brand labels are names, never translated.
                    is SetupChoice.Known -> choice.provider.name
                },
                style = GridlinkType.senderName,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The host, not a sentence about it: it is the one fact the row is about to write into
            // the form, it is the same in every language, and it needs no translation to be read.
            if (choice is SetupChoice.Known) {
                Text(
                    text = choice.provider.imapHost,
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(TICK_DISC)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    // A palette value, never a luminance guess: OLED's accent needs a dark glyph.
                    tint = colors.onAccent,
                    modifier = Modifier.size(TICK_GLYPH),
                )
            }
        }
    }
}

/** The logo well: a bundled logo, an accent glyph for the two manual routes, or a lettermark. */
@Composable
private fun ChoiceChip(choice: SetupChoice) {
    val colors = GridlinkTheme.colors
    when (choice) {
        SetupChoice.Jmap -> GlyphChip(Icons.Filled.CloudQueue)
        SetupChoice.Imap -> GlyphChip(Icons.Filled.Dns)
        is SetupChoice.Known -> {
            val logo = choice.provider.logoRes
            Box(
                modifier = Modifier
                    .size(CHIP_SIZE)
                    .clip(CHIP_SHAPE)
                    // 🔴 The one deliberate exception to "no colour that is not a token": brand
                    // artwork is drawn to sit on white and is not ours to recolour, so the plate
                    // under it is white in every mode. Everything AROUND it is still the palette.
                    .background(if (logo != null) Color.White else Color.Transparent, CHIP_SHAPE)
                    .border(GridlinkDimens.hairline, colors.surfaceBorder, CHIP_SHAPE),
                contentAlignment = Alignment.Center,
            ) {
                if (logo != null) {
                    Image(
                        painter = painterResource(logo),
                        contentDescription = null,
                        modifier = Modifier.size(LOGO_SIZE),
                    )
                } else {
                    Lettermark(choice.provider.name, colors.accent)
                }
            }
        }
    }
}

/** The two manual routes get the accent, not a logo: they are protocols, not brands. */
@Composable
private fun GlyphChip(icon: ImageVector) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = Modifier
            .size(CHIP_SIZE)
            .clip(CHIP_SHAPE)
            .background(gridlinkAccentFill(colors.accent), CHIP_SHAPE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.onAccent,
            modifier = Modifier.size(LOGO_SIZE),
        )
    }
}

/** A provider's initial on its brand colour, standing in until a real logo is bundled. */
@Composable
private fun Lettermark(name: String, fallback: Color) {
    val colors = GridlinkTheme.colors
    val known = PROVIDER_BRAND_COLORS.containsKey(name)
    Box(
        modifier = Modifier
            .size(CHIP_SIZE)
            .clip(CHIP_SHAPE)
            .background(PROVIDER_BRAND_COLORS[name] ?: fallback, CHIP_SHAPE),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // "iCloud" starts with the character people read as the brand, so the first character
            // is taken as written rather than upper-cased.
            name.take(1),
            style = GridlinkType.senderName,
            // A brand colour is not the accent, so its glyph cannot read onAccent; white is right
            // for all six. The fallback IS the accent, and that one takes the palette's own answer.
            color = if (known) Color.White else colors.onAccent,
        )
    }
}
