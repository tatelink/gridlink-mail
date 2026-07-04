package app.sterna.pgp

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The openpgp-api user-interaction round-trip: a provider call that returns
 * [app.sterna.core.data.pgp.PgpResult.UserInteractionRequired] hands us a
 * [PendingIntent] (passphrase prompt, key chooser…). Launch it through the
 * returned lambda; [onResult] then receives the activity-result data Intent to
 * pass back as `interactionResult` on retry, or null when the user cancelled.
 */
@Composable
fun rememberPgpInteractionLauncher(onResult: (Intent?) -> Unit): (PendingIntent) -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        onResult(if (result.resultCode == Activity.RESULT_OK) result.data ?: Intent() else null)
    }
    return remember(launcher) {
        { pendingIntent ->
            launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        }
    }
}
