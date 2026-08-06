package app.sterna.ui.components

import androidx.compose.runtime.compositionLocalOf
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.data.settings.UNREAD_TINT_DEFAULT

/**
 * The message-list density chosen in Settings → Appearance, provided at the app
 * root so list rows ([EmailListItem]) can size themselves without threading the
 * preference through every screen.
 */
val LocalListDensity = compositionLocalOf { ListDensity.NORMAL }

/** How many body-preview lines list rows show (Settings → Appearance). */
val LocalPreviewLines = compositionLocalOf { PreviewLines.ONE }

/**
 * Whether unread rows carry a background of their own (Settings → Appearance → Message list).
 *
 * The fallback is [UNREAD_TINT_DEFAULT] and never a literal: this is the third copy of the same
 * default (the repository and MainActivity's `initial =` are the other two), and a literal here
 * that disagreed with them would show a wrongly-painted list under any provider-less preview and
 * survive every value test.
 */
val LocalUnreadTint = compositionLocalOf { UNREAD_TINT_DEFAULT }
