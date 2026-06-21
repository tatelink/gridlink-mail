package app.jmail.ui.message

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.EmailBodyPart
import app.jmail.ui.components.Monogram
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    emailId: String,
    onBack: () -> Unit,
    onReply: (mode: String) -> Unit,
    onOpenEmail: (String) -> Unit,
    viewModel: MessageViewModel = viewModel(),
) {
    LaunchedEffect(emailId) { viewModel.load(emailId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val thread by viewModel.thread.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    var showRemote by remember(emailId) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val subject = (state as? MessageState.Loaded)?.email?.subject
                        ?.takeIf { it.isNotBlank() }
                    Text(subject ?: "Message", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    val loaded = state as? MessageState.Loaded
                    if (loaded != null && !showRemote) {
                        TextButton(onClick = { showRemote = true }) { Text("Show images") }
                    }
                    if (loaded != null) {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Reply") },
                                onClick = { menuOpen = false; onReply("reply") },
                            )
                            DropdownMenuItem(
                                text = { Text("Reply all") },
                                onClick = { menuOpen = false; onReply("replyAll") },
                            )
                            DropdownMenuItem(
                                text = { Text("Forward") },
                                onClick = { menuOpen = false; onReply("forward") },
                            )
                            DropdownMenuItem(
                                text = { Text(if (loaded.email.isFlagged) "Unflag" else "Flag") },
                                onClick = { menuOpen = false; viewModel.toggleFlag() },
                            )
                            DropdownMenuItem(
                                text = { Text("Mark unread") },
                                onClick = { menuOpen = false; viewModel.markUnread(onBack) },
                            )
                            DropdownMenuItem(
                                text = { Text("Archive") },
                                onClick = { menuOpen = false; viewModel.archive(onBack) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { menuOpen = false; viewModel.delete(onBack) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is MessageState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is MessageState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Could not load message:\n${s.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { viewModel.load(emailId) }) { Text("Retry") }
                }
                is MessageState.Loaded -> MessageBody(
                    email = s.email,
                    siblings = thread,
                    blockRemote = !showRemote,
                    onOpenEmail = onOpenEmail,
                    attachmentStatus = attachmentStatus,
                    onOpenAttachment = viewModel::openAttachment,
                )
            }
        }
    }
}

@Composable
private fun MessageBody(
    email: Email,
    siblings: List<Email>,
    blockRemote: Boolean,
    onOpenEmail: (String) -> Unit,
    attachmentStatus: String?,
    onOpenAttachment: (EmailBodyPart) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header(email)
        if (siblings.isNotEmpty()) {
            HorizontalDivider()
            ThreadSection(siblings, onOpenEmail)
        }
        val attachments = email.attachments.filter { it.blobId != null }
        if (attachments.isNotEmpty()) {
            HorizontalDivider()
            AttachmentSection(attachments, attachmentStatus, onOpenAttachment)
        }
        HorizontalDivider()
        val html = remember(email) { buildHtmlDocument(email) }
        EmailWebView(
            html = html,
            blockRemote = blockRemote,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun ThreadSection(siblings: List<Email>, onOpenEmail: (String) -> Unit) {
    var expanded by remember(siblings) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${siblings.size} more in this conversation",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            siblings.forEach { sibling ->
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenEmail(sibling.id) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = sibling.from.firstOrNull()?.display() ?: "(unknown sender)",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sibling.subject?.takeIf { it.isNotBlank() } ?: "(no subject)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentSection(
    attachments: List<EmailBodyPart>,
    status: String?,
    onOpen: (EmailBodyPart) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Attachments (${attachments.size})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attachments.forEach { att ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(att) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📎", modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = att.name ?: "attachment",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(formatSize(att.size).takeIf { it.isNotEmpty() }, att.type)
                        .joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

@Composable
private fun Header(email: Email) {
    val sender = email.from.firstOrNull()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = email.subject?.takeIf { it.isNotBlank() } ?: "(no subject)",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Monogram(seed = sender?.email ?: "?", label = sender?.display() ?: "?")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = sender?.display() ?: "(unknown sender)",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sender != null && !sender.name.isNullOrBlank() && sender.email.isNotBlank()) {
                    Text(
                        text = sender.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = formatFull(email.receivedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (email.to.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "To: " + email.to.joinToString { it.display() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmailWebView(html: String, blockRemote: Boolean, modifier: Modifier) {
    val client = remember { BlockingWebViewClient() }
    client.blockRemote = blockRemote
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = client
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
    )
}

/** Blocks remote (http/https) resource loads while [blockRemote]; opens links externally. */
private class BlockingWebViewClient : WebViewClient() {
    var blockRemote: Boolean = true

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (blockRemote && request != null) {
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        val context = view?.context ?: return false
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}

private fun buildHtmlDocument(email: Email): String {
    val inner = email.htmlContent()
        ?: email.textContent()?.let { "<pre class=\"plain\">${escapeHtml(it)}</pre>" }
        ?: "<p>${escapeHtml(email.preview ?: "(no content)")}</p>"
    return """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { margin: 16px; font-family: sans-serif; line-height: 1.45; color: #111;
                 word-wrap: break-word; overflow-wrap: break-word; }
          img { max-width: 100%; height: auto; }
          a { color: #0b5fff; }
          pre.plain { white-space: pre-wrap; word-wrap: break-word; font-family: sans-serif; }
        </style></head><body>$inner</body></html>
    """.trimIndent()
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private val fullFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

private fun formatFull(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull() ?: return ""
    return instant.atZone(ZoneId.systemDefault()).format(fullFormatter)
}
