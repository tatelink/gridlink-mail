package app.gridlink.ui.connect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.R
import app.gridlink.ui.theme.GridlinkSpacing

/** Tiles per row. Three fits the shortest provider names at 320dp without truncating. */
private const val SETUP_GRID_COLUMNS = 3

/** The logo well inside a tile, and the lettermark that stands in for a missing logo. */
private val LOGO_SIZE = 44.dp

/**
 * Brand colours, used ONLY as the lettermark background while [MailProvider.logoRes] is null. They
 * are the providers' own marketing colours, taken from their brand pages, so a tile reads as the
 * right service at a glance instead of as eight identical grey squares.
 *
 * Anything not listed falls back to the app's own secondary container, which is the honest answer
 * for a provider we have no brand information about.
 */
private val PROVIDER_BRAND_COLORS: Map<String, Color> = mapOf(
    "Gmail" to Color(0xFFEA4335),
    "Outlook" to Color(0xFF0078D4),
    "Yahoo" to Color(0xFF6001D2),
    "iCloud" to Color(0xFF3693F3),
    "Fastmail" to Color(0xFF0067B9),
    "Yandex" to Color(0xFFFC3F1D),
    "Mail.ru" to Color(0xFF005FF9),
    "Proton Bridge" to Color(0xFF6D4AFF),
)

/**
 * The grid of setup tiles: JMAP, IMAP, then every known provider.
 *
 * This is one question where there used to be two. The protocol lived in a chip row and the
 * providers in a second row that only existed while IMAP was selected, so the shortcut for the
 * commonest case (a Gmail address) was hidden behind a decision about protocols that the person
 * adding the account has no reason to have an opinion about.
 */
@Composable
internal fun SetupChoiceGrid(
    selected: SetupChoice,
    onChoose: (SetupChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Plain Rows rather than LazyVerticalGrid: this sits inside the form's verticalScroll, and a
    // lazy grid nested in a scrolling parent has no bounded height to measure against.
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
    ) {
        SETUP_CHOICES.chunked(SETUP_GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8)) {
                row.forEach { choice ->
                    SetupChoiceTile(
                        choice = choice,
                        selected = choice == selected,
                        onClick = { onChoose(choice) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad the last row so three tiles and two tiles are the same width. Without this the
                // final row's tiles stretch and the grid stops looking like a grid.
                repeat(SETUP_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One tile: a logo (or its stand-in) over a label, in a container that shows its selected state. */
@Composable
private fun SetupChoiceTile(
    choice: SetupChoice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (choice) {
        SetupChoice.Jmap -> stringResource(R.string.connect_jmap)
        SetupChoice.Imap -> stringResource(R.string.connect_imap_smtp)
        // Brand labels are names, never translated.
        is SetupChoice.Known -> choice.provider.name
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            // Selection is already announced by the container's colour and border, but neither is
            // available to TalkBack; this is what makes the grid read as a set of choices.
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            },
        shape = RoundedCornerShape(GridlinkSpacing.s12),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = GridlinkSpacing.s12, horizontal = GridlinkSpacing.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
        ) {
            ChoiceLogo(choice)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                // One line, ellipsised: "Proton Bridge" wrapping would make its tile taller than
                // its neighbours and break the row.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** The logo well: a bundled drawable if the provider has one, a Material glyph for the two manual
 * routes, and otherwise a lettermark in the provider's brand colour. */
@Composable
private fun ChoiceLogo(choice: SetupChoice) {
    when (choice) {
        SetupChoice.Jmap -> GlyphLogo(Icons.Filled.CloudQueue)
        SetupChoice.Imap -> GlyphLogo(Icons.Filled.Dns)
        is SetupChoice.Known -> {
            val provider = choice.provider
            val logo = provider.logoRes
            if (logo != null) {
                Icon(
                    painter = painterResource(logo),
                    contentDescription = null,
                    // Untinted: a brand logo is artwork, and recolouring it to the theme would make
                    // it the wrong logo.
                    tint = Color.Unspecified,
                    modifier = Modifier.size(LOGO_SIZE),
                )
            } else {
                Lettermark(provider.name)
            }
        }
    }
}

/** The two manual routes get a themed glyph, not a lettermark: they are protocols, not brands. */
@Composable
private fun GlyphLogo(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(LOGO_SIZE)
            .clip(RoundedCornerShape(GridlinkSpacing.s12))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * A provider's initial on its brand colour, standing in until a real logo is bundled. Deliberately
 * not a generic envelope: eight identical envelopes would be no more scannable than the text chips
 * this grid replaced.
 */
@Composable
private fun Lettermark(name: String) {
    val brand = PROVIDER_BRAND_COLORS[name] ?: MaterialTheme.colorScheme.secondaryContainer
    Box(
        modifier = Modifier
            .size(LOGO_SIZE)
            .clip(RoundedCornerShape(GridlinkSpacing.s12))
            .background(brand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // "iCloud" and "Mail.ru" both start with the character people read as the brand, so the
            // first character is taken as written rather than upper-cased.
            name.take(1),
            style = MaterialTheme.typography.titleMedium,
            // Every brand colour here is dark enough for white; the fallback container is not, so it
            // takes the theme's matching content colour instead.
            color = if (PROVIDER_BRAND_COLORS.containsKey(name)) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}
