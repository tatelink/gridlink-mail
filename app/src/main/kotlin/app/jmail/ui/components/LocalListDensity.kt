package app.jmail.ui.components

import androidx.compose.runtime.compositionLocalOf
import app.jmail.core.data.settings.ListDensity

/**
 * The message-list density chosen in Settings → Appearance, provided at the app
 * root so list rows ([EmailListItem]) can size themselves without threading the
 * preference through every screen.
 */
val LocalListDensity = compositionLocalOf { ListDensity.NORMAL }
