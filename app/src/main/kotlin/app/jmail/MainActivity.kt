package app.jmail

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import app.jmail.core.data.settings.ListDensity
import app.jmail.core.data.settings.ThemeMode
import app.jmail.ui.JmailApp
import app.jmail.ui.components.LocalListDensity
import app.jmail.ui.theme.JmailTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = application.container.settingsRepository
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val density by settings.listDensity.collectAsState(initial = ListDensity.NORMAL)
            JmailTheme(themeMode = themeMode) {
                CompositionLocalProvider(LocalListDensity provides density) {
                    JmailApp()
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
    }
}
