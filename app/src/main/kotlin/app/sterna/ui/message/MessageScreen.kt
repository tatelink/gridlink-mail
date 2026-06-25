package app.sterna.ui.message

import app.sterna.appLocale
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
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
import app.sterna.R
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.components.Monogram
import app.sterna.ui.rememberMotionEnabled
import app.sterna.util.LinkCleaner
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
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
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
                        // An icon (not a text button) so it can't overrun the back arrow
                        // when the font scale is large; the label is kept for screen readers.
                        IconButton(onClick = { manualShow = true }) {
                            Icon(
                                Icons.Filled.Image,
                                contentDescription = stringResource(R.string.message_show_images),
                            )
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
                is MessageState.Loaded -> ConversationBody(
                    messages = messages,
                    blockRemote = !showRemote,
                    stripTracking = stripTracking,
                    confirmLinks = confirmLinks,
                    attachmentStatus = attachmentStatus,
                    onToggle = viewModel::toggleExpand,
                    onOpenAttachment = viewModel::openAttachment,
                    textZoom = messageTextSize.zoom,
                )
            }
        }
    }
}

@Composable
private fun ConversationBody(
    messages: List<ThreadMessage>,
    blockRemote: Boolean,
    stripTracking: Boolean,
    confirmLinks: Boolean,
    attachmentStatus: String?,
    onToggle: (String) -> Unit,
    onOpenAttachment: (EmailBodyPart, String) -> Unit,
    textZoom: Int,
) {
    // A plain scrolling Column (not a LazyColumn): a thread is small, and not
    // recycling the cards keeps each expanded WebView alive instead of reloading its
    // body every time it scrolls past.
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()),
    ) {
        messages.forEachIndexed { index, msg ->
            if (index > 0) HorizontalDivider()
            MessageCard(
                msg = msg,
                collapsible = messages.size > 1,
                blockRemote = blockRemote,
                stripTracking = stripTracking,
                confirmLinks = confirmLinks,
                attachmentStatus = attachmentStatus,
                onToggle = onToggle,
                onOpenAttachment = onOpenAttachment,
                textZoom = textZoom,
            )
        }
    }
}

/** One message in the stacked conversation: a tappable header that folds/unfolds the
 *  full body (fetched lazily). A lone message stays open and isn't collapsible. */
@Composable
private fun MessageCard(
    msg: ThreadMessage,
    collapsible: Boolean,
    blockRemote: Boolean,
    stripTracking: Boolean,
    confirmLinks: Boolean,
    attachmentStatus: String?,
    onToggle: (String) -> Unit,
    onOpenAttachment: (EmailBodyPart, String) -> Unit,
    textZoom: Int,
) {
    val sender = msg.header.from.firstOrNull()
    val unread = !msg.header.isSeen
    val motionOn = rememberMotionEnabled()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (collapsible) Modifier.clickable { onToggle(msg.id) } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Monogram(seed = sender?.email ?: "?", label = sender?.display() ?: "?")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = sender?.display() ?: stringResource(R.string.message_unknown_sender),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (msg.expanded) {
                    Text(
                        text = formatFull(msg.header.receivedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = msg.header.preview?.takeIf { it.isNotBlank() } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!msg.expanded) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatCompact(msg.header.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unread) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
        // A quick "unroll" as the card opens (respecting reduced motion).
        AnimatedVisibility(
            visible = msg.expanded,
            enter = if (motionOn) expandVertically(tween(200)) + fadeIn(tween(200)) else EnterTransition.None,
            exit = if (motionOn) shrinkVertically(tween(160)) + fadeOut(tween(120)) else ExitTransition.None,
        ) {
            val full = msg.body
            if (full == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    val attachments = full.fileAttachmentParts()
                    if (attachments.isNotEmpty()) {
                        HorizontalDivider()
                        AttachmentSection(attachments, attachmentStatus) { part -> onOpenAttachment(part, msg.id) }
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
                    val html = remember(full, msg.inlineImages, emailTheme) { buildHtmlDocument(full, msg.inlineImages, emailTheme) }
                    EmailWebView(
                        html = html,
                        blockRemote = blockRemote,
                        stripTracking = stripTracking,
                        confirmLinks = confirmLinks,
                        backgroundColor = scheme.surface.toArgb(),
                        textZoom = textZoom,
                        modifier = Modifier.fillMaxWidth(),
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
    val density = LocalDensity.current
    // When confirmation is on, a tapped link is held here until the user approves it.
    var pendingLink by remember { mutableStateOf<Uri?>(null) }
    // Measured content height: the WebView sizes to its content (no internal scroll) so
    // it stacks naturally in the conversation's outer scroll. JS is disabled, so this
    // comes from the native contentHeight, read once layout settles.
    var heightPx by remember { mutableIntStateOf(0) }
    val client = remember { BlockingWebViewClient() }
    client.blockRemote = blockRemote
    client.stripTracking = stripTracking
    client.onOpenUrl = { uri -> if (confirmLinks) pendingLink = uri else openExternally(context, uri) }
    client.onContentHeight = { heightPx = it }
    AndroidView(
        modifier = modifier.then(
            if (heightPx > 0) Modifier.height(with(density) { heightPx.toDp() })
            else Modifier.heightIn(min = 80.dp),
        ),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Explicitly deny every path from email markup to the local filesystem,
                // on-device storage, or geolocation. These are off by default on modern
                // API levels, but untrusted HTML email warrants asserting it.
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                settings.domStorageEnabled = false
                settings.setGeolocationEnabled(false)
                settings.mediaPlaybackRequiresUserGesture = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = client
            }
        },
        update = { webView ->
            // Match the WebView's own background to the theme so it doesn't flash white.
            webView.setBackgroundColor(backgroundColor)
            webView.settings.textZoom = textZoom
            // update() runs on every recomposition; only (re)load when the document
            // actually changed, otherwise expanding one card reloads (and flickers)
            // every other open body in the conversation.
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
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

    /** Reports the rendered content height (Android px) so the view can size to it. */
    var onContentHeight: (Int) -> Unit = {}

    override fun onPageFinished(view: WebView?, url: String?) {
        val wv = view ?: return
        // No JS to measure with, so read the native content height once layout settles,
        // then again shortly after to catch any image reflow.
        fun report() {
            val px = (wv.contentHeight * wv.resources.displayMetrics.density).toInt()
            if (px > 0) onContentHeight(px)
        }
        wv.post { report() }
        wv.postDelayed({ report() }, 250)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (!blockRemote) return null
        // Default-deny: only inert, local sources are allowed through. Anything else — http(s),
        // protocol-relative URLs (which arrive with a null/empty scheme), ws, ftp, prefetch — is
        // blocked so a tracking pixel can't fire by any vector. Keying on "http"/"https" alone
        // (the old behaviour) let "//evil.com/x.gif" and friends slip past.
        val scheme = request?.url?.scheme?.lowercase()
        return if (scheme == "data" || scheme == "cid" || scheme == "about") {
            null
        } else {
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        val scheme = url.scheme?.lowercase()
        // Only hand off web/contact schemes to the system. Never forward intent:, javascript:,
        // file:, content:, data: etc. — an <a href="intent://…"> in a hostile email could
        // otherwise redirect into another app or an internal component.
        if (scheme !in SAFE_OPEN_SCHEMES) return true // swallow: don't navigate, don't open
        // Act only on a genuine user tap. Auto-navigations (<meta refresh>, scripted redirects)
        // arrive without a gesture; ignoring them stops a message from opening an app or firing
        // a network request just by being viewed.
        if (!request.hasGesture()) return true
        // Strip tracking params (utm_*, fbclid, …) so the sender can't tell the link was clicked.
        val target = if (stripTracking) Uri.parse(LinkCleaner.strip(url.toString())) else url
        onOpenUrl(target)
        return true
    }

    private companion object {
        val SAFE_OPEN_SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "geo")
    }
}

/**
 * Content-Security-Policy for rendered email. JavaScript is already disabled on the WebView;
 * this is defense-in-depth that also kills scripts, plugins, iframes, and form submissions
 * (phishing posts) outright, while still allowing inline styles and images. Remote images are
 * permitted by the policy but gated at load time by [BlockingWebViewClient] so the "show images"
 * toggle keeps working; the policy stops every other remote vector (connect/frame/object/script).
 */
private const val CSP_META =
    "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
        "default-src 'none'; img-src data: cid: http: https:; style-src 'unsafe-inline'; " +
        "font-src data:; media-src data: cid: http: https:; " +
        "form-action 'none'; base-uri 'none'; frame-src 'none'; object-src 'none'\">"

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
            $CSP_META
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { background-color: ${theme.background}; margin: 0; }
              #sterna-content { filter: invert(1) hue-rotate(180deg); padding: 16px;
                               font-family: sans-serif; line-height: 1.45;
                               word-wrap: break-word; overflow-wrap: break-word; }
              #sterna-content img, #sterna-content picture, #sterna-content video,
              #sterna-content iframe { filter: invert(1) hue-rotate(180deg); }
              img { max-width: 100%; height: auto; }
            </style></head><body><div id="sterna-content">$inner</div></body></html>
        """.trimIndent()
    }
    // Plain/simple text (or light mode): paint with the resolved theme colours directly,
    // so the body's background matches the app surface (no seam below the message).
    val bg = theme.background
    val fg = theme.text
    val link = theme.link
    return """
        <!DOCTYPE html><html><head>
        $CSP_META
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
private val compactFormatter = DateTimeFormatter.ofPattern("d MMM", appLocale)

private fun formatFull(iso: String?): String = formatWith(iso, fullFormatter)

/** Short date (e.g. "21 Jun") for a collapsed conversation card. */
private fun formatCompact(iso: String?): String = formatWith(iso, compactFormatter)

private fun formatWith(iso: String?, formatter: DateTimeFormatter): String {
    if (iso.isNullOrBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull() ?: return ""
    return instant.atZone(ZoneId.systemDefault()).format(formatter)
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
