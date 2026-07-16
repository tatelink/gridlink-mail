package app.sterna

import android.content.Intent
import android.net.MailTo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.appcompat.app.AppCompatActivity
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.data.settings.ThemeMode
import app.sterna.ui.SternaApp
import app.sterna.ui.message.NavFadeGuard
import app.sterna.ui.components.LocalListDensity
import app.sterna.ui.components.LocalPreviewLines
import app.sterna.ui.theme.SternaTheme

class MainActivity : AppCompatActivity() {
    /** A mailto: link waiting to open the compose screen (Codeberg #15). Set from the launch
     *  intent or [onNewIntent] (the activity is singleTask, so an external link re-enters the
     *  running instance), consumed by the NavHost once it has navigated. */
    private val pendingMailto = androidx.compose.runtime.mutableStateOf<MailtoDraft?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingMailto.value = parseMailto(intent)
        val settings = application.container.settingsRepository
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
            val density by settings.listDensity.collectAsState(initial = ListDensity.NORMAL)
            val previewLines by settings.previewLines.collectAsState(initial = PreviewLines.ONE)
            SternaTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                CompositionLocalProvider(
                    LocalListDensity provides density,
                    LocalPreviewLines provides previewLines,
                ) {
                    SternaApp(
                        pendingMailto = pendingMailto.value,
                        onMailtoConsumed = { pendingMailto.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseMailto(intent)?.let { pendingMailto.value = it }
    }

    /** RFC 6068 mailto: parsing — addresses plus the optional subject/body/cc/bcc fields. */
    private fun parseMailto(intent: Intent?): MailtoDraft? {
        val data = intent?.data ?: return null
        if (!"mailto".equals(data.scheme, ignoreCase = true)) return null
        return runCatching {
            val m = MailTo.parse(data.toString())
            MailtoDraft(
                to = m.to.orEmpty(),
                cc = m.cc.orEmpty(),
                bcc = m.headers?.get("bcc").orEmpty(),
                subject = m.subject.orEmpty(),
                body = m.body.orEmpty(),
            )
        }.getOrNull()
    }

    override fun onStop() {
        super.onStop()
        application.container.appLock.onAppBackgrounded(System.currentTimeMillis())
        // Stopped activity → no frames → the reader's GL functor cannot draw, so a process
        // kill while backgrounded (LMK, swipe-away) must not count as a fade-window crash.
        NavFadeGuard.onActivityStop(this)
    }

    override fun onStart() {
        super.onStart()
        application.container.appLock.onAppForegrounded(System.currentTimeMillis())
        NavFadeGuard.onActivityStart(this)
        applySecureFlag()
    }

    /**
     * With app lock on, mark the window FLAG_SECURE so message bodies and the credential
     * screen are kept out of the recents thumbnail, screenshots, and screen recordings —
     * otherwise the lock overlay hides the live UI but the OS still snapshots it.
     */
    private fun applySecureFlag() {
        if (application.container.appLock.isEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** Prefill fields parsed from a mailto: link, handed to the compose screen (Codeberg #15). */
data class MailtoDraft(
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
)
