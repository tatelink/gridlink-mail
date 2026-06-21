package app.jmail.core.jmap

/** Any failure talking to a JMAP server (transport, HTTP, parsing, or a JMAP-level error). */
class JmapException(message: String, cause: Throwable? = null) : Exception(message, cause)
