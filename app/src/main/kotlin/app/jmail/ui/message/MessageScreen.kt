package app.jmail.ui.message

import app.jmail.appLocale
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jmail.R
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.EmailBodyPart
import app.jmail.ui.components.Monogram
import app.jmail.util.LinkCleaner
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
    accountId: String? = null,
    viewModel: MessageViewModel = viewModel(),
) {
    LaunchedEffect(emailId, accountId) { viewModel.load(emailId, accountId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val thread by viewModel.thread.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val inlineImages by viewModel.inlineImages.collectAsStateWithLifecycle()
    val inJunk by viewModel.inJunk.collectAsStateWithLifecycle()
    val stripTracking by viewModel.stripTracking.collectAsStateWithLifecycle()
    val confirmLinks by viewModel.confirmLinks.collectAsStateWithLifecycle()
    val imageAllowlist by viewModel.imageAllowlist.collectAsStateWithLifecycle()
    val messageTextSize by viewModel.messageTextSize.collectAsStateWithLifecycle()
    // Per-message manual override; the sender allowlist auto-shows without it.
    var manualShow by remember(emailId) { mutableStateOf(false) }
    val senderEmail = (state as? MessageState.Loaded)?.email?.from?.firstOrNull()?.email
    val senderAllowed = senderEmail?.lowercase()?.let { it in imageAllowlist } == true
    val showRemote = manualShow || senderAllowed

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val subject = (state as? MessageState.Loaded)?.email?.subject
                        ?.takeIf { it.isNotBlank() }
                    Text(
                        subject ?: stringResource(R.string.message_title_fallback),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.message_back),
                        )
                    }
                },
                actions = {
                    val loaded = state as? MessageState.Loaded
                    if (loaded != null && !showRemote) {
                        TextButton(onClick = { manualShow = true }) {
                            Text(stringResource(R.string.message_show_images))
                        }
                    }
                    if (loaded != null) {
                        IconButton(onClick = { viewModel.markUnread(onBack) }) {
                            Icon(
                                Icons.Filled.MailOutline,
                                contentDescription = stringResource(R.string.message_mark_unread),
                            )
                        }
                        IconButton(onClick = { viewModel.delete(onBack) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.message_delete),
                            )
                        }
                        var menuOpen by remember { mutableStateOf(false) }
                        var snoozeSubmenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.message_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false; snoozeSubmenu = false },
                        ) {
                            if (snoozeSubmenu) {
                                val context = LocalContext.current
                                snoozePresets(context).forEach { (label, until) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            menuOpen = false; snoozeSubmenu = false
                                            viewModel.snooze(until, onBack)
                                        },
                                    )
                                }
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_reply)) },
                                    onClick = { menuOpen = false; onReply("reply") },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_reply_all)) },
                                    onClick = { menuOpen = false; onReply("replyAll") },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_forward)) },
                                    onClick = { menuOpen = false; onReply("forward") },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (loaded.email.isFlagged) R.string.message_unflag
                                                else R.string.message_flag,
                                            ),
                                        )
                                    },
                                    onClick = { menuOpen = false; viewModel.toggleFlag() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_archive)) },
                                    onClick = { menuOpen = false; viewModel.archive(onBack) },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (inJunk) R.string.message_not_spam
                                                else R.string.message_report_spam,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        if (inJunk) viewModel.notSpam(onBack) else viewModel.reportSpam(onBack)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_snooze)) },
                                    onClick = { snoozeSubmenu = true },
                                )
                                if (senderEmail != null) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (senderAllowed) R.string.message_images_stop_sender
                                                    else R.string.message_images_always_sender,
                                                ),
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.setImagesAlwaysAllowed(senderEmail, !senderAllowed)
                                        },
                                    )
                                }
                            }
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
                    Text(
                        stringResource(R.string.message_load_error, s.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { viewModel.load(emailId, accountId) }) {
                        Text(stringResource(R.string.message_retry))
                    }
                }
                is MessageState.Loaded -> MessageBody(
                    email = s.email,
                    siblings = thread,
                    blockRemote = !showRemote,
                    stripTracking = stripTracking,
                    confirmLinks = confirmLinks,
                    onOpenEmail = onOpenEmail,
                    attachmentStatus = attachmentStatus,
                    onOpenAttachment = viewModel::openAttachment,
                    inlineImages = inlineImages,
                    textZoom = messageTextSize.zoom,
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
    stripTracking: Boolean,
    confirmLinks: Boolean,
    onOpenEmail: (String) -> Unit,
    attachmentStatus: String?,
    onOpenAttachment: (EmailBodyPart) -> Unit,
    inlineImages: Map<String, String>,
    textZoom: Int,
) {
    Column(Modifier.fillMaxSize()) {
        Header(email)
        if (siblings.isNotEmpty()) {
            HorizontalDivider()
            ThreadSection(siblings, onOpenEmail)
        }
        val attachments = email.fileAttachmentParts()
        if (attachments.isNotEmpty()) {
            HorizontalDivider()
            AttachmentSection(attachments, attachmentStatus, onOpenAttachment)
        }
        HorizontalDivider()
        val scheme = MaterialTheme.colorScheme
        val dark = scheme.surface.luminance() < 0.5f
        val emailTheme = EmailTheme(
            background = scheme.surface.toCssHex(),
            text = scheme.onSurface.toCssHex(),
            link = scheme.primary.toCssHex(),
            dark = dark,
        )
        val html = remember(email, inlineImages, emailTheme) { buildHtmlDocument(email, inlineImages, emailTheme) }
        EmailWebView(
            html = html,
            blockRemote = blockRemote,
            stripTracking = stripTracking,
            confirmLinks = confirmLinks,
            backgroundColor = scheme.surface.toArgb(),
            textZoom = textZoom,
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
                text = stringResource(R.string.message_thread_more, siblings.size),
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
                        text = sibling.from.firstOrNull()?.display()
                            ?: stringResource(R.string.message_unknown_sender),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sibling.subject?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.message_no_subject),
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
            text = stringResource(R.string.message_attachments, attachments.size),
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
                        text = att.name ?: stringResource(R.string.message_attachment_fallback),
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
            text = email.subject?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.message_no_subject),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Monogram(seed = sender?.email ?: "?", label = sender?.display() ?: "?")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = sender?.display() ?: stringResource(R.string.message_unknown_sender),
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
                text = stringResource(
                    R.string.message_to,
                    email.to.joinToString { it.display() },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmailWebView(
    html: String,
    blockRemote: Boolean,
    stripTracking: Boolean,
    confirmLinks: Boolean,
    backgroundColor: Int,
    textZoom: Int,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // When confirmation is on, a tapped link is held here until the user approves it.
    var pendingLink by remember { mutableStateOf<Uri?>(null) }
    val client = remember { BlockingWebViewClient() }
    client.blockRemote = blockRemote
    client.stripTracking = stripTracking
    client.onOpenUrl = { uri -> if (confirmLinks) pendingLink = uri else openExternally(context, uri) }
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
            // Match the WebView's own background to the theme so it doesn't flash white.
            webView.setBackgroundColor(backgroundColor)
            webView.settings.textZoom = textZoom
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
    )

    pendingLink?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(stringResource(R.string.message_open_link_title)) },
            text = { Text(uri.toString()) },
            confirmButton = {
                TextButton(onClick = { openExternally(context, uri); pendingLink = null }) {
                    Text(stringResource(R.string.message_open_link_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLink = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/** Open a URL in the system's default handler (browser/chooser); no-op if none can. */
private fun openExternally(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // No app can handle the URL — silently ignore rather than crash.
    }
}

/** A Compose [Color] as a CSS hex string (#RRGGBB). */
private fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())

/** Blocks remote (http/https) resource loads while [blockRemote]; opens links externally. */
private class BlockingWebViewClient : WebViewClient() {
    var blockRemote: Boolean = true
    var stripTracking: Boolean = true

    /** Reports the final (possibly cleaned) URL to open; the composable decides how. */
    var onOpenUrl: (Uri) -> Unit = {}

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
        // Strip tracking params (utm_*, fbclid, …) so the sender can't tell the link was clicked.
        val target = if (stripTracking) Uri.parse(LinkCleaner.strip(url.toString())) else url
        onOpenUrl(target)
        return true
    }
}

private fun buildHtmlDocument(
    email: Email,
    inlineImages: Map<String, String> = emptyMap(),
    theme: EmailTheme = EmailTheme("#ffffff", "#111111", "#0b5fff", false),
): String {
    val htmlContent = email.htmlContent()
    var inner = htmlContent
        ?: email.textContent()?.let { "<pre class=\"plain\">${escapeHtml(it)}</pre>" }
        ?: "<p>${escapeHtml(email.preview ?: "(no content)")}</p>"
    // Embed inline images: replace cid: references with their data URIs.
    inlineImages.forEach { (cid, dataUri) ->
        inner = inner.replace("cid:$cid", dataUri).replace("cid:<$cid>", dataUri)
    }
    val richHtml = htmlContent != null
    if (theme.dark && richHtml) {
        // Rich HTML carries its own (usually white) backgrounds we can't restyle reliably.
        // Wrap it and invert: a deterministic dark render that works for any markup. hue-rotate
        // keeps colours roughly intact; media is re-inverted so photos/logos look normal.
        return """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { background-color: ${theme.background}; margin: 0; }
              #jmail-content { filter: invert(1) hue-rotate(180deg); padding: 16px;
                               font-family: sans-serif; line-height: 1.45;
                               word-wrap: break-word; overflow-wrap: break-word; }
              #jmail-content img, #jmail-content picture, #jmail-content video,
              #jmail-content iframe { filter: invert(1) hue-rotate(180deg); }
              img { max-width: 100%; height: auto; }
            </style></head><body><div id="jmail-content">$inner</div></body></html>
        """.trimIndent()
    }
    // Plain/simple text (or light mode): paint with the resolved theme colours directly.
    val bg = if (theme.dark) theme.background else "#ffffff"
    val fg = if (theme.dark) theme.text else "#111111"
    val link = if (theme.dark) theme.link else "#0b5fff"
    return """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="color-scheme" content="${if (theme.dark) "dark" else "light"}">
        <style>
          html, body { background-color: $bg; }
          body { margin: 16px; font-family: sans-serif; line-height: 1.45; color: $fg;
                 word-wrap: break-word; overflow-wrap: break-word; }
          img { max-width: 100%; height: auto; }
          a { color: $link; }
          pre.plain { white-space: pre-wrap; word-wrap: break-word; font-family: sans-serif; }
        </style></head><body>$inner</body></html>
    """.trimIndent()
}

/** Resolved theme colours (CSS hex) handed to the email WebView so it matches the app. */
private data class EmailTheme(val background: String, val text: String, val link: String, val dark: Boolean)

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private val fullFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", appLocale)

private fun formatFull(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull() ?: return ""
    return instant.atZone(ZoneId.systemDefault()).format(fullFormatter)
}

/** Snooze presets → (label, epoch-millis), computed in the device's time zone. */
private fun snoozePresets(context: android.content.Context): List<Pair<String, Long>> {
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    fun at(day: java.time.ZonedDateTime, hour: Int) =
        day.withHour(hour).withMinute(0).withSecond(0).withNano(0)
    val thisEvening = at(now, 18).let { if (it.isAfter(now)) it else at(now.plusDays(1), 18) }
    val nextWeek = at(now.with(java.time.DayOfWeek.MONDAY).plusWeeks(1), 8)
    return listOf(
        context.getString(R.string.snooze_in_1_hour) to now.plusHours(1),
        context.getString(R.string.snooze_this_evening) to thisEvening,
        context.getString(R.string.snooze_tomorrow) to at(now.plusDays(1), 8),
        context.getString(R.string.snooze_next_week) to nextWeek,
    ).map { (label, time) -> label to time.toInstant().toEpochMilli() }
}
