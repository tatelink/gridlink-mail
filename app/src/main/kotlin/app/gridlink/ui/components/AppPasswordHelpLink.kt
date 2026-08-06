package app.gridlink.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gridlink.R
import app.gridlink.ui.rememberLeaveOnce

/** Microsoft's app-password creation page (used for the OAuth→app-password fallback). */
private const val MS_APP_PASSWORD_URL = "https://account.live.com/proofs/AppPassword"

/**
 * One tap to Microsoft's app-password page, offered wherever we ask for a password Microsoft will
 * refuse: three places in the connect screen and one in the account editor. It was written twice,
 * once per file, with the same URL constant and the same bare `startActivity` in each — the second
 * copy inherited the first one's missing re-entrancy guard, which is what duplicated components do.
 *
 * It holds its own [rememberLeaveOnce] latch rather than taking an opener from its callers. That is
 * the opposite of the rule for SettingsCategoryRow, and for the opposite reason: this component has
 * ONE meaning — hand this one URL to a browser — so the guard is not hidden behind an unrelated
 * behaviour, it IS the behaviour, and no caller can be written that forgets it. SettingsCategoryRow
 * has thirteen call sites and nine of them navigate instead, which is a different guard entirely.
 */
@Composable
fun AppPasswordHelpLink() {
    val context = LocalContext.current
    val leaveOnce = rememberLeaveOnce()
    TextButton(
        onClick = {
            leaveOnce {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MS_APP_PASSWORD_URL)))
                }.isSuccess
            }
        },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.connect_app_password_help))
    }
}
