package app.gridlink.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.gridlink.R
import app.gridlink.ui.theme.GridlinkSpacing

/**
 * What this app promises, written where the person holding the phone can read it.
 *
 * 🔴 This screen is the promise, not a description of one. Every line here is a commitment the app
 * is already keeping, so nothing may be added that is merely intended: a promise the code does not
 * keep is worse than no promise, because it is read as a statement of fact. The mirror of this text
 * lives in `docs/PROMISES.md`, which is what somebody who has NOT installed the app can hold it to;
 * the two are meant to say the same thing and should be changed together.
 *
 * It is a plain wall of text on purpose. There is nothing to tap, nothing to configure and no
 * affordance that could be mistaken for one, because a promise that appears next to a switch reads
 * as a setting somebody could have turned off.
 */
@Composable
internal fun PromisesScreen(onBack: () -> Unit) {
    DetailScaffold(title = stringResource(R.string.promises_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GridlinkSpacing.rowHorizontal),
        ) {
            Text(
                stringResource(R.string.promises_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Promise(R.string.promises_gestures_title, R.string.promises_gestures_body)
            Promise(R.string.promises_open_title, R.string.promises_open_body)
            Promise(R.string.promises_free_title, R.string.promises_free_body)
            Promise(R.string.promises_private_title, R.string.promises_private_body)
            Promise(R.string.promises_consent_title, R.string.promises_consent_body)
            Promise(R.string.promises_source_title, R.string.promises_source_body)
            Spacer(Modifier.height(GridlinkSpacing.s20))
        }
    }
}

/**
 * One promise: a headline and the sentence that says what keeping it costs the app.
 *
 * The headline carries [heading] so a screen reader can jump between promises instead of being made
 * to hear all six as one undifferentiated paragraph, which is how a scrolling wall of text reaches
 * somebody who cannot skim it.
 */
@Composable
private fun Promise(title: Int, body: Int) {
    Column(Modifier.fillMaxWidth().padding(top = GridlinkSpacing.s20)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(GridlinkSpacing.s8))
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
