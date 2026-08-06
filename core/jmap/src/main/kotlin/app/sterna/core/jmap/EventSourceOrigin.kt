package app.sterna.core.jmap

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a push log line says instead of a URL it could not parse — never the URL itself. */
internal const val UNPARSEABLE_EVENT_SOURCE_URL = "<unparseable url>"

/**
 * The origin of an EventSource URL: scheme, host, and the port only when it is not the scheme's
 * default. Nothing else — no path, no query, no fragment, no userinfo.
 *
 * A push log line ends up pasted verbatim into a public bug report, and the EventSource URL is the
 * one place that can carry a credential: servers are free to put anything in its query (an
 * `access_token=` is common) and a URL may carry `user:password@`. The host is what a diagnosis
 * needs; the rest is dropped rather than trimmed. A URL that does not parse yields
 * [UNPARSEABLE_EVENT_SOURCE_URL], never the input echoed back — an input we cannot take apart is
 * exactly the one we cannot vouch for.
 */
internal fun eventSourceOrigin(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return UNPARSEABLE_EVENT_SOURCE_URL
    val port = if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
    return "${parsed.scheme}://${parsed.host}$port"
}
