package app.gridlink.ui.gridlink

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.R
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * Who a message goes out as: the account that sends it, and the address it carries in From.
 *
 * [email] null means "that account's default identity", which is what a reply gets before the
 * composer has resolved anything, and it keeps working if the user renames or re-adds identities in
 * between. An address rather than an identity id because ids are not stable across the merged
 * manual and server lists, while the address is what the identity IS on the wire.
 */
@Immutable
data class GridlinkFrom(val accountId: String, val email: String? = null)

/** One address an account can send as, already flattened for drawing. */
@Immutable
data class GridlinkFromIdentity(
    val name: String,
    val email: String,
    /** What the composer appends when this identity is chosen. Blank for none. */
    val signature: String = "",
)

/**
 * One signed-in account and the addresses it can send as, for the composer's From line.
 *
 * The host builds these from the account store (the package rule: nothing in `ui/gridlink` reads a
 * core type), with the active account FIRST. That order is the default: a composer with no
 * [GridlinkFrom] sends as the first entry's [defaultEmail].
 */
@Immutable
data class GridlinkFromAccount(
    val accountId: String,
    val label: String,
    val identities: List<GridlinkFromIdentity>,
    val defaultEmail: String,
)

/** A resolved choice: the account and the exact identity on it. */
@Immutable
data class GridlinkSending(val account: GridlinkFromAccount, val identity: GridlinkFromIdentity) {
    /** The value a request carries, always explicit once resolved. */
    val from: GridlinkFrom get() = GridlinkFrom(account.accountId, identity.email)
}

/**
 * What [from] means against [choices], or null when there is nothing to choose from (the gallery,
 * or a build with no accounts).
 *
 * Every miss degrades toward the default rather than failing: an account that has been signed out
 * since the draft was written falls back to the first (active) account, and an address the account
 * no longer has falls back to that account's default identity. A composer that refused to open over
 * a stale From would lose the draft over a detail the user can fix with one tap.
 */
internal fun gridlinkResolveFrom(from: GridlinkFrom?, choices: List<GridlinkFromAccount>): GridlinkSending? {
    val account = choices.firstOrNull { it.accountId == from?.accountId } ?: choices.firstOrNull() ?: return null
    val wanted = from?.takeIf { it.accountId == account.accountId }?.email
    val identity = account.identities.firstOrNull { it.email.equals(wanted, ignoreCase = true) }
        ?: account.identities.firstOrNull { it.email.equals(account.defaultEmail, ignoreCase = true) }
        ?: account.identities.firstOrNull()
        ?: return null
    return GridlinkSending(account, identity)
}

/**
 * Whether the From line has anything to offer: more than one address across every account.
 *
 * One account with one address has exactly one possible answer, and a row that can only ever say
 * the same thing is a control that cannot act, so the composer keeps its old shape for that user.
 */
internal fun gridlinkFromHasChoice(choices: List<GridlinkFromAccount>): Boolean =
    choices.sumOf { it.identities.size } > 1

/**
 * [request] sent from the account that owns the message [rowKey] names.
 *
 * Replies, forwards and unsubscribe mails answer a message that arrived at ONE account, and in the
 * unified inbox that is often not the account the app is switched to. The row key carries the
 * owning account exactly in that case (see [GridlinkRowKey]); a bare key means the list was showing
 * the active account, which is already the default, so the request is returned unchanged.
 */
internal fun gridlinkSentFromOwner(request: GridlinkComposeRequest, rowKey: String): GridlinkComposeRequest {
    val accountId = GridlinkRowKey.decode(rowKey).first ?: return request
    return request.copy(draft = request.draft.copy(from = GridlinkFrom(accountId)))
}

/** "Name <address>", or the bare address for an unnamed identity. */
internal fun gridlinkFromDisplay(identity: GridlinkFromIdentity): String =
    if (identity.name.isBlank()) identity.email else "${identity.name} <${identity.email}>"

/**
 * The composer's From line: who this goes out as, tappable when there is a choice.
 *
 * Laid out like TO above it (caps label, then the value) so the form reads as one stack of header
 * lines. The account label rides along only when the user has more than one account, because that
 * is the case where the address alone may not say which mailbox the Sent copy lands in.
 */
@Composable
internal fun GridlinkFromRow(
    sending: GridlinkSending,
    multiAccount: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = GridlinkSpacing.rowHorizontal, vertical = GridlinkSpacing.s12)
            .testTag("gridlink-compose-from"),
    ) {
        Text(
            text = stringResource(R.string.compose_from).uppercase(),
            style = GridlinkType.sectionLabel,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(GridlinkSpacing.s8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = gridlinkFromDisplay(sending.identity),
                    style = GridlinkType.chip,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (multiAccount && !sending.account.label.equals(sending.identity.email, ignoreCase = true)) {
                    Text(
                        text = sending.account.label,
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Pick who to send as: every signed-in account, each with its addresses under it.
 *
 * The account's name heads its group only when there is more than one account, for the same reason
 * [GridlinkFromRow] only names it then. The current choice carries a check rather than a colour, so
 * it reads the same in every mode and to a screen reader.
 */
@Composable
internal fun GridlinkFromPicker(
    choices: List<GridlinkFromAccount>,
    selected: GridlinkSending?,
    onPick: (GridlinkSending) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    val multiAccount = choices.size > 1
    GridlinkCenterSheet(onDismiss = onDismiss) {
        GridlinkSheetHeading(
            title = stringResource(R.string.compose_from),
            icon = Icons.Outlined.AlternateEmail,
        )
        choices.forEachIndexed { index, account ->
            GridlinkSheetDivider()
            if (multiAccount) {
                Text(
                    text = account.label.uppercase(),
                    style = GridlinkType.sectionLabel,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(
                        start = GridlinkSpacing.chrome,
                        end = GridlinkSpacing.chrome,
                        top = GridlinkSpacing.s12,
                    ),
                )
            }
            account.identities.forEach { identity ->
                val isSelected = selected?.account?.accountId == account.accountId &&
                    selected.identity.email.equals(identity.email, ignoreCase = true)
                GridlinkSheetAction(
                    label = identity.name.ifBlank { identity.email },
                    icon = Icons.Outlined.AlternateEmail,
                    subline = identity.email.takeIf { identity.name.isNotBlank() },
                    onClick = { onPick(GridlinkSending(account, identity)) },
                    modifier = Modifier.testTag("gridlink-from-$index-${identity.email}"),
                    trailing = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        GridlinkSheetFooterSpace()
    }
}
