package app.gridlink.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import app.gridlink.R
import app.gridlink.ui.theme.GridlinkSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The GPL, in full, inside the app.
 *
 * ## 🔴 Why this is not a link
 * It used to be. The row handed `github.com/tatelink/gridlink-mail/blob/main/LICENSE` to a browser,
 * and that repo is private, so GitHub served an anonymous phone a **404** — not a sign-in page, a
 * flat "there is nothing here". Same for the Version and Source code rows. Three rows out of five in
 * About were dead, and because they died in the browser rather than in the app, what it looked like
 * from the outside was the app shipping broken links.
 *
 * The licence is the one of the three that has to be readable, because the GPL itself requires the
 * text to travel with the binary. So it travels with the binary: `assets/gpl-3.0.txt`, read from the
 * APK, no network involved. It cannot 404, it works on a plane, and it is the actual licence this
 * build is under rather than a promise that one exists elsewhere.
 *
 * ## Why the text is read off the main thread
 * 35 KB of asset. Small, but it is still file I/O, and doing it inline in composition means the
 * frame that opens this screen waits on the disk. The read happens in a [LaunchedEffect] on
 * [Dispatchers.IO] and the screen draws empty for the one frame that costs, which is invisible under
 * the screen's own slide-in.
 *
 * ## Why it scrolls sideways
 * The GPL is a fixed-width document: its clause numbering and indentation only line up in a
 * monospace font at its original wrap. Re-wrapping it to the screen would leave a legal text
 * looking mangled, so it keeps its own line breaks and gets a horizontal scroll instead. ⚠️ Do not
 * "fix" the long lines. The document is not ours to reformat.
 */
@Composable
internal fun LicenseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            // Bundled with the app, so the only way this throws is a corrupt APK, and there is
            // nothing useful to say to the user about that beyond the licence name they already saw.
            runCatching {
                context.assets.open(LICENSE_ASSET).bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }
    DetailScaffold(title = stringResource(R.string.settings_about_license), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GridlinkSpacing.rowHorizontal),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                softWrap = false,
            )
            Spacer(Modifier.height(GridlinkSpacing.s20))
        }
    }
}

/**
 * 🔴 A verbatim copy of the repo's root `LICENSE`, checked in so the APK carries it.
 *
 * The duplication is deliberate and safe: this is the unmodified GPL-3.0 text, a fixed document the
 * FSF publishes and nobody edits. It is copied rather than generated because a Gradle copy task that
 * feeds the asset merger is one more thing that can break a build, to solve a drift problem that
 * cannot occur. If the project ever changes licence, both copies change together.
 */
private const val LICENSE_ASSET = "gpl-3.0.txt"
