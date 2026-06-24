package app.jmail.core.jmap

/**
 * Entry point / constants for the JMAP protocol layer (RFC 8620 core, RFC 8621 mail).
 */
object Jmap {
    const val CORE_CAPABILITY = "urn:ietf:params:jmap:core"
    const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"
    const val SUBMISSION_CAPABILITY = "urn:ietf:params:jmap:submission"
    const val VACATION_CAPABILITY = "urn:ietf:params:jmap:vacationresponse"
    const val QUOTA_CAPABILITY = "urn:ietf:params:jmap:quota"
    const val SIEVE_CAPABILITY = "urn:ietf:params:jmap:sieve"

    /** Well-known path for JMAP autodiscovery (RFC 8620 §2.2). */
    const val WELL_KNOWN_PATH = "/.well-known/jmap"

    /**
     * Build a JMAP Session URL from user input. Accepts a bare host
     * ("mail.example.com"), a base URL, or a full well-known URL, and
     * normalises to "https://host/.well-known/jmap".
     */
    fun sessionUrlFor(serverInput: String): String {
        var s = serverInput.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        s = s.removeSuffix("/")
        return if (s.endsWith(WELL_KNOWN_PATH)) s else "$s$WELL_KNOWN_PATH"
    }

    /**
     * Candidate server hosts to probe when autodiscovering the JMAP server for an
     * email address (RFC 8620 §2.2 well-known method). The email domain comes
     * first — its `/.well-known/jmap` typically redirects to the real session
     * endpoint, which the HTTP client follows — then conventional `mail.` / `jmap.`
     * subdomains as a fallback. Empty for a malformed address.
     */
    fun autodiscoverHosts(email: String): List<String> {
        val domain = email.substringAfter('@', "").trim().lowercase().removeSuffix(".")
        if (domain.isEmpty() || !domain.contains('.')) return emptyList()
        return listOf(domain, "mail.$domain", "jmap.$domain")
    }
}
