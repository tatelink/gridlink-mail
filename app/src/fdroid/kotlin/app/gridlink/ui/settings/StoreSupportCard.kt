package app.gridlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gridlink.R

/**
 * The F-Droid (and GitHub release) build's tip jar: one pill that opens Tate's Ko-fi in a browser.
 *
 * 🔴 This flavor must stay free of any Google dependency. F-Droid rebuilds it from the tag with
 * `gradle: [fdroid]` and checks our signed APK is byte-identical to theirs, and its scanner flags
 * the Play Billing library. The Play build's card lives in `src/play` and is the only place billing
 * code exists; nothing in `src/main` may name it.
 *
 * [onOpenUrl] is the hub's guarded opener, so a double tap cannot open the page twice.
 */
@Composable
internal fun StoreSupportCard(onOpenUrl: (String) -> Unit) {
    SupportCardFrame(body = stringResource(R.string.settings_support_card_body)) {
        SupportPill(
            label = stringResource(R.string.settings_about_support_summary),
            onClick = { onOpenUrl(KOFI_URL) },
            icon = Icons.Filled.LocalCafe,
        )
    }
}

/**
 * Tate's Ko-fi, behind the support pill.
 *
 * 🔴 His, not upstream's: an earlier support row pointed at upstream's Ko-fi and had to be removed.
 * If a support link ever needs changing, it changes here and nowhere else. 🔴 F-Droid flavor only:
 * Google Play forbids an external tip link, so the Play build carries no Ko-fi URL at all.
 */
internal const val KOFI_URL = "https://ko-fi.com/tatelink"
