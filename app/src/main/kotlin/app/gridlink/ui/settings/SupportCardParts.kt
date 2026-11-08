package app.gridlink.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import app.gridlink.R
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme

/*
 * The support card's look, shared by both store flavors.
 *
 * 🔴 What goes INSIDE the card is per store and lives in the flavor source sets as
 * `StoreSupportCard`: `src/fdroid` keeps the Ko-fi pill, `src/play` draws Google Play Billing tips
 * (Play forbids an external tip link for an app that is not a charity, Brandon's call 2026-11-07).
 * Only the frame and the pill live here, so the two flavors cannot drift apart visually and the
 * fdroid flavor never sees a line of billing code.
 */

/**
 * The tip jar, drawn as a card rather than a row so it reads as an offer instead of a setting.
 *
 * 🔴 2026-08-22, Tate: *"Buy me a coffee needs to look nicer."* What it was: a flat slab of
 * `secondaryContainer` (which this skin answers with the *selected row* wash), no edge, a small
 * black heart, and a stock filled Button, the only piece of undressed Material 3 on a screen
 * where every other control is drawn in the app's own language. It read as a banner someone dropped
 * in. What it is now is the app's own card: a raised panel with a hairline, a badge, a type
 * hierarchy and a pill, all built out of [app.gridlink.ui.theme.GridlinkColors] roles.
 *
 * ⚠️ The ring, the badge and the pill are [app.gridlink.ui.theme.GridlinkColors.accent] and NOT
 * `accentWarm`, and the pill deliberately carries no glow. Warm-plus-glow is this app's one *verb*
 * treatment (Send, Save, the compose circle), and a donation ask wearing it would claim to be a
 * mail action. Accent without the halo is the half-step that exists for exactly this: unmissable,
 * clearly tappable, clearly not part of the mail.
 *
 * The badge icon is decorative and unlabelled, so a screen reader announces the offer once rather
 * than reciting an icon first. [content] is drawn under the body, after the same 20dp gap the
 * original single-pill card used.
 */
@Composable
internal fun SupportCardFrame(
    body: String,
    badgeIcon: ImageVector = Icons.Filled.Favorite,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    val cardShape = RoundedCornerShape(GridlinkRadii.card)
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = GridlinkSpacing.s16, vertical = GridlinkSpacing.s12),
        shape = cardShape,
        // The raised panel, not a wash. 🔴 The old `secondaryContainer` maps to `selection` here,
        // so the card was painted in the colour that means "this row is open" on every list in the
        // app: a state colour spent as decoration. Lift plus an accent hairline says "card" without
        // borrowing a meaning.
        color = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        border = BorderStroke(GridlinkDimens.hairline, colors.accent.copy(alpha = SUPPORT_RING_ALPHA)),
    ) {
        Column(Modifier.padding(GridlinkSpacing.s20)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A badge rather than a loose glyph. The heart on its own was 20dp of near-black
                // ink beside a title, which is where the card looked cheapest; a washed circle
                // gives the block one thing for an eye to land on.
                Box(
                    Modifier.size(GridlinkSpacing.s40).clip(CircleShape).background(colors.fieldLabelFill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        badgeIcon,
                        contentDescription = null,
                        Modifier.size(GridlinkSpacing.s20),
                        tint = colors.accent,
                    )
                }
                Spacer(Modifier.width(GridlinkSpacing.s12))
                Text(stringResource(R.string.settings_about_support), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(GridlinkSpacing.s12))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                // The second rank. One ink for the title and the paragraph is what let three
                // separate things read as a single undifferentiated block of text.
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(GridlinkSpacing.s20))
            content()
        }
    }
}

/**
 * The card's accent pill: the one tappable thing on it.
 *
 * [icon] is decorative (the label says what the pill does). A disabled pill keeps its shape and
 * fades, so a card that is waiting on a store does not jump when it comes back.
 */
@Composable
internal fun SupportPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = GridlinkTheme.colors
    val pillShape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier
            .then(if (enabled) Modifier else Modifier.alpha(SUPPORT_PILL_DISABLED_ALPHA))
            .clip(pillShape)
            .background(colors.accent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = GridlinkSpacing.s20, vertical = GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                Modifier.size(GridlinkSpacing.s20),
                tint = colors.onAccent,
            )
            Spacer(Modifier.width(GridlinkSpacing.s8))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onAccent,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * How much accent the support card's hairline gets.
 *
 * Enough that the ring reads as deliberate on Day's white glass, little enough that it stays a ring
 * and not an outline. ⚠️ Much above half it starts competing with the pill inside it, and the
 * card reads as two controls stacked.
 */
private const val SUPPORT_RING_ALPHA = 0.35f

/** A pill waiting on something: still visibly the same control, clearly not ready. */
private const val SUPPORT_PILL_DISABLED_ALPHA = 0.5f
