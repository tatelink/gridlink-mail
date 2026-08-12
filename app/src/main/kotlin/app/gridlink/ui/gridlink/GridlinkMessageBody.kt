package app.gridlink.ui.gridlink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.gridlink.ui.emailhtml.EmailRemoteContent
import app.gridlink.ui.emailhtml.EmailTheme
import app.gridlink.ui.emailhtml.buildEmailHtmlDocument
import app.gridlink.ui.rememberLeaveOnce
import java.io.ByteArrayInputStream

/**
 * The message body, rendered by a real browser.
 *
 * ## 🔴 Why this replaced `AnnotatedString.fromHtml`
 * The old renderer went through `HtmlCompat` and kept the handful of spans Compose has an
 * equivalent for. That is a rich-text mapper, not a browser: no tables, no images, and no
 * understanding of `<style>`, so the CSS inside a marketing email came out as visible body text and
 * a table-based newsletter came out as a column of stacked cells. The note on the old composable
 * said a WebView was the real answer and that it was "a privacy decision with a UI attached". This
 * is that change: the privacy decision is [blockRemote], and the UI attached to it is
 * [GridlinkImagesBanner].
 *
 * ## What is locked down, and why each one
 * The markup here is written by whoever sent the mail, so every capability is denied unless the
 * body genuinely needs it:
 *
 *  - **No JavaScript.** Nothing in an email needs to run code, and the body's height is measured
 *    from the native scroll range instead (see below) precisely so none has to.
 *  - **No file, content or storage access**, and no geolocation. Off by default on modern API
 *    levels, asserted anyway: a default is not a decision.
 *  - **No remote loads while [blockRemote]**, decided by [EmailRemoteContent.blocked], which is
 *    default-deny rather than a list of blocked schemes. `//evil.com/pixel.gif` arrives with no
 *    scheme at all, and a rule written as "block http and https" waves it through.
 *  - **Links are handed to the system, never followed here**, and only for the schemes in
 *    [EmailRemoteContent.SAFE_OPEN_SCHEMES]. An `<a href="intent://…">` in a hostile message would
 *    otherwise be a route into another app.
 *  - **A CSP on the document itself** ([buildEmailHtmlDocument]), which kills scripts, frames,
 *    objects and form submissions outright. Belt and braces: the phishing form is the one that
 *    still works with JavaScript off.
 *
 * ## 🔴 It owns its vertical scroll, and the sender block above it therefore does not scroll away
 * A WebView measured to its content height inside a Compose `verticalScroll` is the arrangement
 * that looks tidiest and performs worst: Blink cannot cull what it has been told is all visible, so
 * a long newsletter lays out and paints in full on every frame, and the two scroll containers fight
 * over the same drag. Upstream hit exactly that and fixed it by giving the WebView the viewport
 * (Codeberg #5), so this does the same.
 *
 * The cost is real and worth stating: the header, the images banner and the attachment chip are
 * pinned, so about 90dp of a phone screen is permanently spent on them. That is the trade, and it
 * is the right way round, because the alternative is jank on exactly the messages this renderer
 * exists for.
 *
 * ## Why the body is invisible until it reports a height
 * There is no JavaScript to ask, so the height comes from polling the native scroll range until two
 * consecutive readings agree ([BodyReveal.step]). Until then the WebView is at alpha 0: a body
 * revealed mid-layout reflows in front of the reader, and on a heavy message it does it several
 * times. The poll always reports in the end, even when every reading was zero, because this is the
 * only thing that reveals the body and a poll that gave up silently would hide the message for good.
 */
@Composable
internal fun GridlinkMessageBody(
    html: String,
    blockRemote: Boolean,
    text: Color,
    link: Color,
    dark: Boolean,
    modifier: Modifier = Modifier,
    plainText: Boolean = false,
    inlineImages: Map<String, String> = emptyMap(),
) {
    val context = LocalContext.current
    // The reader is a nav destination, so this is that destination's latch: it releases when the
    // user comes back from the browser.
    val leaveOnce = rememberLeaveOnce()
    val theme = remember(text, link, dark) {
        EmailTheme(
            // 🔴 Transparent, and there is deliberately no background parameter to pass instead.
            // Upstream paints the WebView the app's surface colour because upstream's reader IS a
            // flat surface. Gridlink's panel is frosted glass over the aurora, so any colour here
            // would be a flat slab covering it, and picking the panel's own colour does not help:
            // it is translucent, and a hex without alpha resolves to near-white.
            //
            // ⚠️ This only clears what the APP would have painted. A newsletter that sets its own
            // white body background is still a white slab, correctly: that is the message's design,
            // and repainting it would be this app editing somebody's mail.
            background = "transparent",
            text = text.toCssHex(),
            link = link.toCssHex(),
            dark = dark,
        )
    }
    val document = remember(html, plainText, inlineImages, theme) {
        buildEmailHtmlDocument(
            // 🔴 The two are not interchangeable. Plain text goes through the format=flowed reflow
            // and is painted in the app's own colours; HTML carries its own design and is inverted
            // wholesale in a dark theme. Handing plain text over as HTML would skip the reflow and
            // show the sender's 72-column line breaks.
            htmlContent = html.takeIf { !plainText },
            textContent = html.takeIf { plainText },
            inlineImages = inlineImages,
            theme = theme,
        )
    }
    // Height, in the only sense this screen uses it: zero means "not laid out yet, keep it hidden".
    // Nothing is sized from the number.
    var heightPx by remember { mutableIntStateOf(0) }
    val client = remember { GridlinkBodyWebViewClient() }
    client.blockRemote = blockRemote
    client.onContentHeight = { px -> heightPx = px }
    // 🔴 Guarded, like every other way out of the app. A link in a message is the easiest one to
    // double-fire: the WebView reports the tap, the browser takes a moment to come up, and a second
    // tap on a body that has not moved yet opens the page twice.
    client.onOpenUrl = { uri -> leaveOnce { openExternally(context, uri) } }
    // Fades in rather than appearing, because the reveal lands one frame after the poll settles and
    // a body that pops is indistinguishable from a body that reloaded.
    val revealed by animateFloatAsState(
        targetValue = if (heightPx > 0) 1f else 0f,
        label = "gridlinkBodyReveal",
    )
    // 🔴 The native surface behind the document, and it has to be cleared too. The CSS above only
    // stops the DOCUMENT painting a background; the WebView itself defaults to opaque white, which
    // is the slab this is here to remove.
    val backgroundArgb = android.graphics.Color.TRANSPARENT

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize().alpha(revealed),
            factory = { ctx ->
                val webView = GridlinkBodyWebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.domStorageEnabled = false
                    settings.setGeolocationEnabled(false)
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // Scrollbars are the app's job, not the platform's: this panel has a bottom edge
                    // fade and a hairline top rule, and a system scrollbar overlays both.
                    isVerticalScrollBarEnabled = false
                    webViewClient = client
                }
                // 🔴 The WebView is deliberately not the AndroidView's root. An intermediate
                // FrameLayout changes the clip geometry HWUI hands the GL functor when a parent
                // composites this subtree into an offscreen layer, which is what steers it away
                // from the unguarded null-SkSurface path that SIGSEGVs some devices (upstream
                // Codeberg #10, an AOSP bug open since Android 11). Gridlink's detail screens
                // animate in over the list, which is exactly that kind of composite.
                FrameLayout(ctx).apply {
                    addView(
                        webView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            },
            update = { frame ->
                val webView = frame.getChildAt(0) as GridlinkBodyWebView
                webView.setBackgroundColor(backgroundArgb)
                // Second chance to notice the body is laid out. The post-load poll can run and
                // expire entirely while the view still has no size, and nothing restarts it.
                webView.onSized = {
                    if (heightPx <= 0) heightPx = webView.contentRangePx().coerceAtLeast(webView.height)
                }
                // update() runs on every recomposition. 🔴 blockRemote is part of the key: an
                // already-intercepted image request is never re-issued, so "show images" has to
                // reload the document or the pictures stay broken.
                val loadKey = Pair(blockRemote, document)
                if (webView.tag != loadKey) {
                    webView.tag = loadKey
                    heightPx = 0
                    webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                }
            },
        )
    }
}

/**
 * The body WebView.
 *
 * It exists as a subclass for two reasons and no more: to report when it finally has a size, and to
 * keep Gridlink's back gesture working. The gesture is the second one and it is the subtle one.
 *
 * 🔴 Every detail screen in this app is left by dragging it off the right of the window, and a
 * WebView filling that window consumes touches before the drag ever reaches the Compose gesture
 * that owns it. So the view claims a drag for itself unless the drag is *clearly* horizontal, which
 * hands the sideways ones back. The threshold is upstream's, arrived at by complaint: a thumb
 * starting a vertical scroll arcs sideways over the first few millimetres, and a loose threshold
 * loses the reader's place in the message.
 */
private class GridlinkBodyWebView(context: Context) : WebView(context) {
    /** Invoked once the view actually has a size. See [GridlinkMessageBody]'s update block. */
    var onSized: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) onSized?.invoke()
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var axisDecided = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                axisDecided = false
            }

            MotionEvent.ACTION_MOVE -> if (!axisDecided) {
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (dx > touchSlop || dy > touchSlop) {
                    axisDecided = true
                    val clearlyHorizontal = dx > dy * SWIPE_HORIZONTAL_DOMINANCE
                    if (!clearlyHorizontal) parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /** The body's true content height (device px), from the layout rather than `getContentHeight`. */
    fun contentRangePx(): Int = computeVerticalScrollRange()
}

/**
 * How much longer a drag's horizontal side must be than its vertical one before the body lets go of
 * it and the back gesture takes it. 3.0 is about 18 degrees off the horizontal.
 *
 * Upstream tuned this from 1.5 (34 degrees) after readers kept losing the message they were reading:
 * the decision is made as soon as the finger has crossed one touch slop, about 1.3mm, and over that
 * distance a thumb starting a scroll wanders sideways easily. Ties go to reading, which is what the
 * screen is for.
 */
private const val SWIPE_HORIZONTAL_DOMINANCE = 3.0f

/** Blocks remote loads while [blockRemote], hands taps on links to the system, measures the body. */
private class GridlinkBodyWebViewClient : WebViewClient() {
    var blockRemote: Boolean = true

    /** Reports the laid-out content height, which the host uses only as "reveal it now". */
    var onContentHeight: (Int) -> Unit = {}

    /** A link the reader tapped, already checked against [EmailRemoteContent.SAFE_OPEN_SCHEMES]. */
    var onOpenUrl: (Uri) -> Unit = {}

    override fun onPageFinished(view: WebView?, url: String?) {
        val wv = view ?: return
        var last = -1
        var maxSeen = 0
        fun poll(triesLeft: Int) {
            if (wv.parent == null) return // detached (closed or recycled): stop
            // The layout-accurate scroll range, not the legacy getContentHeight(), which
            // under-reports on heavy HTML at first paint.
            val px = (wv as? GridlinkBodyWebView)?.contentRangePx()
                ?: (wv.contentHeight * wv.resources.displayMetrics.density).toInt()
            if (px > maxSeen) maxSeen = px
            when (val step = BodyReveal.step(px, last, maxSeen, triesLeft, wv.height)) {
                is HeightPoll.Report -> onContentHeight(step.px)
                HeightPoll.Retry -> {
                    last = px
                    wv.postDelayed({ poll(triesLeft - 1) }, POLL_INTERVAL_MS)
                }
            }
        }
        wv.post { last = -1; maxSeen = 0; poll(POLL_TICKS) }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        if (!blockRemote) return null
        return if (EmailRemoteContent.blocked(request?.url)) {
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        } else {
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        // Swallow anything that is not a web or contact scheme: don't navigate, don't hand it out.
        if (url.scheme?.lowercase() !in EmailRemoteContent.SAFE_OPEN_SCHEMES) return true
        // A genuine tap only. Auto-navigations (<meta refresh>, redirects) arrive without a gesture,
        // and honouring those would let a message open an app just by being read.
        if (!request.hasGesture()) return true
        onOpenUrl(url)
        return true
    }

    private companion object {
        /** Ticks of the height poll, and how far apart. About a second before it gives an answer. */
        const val POLL_TICKS = 30
        const val POLL_INTERVAL_MS = 32L
    }
}

/**
 * Hand a URL to the system's default handler, and say whether anything took it.
 *
 * 🔴 The Boolean is the contract [rememberLeaveOnce] is built on, not a courtesy: the latch closes
 * only when a launch actually happened, so a tap that nothing on the device can open leaves the next
 * one live instead of deadening every link in the message.
 */
private fun openExternally(context: Context, uri: Uri): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (t: Exception) {
    // Nothing on the device can open it. Silently ignoring beats crashing the reader.
    false
}

/** A Compose colour as the CSS hex the document builder takes. */
private fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())
