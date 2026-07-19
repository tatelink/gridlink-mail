package app.sterna.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.OAuthProvider

/**
 * The "accounts to sign in" list shown after a K-9 / backup import: every imported account still
 * awaiting its one-time sign-in. Tapping a row opens that account's sign-in; swiping it (either
 * direction, like a mail row) dismisses it from the list — the account stays inert and can be
 * signed in later from Settings → Accounts. Rendered as a plain [Column] so it nests inside a
 * scrolling parent without a scroll conflict (the list is short by nature).
 */
@Composable
fun PendingImportAccountsSection(
    accounts: List<StoredAccount>,
    onSignIn: (StoredAccount) -> Unit,
    onDismiss: (StoredAccount) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (accounts.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.import_pending_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        for (account in accounts) {
            key(account.id) {
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) {
                            onDismiss(account)
                            true
                        } else {
                            false
                        }
                    },
                )
                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = { DismissBackground() },
                ) {
                    PendingAccountRow(account = account, onClick = { onSignIn(account) })
                }
            }
        }
    }
}

@Composable
private fun PendingAccountRow(account: StoredAccount, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                account.label(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(authHintRes(account)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The reveal shown behind a row being swiped away (either direction). */
@Composable
private fun DismissBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.Archive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.import_pending_dismiss),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Which sign-in a tap will start, so the row tells the user what to expect. */
private fun authHintRes(account: StoredAccount): Int = when {
    account.authType == AuthType.OAUTH && OAuthProvider.forImapHost(account.imapHost) != null ->
        R.string.import_pending_hint_microsoft
    account.authType == AuthType.OAUTH -> R.string.import_pending_hint_oauth
    else -> R.string.import_pending_hint_password
}
