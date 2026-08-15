package app.gridlink.ui.gridlink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import app.gridlink.core.data.settings.ListDensity
import app.gridlink.ui.components.LocalListDensity
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkSpacing

/**
 * The list-density setting, expressed as row geometry.
 *
 * ## 🔴 Density changes the padding, never the content
 * [GridlinkMessageRow]'s own header bans a third line, avatars, cards and gaps, because the brief
 * wants 13 rows on a folded Fold screen. That ban is not a density setting in disguise: it is what
 * a row IS in this app, and it holds at all three settings. So the only thing this dial turns is
 * the breathing room around two lines of text that never change.
 *
 * The two lines occupy 40dp between them, and every height below is exactly `40 + 2 * padding`.
 * That is why the padding ladder is the source of truth here and the heights are derived from it:
 * pick a padding off [GridlinkSpacing]'s scale and the height follows, so a row can never end up
 * with vertical padding that does not fit inside it.
 *
 * - COMPACT: 8dp padding, 56dp row. About 15 rows where NORMAL gets 13.
 * - NORMAL: 12dp padding, 64dp row. Unchanged from [GridlinkDimens.messageRowHeight], so an install
 *   that never touches the setting sees exactly the list it saw before this existed.
 * - SPACED: 16dp padding, 72dp row. About 11 rows.
 *
 * 🔴 Nothing here is a free number. All three paddings are rungs on [GridlinkSpacing]'s ladder
 * (s8 / s12 / s16), which the ladder's own doc says nothing may step outside of.
 *
 * ## Search rows shift by the same amount
 * A search row carries a third line and is [GridlinkDimens.searchRowHeight], 20dp taller than a
 * message row. It keeps that same 20dp offset at every density rather than scaling separately, so
 * the extra line stays the same size and only the padding around the block moves.
 */
@Composable
@ReadOnlyComposable
fun gridlinkRowVertical(): Dp = when (LocalListDensity.current) {
    ListDensity.COMPACT -> GridlinkSpacing.s8
    ListDensity.NORMAL -> GridlinkSpacing.s12
    ListDensity.SPACED -> GridlinkSpacing.s16
}

/** Height of a two-line message row at the current density. See the note above. */
@Composable
@ReadOnlyComposable
fun gridlinkRowHeight(): Dp = GRIDLINK_ROW_CONTENT + gridlinkRowVertical() * 2

/** Height of a three-line search result row at the current density. */
@Composable
@ReadOnlyComposable
fun gridlinkSearchRowHeight(): Dp = gridlinkRowHeight() + GRIDLINK_SEARCH_LINE

/**
 * What two lines of row text measure between them.
 *
 * 🔴 This is `messageRowHeight - 2 * rowVertical` at NORMAL and must stay that way: it is the one
 * fact that keeps [gridlinkRowHeight] returning exactly [GridlinkDimens.messageRowHeight] when the
 * setting is left alone. Changing either token without changing this silently retunes the whole
 * list.
 */
private val GRIDLINK_ROW_CONTENT: Dp = GridlinkDimens.messageRowHeight - GridlinkSpacing.rowVertical * 2

/** The third line a search row adds, likewise pinned to the pair of tokens it came from. */
private val GRIDLINK_SEARCH_LINE: Dp = GridlinkDimens.searchRowHeight - GridlinkDimens.messageRowHeight
