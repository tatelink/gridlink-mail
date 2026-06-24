package app.sterna.ui.components

import androidx.compose.runtime.compositionLocalOf
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.PreviewLines

/**
 * The message-list density chosen in Settings → Appearance, provided at the app
 * root so list rows ([EmailListItem]) can size themselves without threading the
 * preference through every screen.
 */
val LocalListDensity = compositionLocalOf { ListDensity.NORMAL }

/** How many body-preview lines list rows show (Settings → Appearance). */
val LocalPreviewLines = compositionLocalOf { PreviewLines.ONE }
