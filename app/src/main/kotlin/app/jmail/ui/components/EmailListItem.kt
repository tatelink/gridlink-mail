package app.jmail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jmail.core.data.settings.ListDensity
import app.jmail.core.jmap.model.Email
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * One row in a message list: monogram, sender, subject, preview, time + state.
 * [accountLabel] shows the owning account as a chip — set only in the unified inbox.
 */
@Composable
fun EmailListItem(
    email: Email,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accountLabel: String? = null,
) {
    val unread = !email.isSeen
    val senderName = email.from.firstOrNull()?.display() ?: "(unknown sender)"
    val density = LocalListDensity.current
    val rowPadding = when (density) {
        ListDensity.COMPACT -> 6.dp
        ListDensity.NORMAL -> 10.dp
        ListDensity.SPACED -> 16.dp
    }
    val showPreview = density != ListDensity.COMPACT
    val previewLines = if (density == ListDensity.SPACED) 2 else 1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(seed = email.from.firstOrNull()?.email ?: senderName, label = senderName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (email.isFlagged) {
                    Text("★", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = formatReceived(email.receivedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Text(
                text = email.subject?.takeIf { it.isNotBlank() } ?: "(no subject)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showPreview) {
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
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormatter = DateTimeFormatter.ofPattern("d MMM")

private fun formatReceived(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull() ?: return ""
    val zoned = instant.atZone(ZoneId.systemDefault())
    val today = ZonedDateTime.now().toLocalDate()
    return zoned.format(if (zoned.toLocalDate() == today) timeFormatter else dateFormatter)
}
