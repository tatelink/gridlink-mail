package app.jmail.core.jmap

/**
 * Entry point / constants for the JMAP protocol layer (RFC 8620 core, RFC 8621 mail).
 *
 * The real client — Session fetch, batched method calls, typed models — lands in M1.
 * For now this marks the module and pins the capability URIs we target.
 */
object Jmap {
    const val CORE_CAPABILITY = "urn:ietf:params:jmap:core"
    const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"

    /** Well-known path for JMAP autodiscovery (RFC 8620 §2.2). */
    const val WELL_KNOWN_PATH = "/.well-known/jmap"
}
