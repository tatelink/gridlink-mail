package app.gridlink.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gridlink.R
import app.gridlink.billing.TipAvailability
import app.gridlink.billing.TipJar
import app.gridlink.billing.TipJarState
import app.gridlink.billing.TipNotice
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme

/**
 * The Google Play build's tip jar: Play Billing tips in place of the F-Droid build's Ko-fi pill.
 *
 * 🔴 Why it differs: Google Play forbids sending users to an outside payment page for a tip unless
 * the app is a charity, so the Play build sells consumable tips through Play instead (Brandon's
 * call 2026-11-07). Tips are voluntary and unlock nothing; the copy says so, and no code path reads
 * the tip count except this card's thank-you.
 *
 * [onOpenUrl] is unused here on purpose: the F-Droid card needs it for Ko-fi, and both flavors
 * share one signature so `src/main` calls the card without knowing which store it is in.
 */
@Composable
internal fun StoreSupportCard(@Suppress("UNUSED_PARAMETER", "UnusedParameter") onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val tipJar = remember { TipJar(context) }
    DisposableEffect(tipJar) {
        tipJar.connect()
        onDispose { tipJar.close() }
    }
    val state by tipJar.state.collectAsStateWithLifecycle()
    TipJarCard(state = state, onTip = { productId -> context.findActivity()?.let { tipJar.launch(it, productId) } })
}

/**
 * The card itself, driven only by [state] so every state can be drawn in a test without Play.
 *
 * Prices are Play's own formatted strings, never ours. While Play is unreachable, or before the
 * tip products exist in Play Console, the card stays (so the screen does not jump) and says so in
 * one quiet line instead of offering pills that cannot work.
 */
@Composable
internal fun TipJarCard(state: TipJarState, onTip: (String) -> Unit) {
    val colors = GridlinkTheme.colors
    val body = if (state.tippedBefore) R.string.tip_card_body_thanked else R.string.tip_card_body
    SupportCardFrame(
        body = stringResource(body),
        badgeIcon = if (state.notice == TipNotice.Thanks) Icons.Filled.Check else Icons.Filled.Favorite,
    ) {
        when (state.availability) {
            TipAvailability.Ready -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
            ) {
                state.options.forEach { option ->
                    val description = stringResource(R.string.tip_pill_description, option.price)
                    SupportPill(
                        label = option.price,
                        onClick = { onTip(option.productId) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = description },
                        enabled = !state.busy,
                    )
                }
            }
            TipAvailability.Loading -> QuietLine(stringResource(R.string.tip_card_loading))
            TipAvailability.Unavailable -> QuietLine(stringResource(R.string.tip_card_unavailable))
        }
        val notice = when (state.notice) {
            TipNotice.None -> null
            TipNotice.Thanks -> R.string.tip_card_thanks
            TipNotice.Pending -> R.string.tip_card_pending
            TipNotice.Failed -> R.string.tip_card_failed
        }
        if (notice != null) {
            Spacer(Modifier.height(GridlinkSpacing.s12))
            Text(
                stringResource(notice),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.notice == TipNotice.Thanks) colors.accent else colors.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun QuietLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GridlinkTheme.colors.textSecondary)
}

/** Play's purchase sheet needs the hosting Activity, not the application context. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
