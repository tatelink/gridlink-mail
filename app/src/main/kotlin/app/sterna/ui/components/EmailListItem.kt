package app.sterna.ui.components

import app.sterna.appLocale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.jmap.model.Email
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * One row in a message list: monogram, sender, subject, preview, time + state.
 * [accountLabel] shows the owning account as a chip — set only in the unified inbox.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmailListItem(
    email: Email,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accountLabel: String? = null,
    accountColor: Color? = null,
    onToggleFavourite: (() -> Unit)? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    // In conversation view: the thread's unread state and how many messages it holds.
    unread: Boolean = !email.isSeen,
    threadCount: Int = 1,
) {
    val senderName = email.from.firstOrNull()?.display() ?: stringResource(R.string.message_unknown_sender)
    val density = LocalListDensity.current
    val rowPadding = when (density) {
        ListDensity.COMPACT -> 6.dp
        ListDensity.NORMAL -> 10.dp
        ListDensity.SPACED -> 16.dp
    }
    val previewLines = LocalPreviewLines.current.lines
    val receivedLabel = remember(email.receivedAt) { formatReceived(email.receivedAt) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = rowPadding, bottom = rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(seed = email.from.firstOrNull()?.email ?: senderName, label = senderName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.titleMedium,
                    // Unread is shown by weight (bold) rather than a status dot.
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = receivedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = email.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.message_no_subject),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Conversation count badge — only when the thread has 2+ messages.
                if (threadCount > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = threadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (previewLines > 0) {
                email.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = previewLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            accountLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Spacer(Modifier.size(4.dp))
                // Tint the account chip with the account's accent colour when set.
                val chipColor = accountColor ?: MaterialTheme.colorScheme.primary
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = chipColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (accountColor != null) accountColor.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (onToggleFavourite != null) {
            // The ★/☆ glyph is meaningless to a screen reader, so replace its
            // semantics with a clear, state-aware label + button role.
            val favLabel = stringResource(
                if (email.isFlagged) R.string.a11y_unfavourite else R.string.a11y_favourite,
            )
            Text(
                text = if (email.isFlagged) "★" else "☆",
                style = MaterialTheme.typography.titleLarge,
                color = if (email.isFlagged) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onToggleFavourite)
                    .padding(8.dp)
                    .clearAndSetSemantics {
                        contentDescription = favLabel
                        role = Role.Button
                    },
            )
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", appLocale)
private val dateFormatter = DateTimeFormatter.ofPattern("d MMM", appLocale)

private fun formatReceived(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull() ?: return ""
    val zoned = instant.atZone(ZoneId.systemDefault())
    val today = ZonedDateTime.now().toLocalDate()
    return zoned.format(if (zoned.toLocalDate() == today) timeFormatter else dateFormatter)
}
