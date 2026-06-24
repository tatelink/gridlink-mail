package app.jmail.core.jmap

/**
 * Any failure talking to a JMAP server (transport, HTTP, parsing, or a JMAP-level
 * error). [httpCode] carries the HTTP status when the failure was an HTTP response
 * (null for transport/parse errors) — autodiscovery uses it to tell "reached the
 * server but the credentials were rejected" (401/403) from "no server here".
 */
class JmapException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
) : Exception(message, cause)
