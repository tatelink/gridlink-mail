package app.gridlink.ui.gridlink

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.getSystemService
import app.gridlink.ui.emailhtml.EmailTheme
import app.gridlink.ui.emailhtml.buildEmailHtmlDocument
import java.io.ByteArrayInputStream

/**
 * Hand one message to Android's print spooler.
 *
 * ## Why this builds its own WebView instead of printing the reader's
 * The obvious implementation is to keep a reference to [GridlinkMessageBody]'s WebView and call
 * `createPrintDocumentAdapter` on it. That would mean hoisting a live View out of a composable and
 * up through the thread screen so a button in the bottom bar could reach it, and the reference would
 * be wrong exactly when it matters: on the reading pane the bar belongs to the open thread, and the
 * body is free to be recomposed, scrolled or replaced under it. 🔴 A print job that captures a
 * detached or half-loaded WebView prints a blank page, and blank pages come out of a printer.
 *
 * So this builds a fresh one from the message itself. It costs a second render, which happens once,
 * off screen, on a deliberate press of a button — nothing about printing is a hot path.
 *
 * ## 🔴 The WebView is held in a local until the job is handed over
 * `PrintManager.print` does NOT print immediately: it opens the system print UI, which calls back
 * into the adapter to lay out and write pages, possibly seconds later. The WebView must still be
 * alive then. `webView.also { ... }` inside the callback is what keeps a hard reference on the
 * adapter's own closure — remove it and the page renders empty on a device under memory pressure,
 * intermittently, which is the worst kind of bug to be handed by a user.
 *
 * ## ⚠️ Remote content stays blocked, unconditionally
 * The reader has a per-sender allowlist behind it and this does not consult it, on purpose. A print
 * is a second, separate fetch: honouring the allowlist here would mean a tracking pixel firing again
 * every time somebody pressed Print, and would tell the sender when their mail was printed, which is
 * a fact the reader never agreed to share. Inline `cid:` parts still draw — they arrived with the
 * message and touch no network — so a signature logo prints and a spy pixel does not.
 *
 * The document is built with the LIGHT theme regardless of the app's, because the output is ink on
 * white paper. Printing the dark reader would spend a cartridge on the background.
 */
fun gridlinkPrintMessage(context: Context, message: GridlinkMessage) {
    val printManager = context.getSystemService<PrintManager>() ?: return

    val document = buildEmailHtmlDocument(
        htmlContent = message.body.takeIf { !message.bodyIsPlainText },
        textContent = message.body.takeIf { message.bodyIsPlainText },
        inlineImages = message.inlineImages,
        theme = EmailTheme(background = "#ffffff", text = "#111111", link = "#0b5fff", dark = false),
    )

    // The header the paper needs and the screen does not. On screen the sender, subject and date are
    // in the chrome around the body; on a sheet of paper the body is all there is, and a printed
    // message with no indication of who sent it or when is a page nobody can file.
    val header = buildString {
        append("<div style=\"font-family:sans-serif;color:#111;border-bottom:1px solid #ccc;")
        append("padding-bottom:12px;margin-bottom:16px\">")
        append("<div style=\"font-size:18px;font-weight:600\">").append(printEscape(message.subject)).append("</div>")
        append("<div style=\"font-size:13px;color:#555;margin-top:4px\">")
        append(printEscape(message.sender)).append(" &lt;").append(printEscape(message.address)).append("&gt;")
        append("</div>")
        append("<div style=\"font-size:13px;color:#555\">").append(printEscape(message.timestamp)).append("</div>")
        append("</div>")
    }
    // Injected after the opening <body ...> tag, so it lands inside the document's own styling
    // rather than above <html>, where the parser would relocate it and the margins would not apply.
    // Found by scanning to the tag's closing '>' rather than matching a literal "<body>", because
    // the builder is free to put attributes on it and a literal match would silently miss.
    val bodyOpen = document.indexOf("<body", ignoreCase = true)
    val insertAt = if (bodyOpen < 0) -1 else document.indexOf('>', bodyOpen) + 1
    val printable = if (insertAt <= 0) header + document else document.replaceRange(insertAt, insertAt, header)

    val webView = WebView(context)
    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val scheme = request.url.scheme?.lowercase()
            // data: and about: are the document itself and its inline parts. Everything else is the
            // network, and it is answered with an empty 200 rather than blocked at a lower level so
            // the layout finishes instead of waiting on a request that will never arrive.
            return if (scheme == "data" || scheme == "about") {
                null
            } else {
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val job = "${message.subject.ifBlank { "Message" }}.pdf"
            printManager.print(
                job,
                view.createPrintDocumentAdapter(job).also { webViewHolder = view },
                PrintAttributes.Builder().build(),
            )
        }
    }
    // 🔴 A null base URL. A real one would make the document same-origin with a site and let a
    // crafted message read from it; there is nothing to resolve relative URLs against anyway,
    // because every resource that survives the interceptor above is already a data: URI.
    webView.loadDataWithBaseURL(null, printable, "text/HTML", "UTF-8", null)
}

/**
 * The live WebView behind whichever print job is currently in the spooler.
 *
 * ⚠️ A field and not a local because the adapter outlives the function that created it, and the
 * only other thing holding the View is a callback the framework may drop. One at a time is correct:
 * the system print UI is modal, so a second press cannot happen until the first job is gone.
 */
private var webViewHolder: WebView? = null

private fun printEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
