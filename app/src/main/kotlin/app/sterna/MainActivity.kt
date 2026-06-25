package app.sterna

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
import app.sterna.ui.components.LocalListDensity
import app.sterna.ui.components.LocalPreviewLines
import app.sterna.ui.theme.SternaTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    SternaApp()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        application.container.appLock.onAppBackgrounded(System.currentTimeMillis())
    }

    override fun onStart() {
        super.onStart()
        application.container.appLock.onAppForegrounded(System.currentTimeMillis())
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
