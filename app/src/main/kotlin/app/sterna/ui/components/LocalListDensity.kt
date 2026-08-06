package app.sterna.ui.components

import androidx.compose.runtime.compositionLocalOf
import app.sterna.core.data.settings.LIST_MONOGRAM_DEFAULT
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

/**
 * Whether a list row starts with the sender's initials (Settings → Appearance → Message list).
 *
 * Read by [EmailListItem] rather than passed in, on purpose: the row has three call sites (top-level
 * rows, the members of an expanded thread, search results) and a parameter would let one of them
 * drift. The fallback is [LIST_MONOGRAM_DEFAULT] and never a literal, for the reason above.
 */
val LocalListMonogram = compositionLocalOf { LIST_MONOGRAM_DEFAULT }
