package app.gridlink.ui.connect

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.R
import app.gridlink.ui.theme.GridlinkSpacing

/** One row per choice, tall enough for a 46dp logo chip over two lines of text with air around it. */
private val ROW_HEIGHT = 68.dp

/** The logo well: a white chip, so a brand's own colours land on the ground they were drawn for. */
private val CHIP_SIZE = 46.dp
private val LOGO_SIZE = 30.dp

private val ROW_SHAPE = RoundedCornerShape(20.dp)
private val CHIP_SHAPE = RoundedCornerShape(15.dp)

/**
 * Brand colours, used ONLY as the lettermark background while [MailProvider.logoRes] is null, which
 * today is no provider at all. Kept for the next one added before its artwork arrives: eight
 * identical grey squares would be no more scannable than the text chips this list replaced.
 */
private val PROVIDER_BRAND_COLORS: Map<String, Color> = mapOf(
    "Gmail" to Color(0xFFEA4335),
    "Outlook" to Color(0xFF0078D4),
    "Yahoo" to Color(0xFF7D2EFF),
    "iCloud" to Color(0xFF3693F3),
    "Fastmail" to Color(0xFF0067B9),
    "Yandex" to Color(0xFFFC3F1D),
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
    Box(modifier = modifier.fillMaxWidth()) {
        GlassBackdrop(Modifier.matchParentSize())
        // Plain Column, not LazyColumn: this sits inside the form's verticalScroll, and a lazy list
        // nested in a scrolling parent has no bounded height to measure against.
        Column(verticalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8)) {
            SETUP_CHOICES.forEach { choice ->
                SetupChoiceRow(
                    choice = choice,
                    selected = choice == selected,
                    onClick = { onChoose(choice) },
                )
            }
        }
    }
}

/**
 * The wash the glass has something to be glass over. Two soft blooms in the theme's own accent
 * colours, sized to sit behind the list rather than to be looked at: without them the translucent
 * rows have nothing behind them and read as flat grey cards.
 *
 * [Modifier.blur] is a no-op below API 31. That is fine here, because a radial gradient is already
 * soft; the blur only takes the last edge off.
 */
@Composable
private fun GlassBackdrop(modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .blur(48.dp)
            .background(
                Brush.linearGradient(
                    0f to scheme.primary.copy(alpha = 0.22f),
                    0.45f to scheme.tertiary.copy(alpha = 0.14f),
                    1f to scheme.secondary.copy(alpha = 0.18f),
                ),
            ),
    )
}

/**
 * One row: logo chip, name, the server it fills in, and a tick when it is the chosen one.
 *
 * The glass is three layers that have to stay in this order: a shadow that is allowed to spill
 * outside the shape, the translucent fill clipped to it, then a hairline that is brighter at the
 * top than the bottom. That top-lit edge is what makes a flat translucent rectangle read as a pane
 * of glass rather than as a washed-out card.
 */
@Composable
private fun SetupChoiceRow(
    choice: SetupChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.surface.luminance() > 0.5f
    // The chip plate stays near-white in both themes. Brand artwork is drawn to sit on white, and a
    // translucent plate on a dark theme turns every logo into a grey smudge.
    val plate = if (light) Color.White else Color.White.copy(alpha = 0.93f)

    val fill = when {
        selected && light -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.72f)))
        light -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.74f), Color.White.copy(alpha = 0.48f)))
        selected -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.13f)))
        else -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.17f), Color.White.copy(alpha = 0.07f)))
    }
    val edge = when {
        selected -> Brush.verticalGradient(
            listOf(scheme.primary.copy(alpha = 0.85f), scheme.primary.copy(alpha = 0.35f)),
        )
        light -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f)),
        )
        else -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.36f), Color.White.copy(alpha = 0.10f)),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .shadow(
                elevation = if (selected) 12.dp else 5.dp,
                shape = ROW_SHAPE,
                // The shadow carries the accent when selected, so the chosen row lifts off the page
                // instead of only changing colour.
                ambientColor = if (selected) scheme.primary else Color.Black,
                spotColor = if (selected) scheme.primary else Color.Black,
            )
            .clip(ROW_SHAPE)
            .background(fill)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, edge), ROW_SHAPE)
            // Selection is announced by the fill, the edge and the tick, and none of the three
            // reaches TalkBack; this is what makes the list read as a set of choices.
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s12),
    ) {
        ChoiceChip(choice, plate, light)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (choice) {
                    SetupChoice.Jmap -> stringResource(R.string.connect_jmap)
                    SetupChoice.Imap -> stringResource(R.string.connect_imap_smtp)
                    // Brand labels are names, never translated.
                    is SetupChoice.Known -> choice.provider.name
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The host, not a sentence about it: it is the one fact the row is about to write into
            // the form, it is the same in every language, and it needs no translation to be read.
            if (choice is SetupChoice.Known) {
                Text(
                    text = choice.provider.imapHost,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** The logo well: a bundled logo, a themed glyph for the two manual routes, or a lettermark. */
@Composable
private fun ChoiceChip(choice: SetupChoice, plate: Color, light: Boolean) {
    val scheme = MaterialTheme.colorScheme
    when (choice) {
        SetupChoice.Jmap -> GlyphChip(Icons.Filled.CloudQueue)
        SetupChoice.Imap -> GlyphChip(Icons.Filled.Dns)
        is SetupChoice.Known -> {
            val logo = choice.provider.logoRes
            Box(
                modifier = Modifier
                    .size(CHIP_SIZE)
                    .clip(CHIP_SHAPE)
                    // Brand artwork is drawn for white. On a dark theme the chip stays light rather
                    // than the logos being tinted, because a recoloured logo is the wrong logo.
                    .background(if (logo != null) plate else Color.Transparent)
                    .border(
                        1.dp,
                        when {
                            logo != null -> Color.Black.copy(alpha = 0.07f)
                            light -> Color.Black.copy(alpha = 0.06f)
                            else -> Color.White.copy(alpha = 0.14f)
                        },
                        CHIP_SHAPE,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (logo != null) {
                    Image(
                        painter = painterResource(logo),
                        contentDescription = null,
                        modifier = Modifier.size(LOGO_SIZE),
                    )
                } else {
                    Lettermark(choice.provider.name, scheme.secondaryContainer)
                }
            }
        }
    }
}

/** The two manual routes get a themed glyph, not a logo: they are protocols, not brands. */
@Composable
private fun GlyphChip(icon: ImageVector) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(CHIP_SIZE)
            .clip(CHIP_SHAPE)
            .background(
                Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = scheme.onPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** A provider's initial on its brand colour, standing in until a real logo is bundled. */
@Composable
private fun Lettermark(name: String, fallback: Color) {
    val brand = PROVIDER_BRAND_COLORS[name] ?: fallback
    Box(
        modifier = Modifier
            .size(CHIP_SIZE)
            .clip(CHIP_SHAPE)
            .background(brand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // "iCloud" starts with the character people read as the brand, so the first character
            // is taken as written rather than upper-cased.
            name.take(1),
            style = MaterialTheme.typography.titleMedium,
            color = if (PROVIDER_BRAND_COLORS.containsKey(name)) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}
