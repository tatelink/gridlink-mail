package app.sterna.core.data.unsubscribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Why a one-click unsubscribe did not go through; each maps to its own sentence on screen. */
enum class UnsubscribeFailure {
    /**
     * The server answered 3xx. Refused rather than followed: OkHttp turns a POST into a GET on
     * 301/302/303, so following it would replace the one request that loads nothing with exactly
     * the page fetch this feature exists to avoid (decision D3).
     */
    REDIRECT,

    /** The request never left the device (no route, no DNS, timed out). */
    OFFLINE,

    /** The server said no, or the URL was one we refuse to POST to. */
    REFUSED,
}

/** Outcome of a one-click unsubscribe POST. */
sealed interface UnsubscribeResult {
    data object Sent : UnsubscribeResult
    data class Failed(val reason: UnsubscribeFailure) : UnsubscribeResult
}

/** RFC 8058 §3.1: the POST body, verbatim and complete. Nothing else is ever sent. */
internal const val ONE_CLICK_BODY = "List-Unsubscribe=One-Click"

private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

/**
 * Whether a URL may be POSTed to at all: https, and nothing else (decision D4).
 *
 * Refused HERE rather than left to fail inside the platform's cleartext policy, so the reader
 * gets our sentence instead of an `IOException`'s. The parser already drops `http:` URIs, which
 * makes this the second lock on the same door — deliberately, since this function is what any
 * future caller will reach for.
 */
internal fun isPostableUnsubscribeUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

/**
 * The exact request a one-click unsubscribe sends.
 *
 * ⛔ **No `Authorization` header, ever.** This POST goes to a third-party domain named by the
 * sender of an email. The project has no global interceptor (every JMAP call sets the header by
 * hand), so an absent line is genuinely an absent header — the invariant is kept by NOT reusing
 * `JmapClient.postWithRetry`, which requires a token.
 *
 * ⛔ The URL is used verbatim: `LinkCleaner` is NOT applied (decision D6). The subscriber token
 * frequently lives in the query parameters, and stripping one would either break the request or,
 * worse, unsubscribe somebody else.
 */
internal fun oneClickRequest(url: String): Request = Request.Builder()
    .url(url)
    .post(ONE_CLICK_BODY.toRequestBody(FORM_MEDIA_TYPE))
    .build()

/** What an HTTP status code means for the reader. */
internal fun oneClickOutcome(code: Int): UnsubscribeResult = when {
    code in 200..299 -> UnsubscribeResult.Sent
    code in 300..399 -> UnsubscribeResult.Failed(UnsubscribeFailure.REDIRECT)
    else -> UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED)
}

/**
 * What a thrown failure means for the reader: the transport dying is "no network", anything else
 * keeps the neutral refusal. Matched on the exception TYPE, never on its text — that sentence is
 * written by the platform resolver and changes with the Android version and the system language.
 *
 * A deliberate local twin of `app.sterna.net.isNetworkFailure` (which is `internal` to the app
 * module and unreachable from here); same three types, same reasoning.
 */
internal fun oneClickFailure(t: Throwable): UnsubscribeResult {
    var error: Throwable? = t
    var hops = 0
    while (error != null && hops++ < 8) {
        if (error is UnknownHostException || error is SocketException || error is InterruptedIOException) {
            return UnsubscribeResult.Failed(UnsubscribeFailure.OFFLINE)
        }
        error = error.cause
    }
    return UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED)
}

/**
 * The HTTP client for unsubscribe POSTs, built here and NOT shared with the JMAP one.
 *
 * Two settings carry the whole point of the feature:
 *  - `followRedirects(false)` — see [UnsubscribeFailure.REDIRECT];
 *  - `followSslRedirects(false)` — and never down to cleartext either.
 *
 * Short timeouts: this is a gesture the reader is watching, not a background sync.
 */
internal fun defaultUnsubscribeHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/**
 * Sends the RFC 8058 one-click unsubscribe: a single `POST` carrying
 * `List-Unsubscribe=One-Click` and nothing else. No browser is opened, no document is fetched,
 * no image is loaded — which is the entire privacy argument for the feature.
 */
class UnsubscribeClient(
    private val httpClient: OkHttpClient = defaultUnsubscribeHttpClient(),
) {
    /** Unsubscribe from [url], or say why not. Refuses anything that is not https (D4). */
    suspend fun oneClick(url: String): UnsubscribeResult =
        if (!isPostableUnsubscribeUrl(url)) {
            UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED)
        } else {
            post(url)
        }

    /**
     * The POST itself, without the scheme gate — so the wire behaviour (headers, body, and above
     * all the refusal to follow a redirect) is exercised against a local test server, which
     * speaks plain http. Callers go through [oneClick].
     */
    internal suspend fun post(url: String): UnsubscribeResult = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(oneClickRequest(url)).execute().use { response ->
                oneClickOutcome(response.code)
            }
        } catch (t: Throwable) {
            oneClickFailure(t)
        }
    }
}
