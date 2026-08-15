package app.gridlink.ui.components

import androidx.compose.runtime.compositionLocalOf
import app.gridlink.core.data.settings.ListDensity
import app.gridlink.core.data.settings.PreviewLines

/**
 * The message-list density chosen in Settings → Appearance, provided at the app
 * root so list rows ([EmailListItem]) can size themselves without threading the
 * preference through every screen.
 */
val LocalListDensity = compositionLocalOf { ListDensity.NORMAL }

/**
 * How many body-preview lines list rows show (Settings → Appearance).
 *
 * 🔴 NONE, matching [app.gridlink.core.data.settings.SettingsRepository.previewLines]'s stored
 * default and for the same reason spelled out there. It is also the safer fallback of the two: this
 * value is what a preview or a test composition sees when nothing provides the real setting, and a
 * default that added a line would make every preview taller than the app.
 */
val LocalPreviewLines = compositionLocalOf { PreviewLines.NONE }
