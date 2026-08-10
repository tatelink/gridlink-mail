package app.gridlink.core.data.net

import kotlin.random.Random

/**
 * One SRV record (RFC 2782): where a domain says a named service actually lives.
 *
 * [target] is the hostname with its trailing root dot already removed, so it is ready to put in a
 * URL. It is empty for the root target `.`, which RFC 2782 defines as "decidedly not available" —
 * see [SrvSelection.order] for why that does not switch anything off here.
 */
data class SrvRecord(
    val priority: Int,
    val weight: Int,
    val port: Int,
    val target: String,
) {
    /**
     * The authority to put in a URL: `host` when the port is the protocol's usual one, `host:port`
     * otherwise.
     *
     * 🔴 The port is not decoration. RFC 8620 §2.2 has the client fetch
     * `https://hostname[:port]/.well-known/jmap`, so a domain that publishes JMAP on 8443 is only
     * reachable if the port survives all the way into the URL. Dropping it would turn a correct
     * record into a candidate that silently fails and falls back to guessing.
     */
    fun authority(defaultPort: Int): String =
        if (port == defaultPort) target else "$target:$port"
}

/**
 * RFC 2782 ordering: strictly by priority, then weighted-random inside each priority band.
 *
 * The weighting is not busywork. It is how a domain spreads setup traffic over several servers, and
 * a client that always took the first record listed would send every new account on that domain to
 * the same box.
 */
object SrvSelection {

    /**
     * The records to try, best first.
     *
     * 🔴 Targets of `.` are dropped, and dropping them is all that happens: SRV here is a discovery
     * path that can only ever ADD candidates. RFC 2782 lets a domain publish `.` to say "no service
     * here", but honouring that as a veto would let one stray DNS record break setup for a domain
     * whose well-known probe works today. The rule is that a bad or hostile answer costs the user a
     * few wasted milliseconds, never a working sign-in.
     *
     * [random] is a parameter so the weighting can be tested; callers use the default.
     */
    fun order(records: List<SrvRecord>, random: Random = Random.Default): List<SrvRecord> =
        records
            .filter { it.target.isNotEmpty() && it.port in 1..65535 }
            .groupBy { it.priority }
            .toSortedMap()
            .values
            .flatMap { band -> weighted(band, random) }

    /**
     * RFC 2782's selection inside one priority band: pick a running-sum target across the remaining
     * weights, take the record it lands in, repeat without it.
     *
     * ⚠️ The all-zero case is picked uniformly instead. The running-sum walk would otherwise stop at
     * the first record every time (a target of 0 is `>= 0` immediately), which is exactly the
     * fixed order the weighting exists to avoid.
     */
    private fun weighted(band: List<SrvRecord>, random: Random): List<SrvRecord> {
        val pool = band.toMutableList()
        val ordered = ArrayList<SrvRecord>(pool.size)
        while (pool.isNotEmpty()) {
            val total = pool.sumOf { it.weight }
            val picked = if (total == 0) {
                pool[random.nextInt(pool.size)]
            } else {
                val cut = random.nextInt(total + 1)
                var running = 0
                pool.first { running += it.weight; running >= cut }
            }
            ordered += picked
            pool.remove(picked)
        }
        return ordered
    }
}

/**
 * The service names mail publishes under, and the address-to-domain rule they share.
 *
 * ⚠️ There is deliberately no `_jmaps._tcp`. JMAP is HTTPS by definition and RFC 8620 §2.2 defines
 * exactly one record, `_jmap._tcp`; a second name would be one this app invented. The IMAP-side
 * names come from RFC 6186, which does define a secure/insecure split, and only the secure ones are
 * used here.
 */
object MailSrv {

    /** JMAP session discovery (RFC 8620 §2.2). */
    fun jmap(domain: String): String = "_jmap._tcp.$domain"

    /** Implicit-TLS IMAP (RFC 6186 §3.2). ⛔ `_imap._tcp` (cleartext) is not looked up. */
    fun imaps(domain: String): String = "_imaps._tcp.$domain"

    /** Mail submission (RFC 6186 §3.1). */
    fun submission(domain: String): String = "_submission._tcp.$domain"

    /**
     * The domain to look up for an address, or null when the address cannot name one.
     *
     * 🔴 Identical to the rule [app.gridlink.core.jmap.Jmap.autodiscoverHosts] applies, and it has
     * to be: SRV results are prepended to that function's guesses, so a domain the two disagreed
     * about would be probed by one path and not the other.
     */
    fun domainOf(email: String): String? = app.gridlink.core.jmap.Jmap.mailDomain(email)

    /** JMAP is HTTPS, so 443 is the port a candidate URL need not mention. */
    private const val HTTPS_PORT = 443

    /**
     * The submission port that means implicit TLS (RFC 8314). RFC 6186 registers ONE submission
     * name covering both this and 587, so the port is the only thing saying which a record means.
     */
    private const val IMPLICIT_TLS_SUBMISSION_PORT = 465

    /**
     * Hosts to probe for a JMAP session: what the domain published, best first, then [fallback]
     * (the conventional `mail.` / `jmap.` / `api.` guesses).
     *
     * 🔴 SRV goes first and the guesses are never dropped. A published record is the domain's own
     * answer and outranks a guess, but treating it as the only answer makes discovery *worse* for a
     * domain whose record has gone stale: the guess that works today has to still be there behind
     * it. Because of that, this can only ever lengthen the candidate list, which is what lets
     * [SrvSelection.order] be so relaxed about a hostile answer.
     */
    fun jmapCandidates(
        published: List<SrvRecord>,
        fallback: List<String>,
        random: Random = Random.Default,
    ): List<String> =
        (SrvSelection.order(published, random).map { it.authority(HTTPS_PORT) } + fallback).distinct()

    /**
     * The IMAP and submission servers a domain published (RFC 6186), or null when it published
     * neither usable one. Either half may be absent: a form with two of its four fields filled in
     * is still two fields better than one the user types blind.
     */
    fun imapEndpoints(
        imaps: List<SrvRecord>,
        submission: List<SrvRecord>,
        random: Random = Random.Default,
    ): ImapEndpoints? {
        val incoming = SrvSelection.order(imaps, random).firstOrNull()
        val outgoing = SrvSelection.order(submission, random).firstOrNull()
        if (incoming == null && outgoing == null) return null
        return ImapEndpoints(
            imapHost = incoming?.target,
            imapPort = incoming?.port,
            smtpHost = outgoing?.target,
            smtpPort = outgoing?.port,
            // 🔴 Guessing this the other way round would open a cleartext connection to a port that
            // only speaks TLS. `_imaps` needs no such call: RFC 6186 gives it its own name.
            smtpImplicitTls = outgoing?.port == IMPLICIT_TLS_SUBMISSION_PORT,
        )
    }
}

/**
 * What a domain publishes about its IMAP and submission servers.
 *
 * ⚠️ Nothing here has been contacted. These are claims a domain's DNS makes, offered to the user as
 * a starting point in a form they can still edit, and proven only when Connect dials them. That is
 * why this fills a form rather than saving an account.
 */
data class ImapEndpoints(
    val imapHost: String?,
    val imapPort: Int?,
    val smtpHost: String?,
    val smtpPort: Int?,
    /** True when [smtpPort] is the implicit-TLS submission port; STARTTLS otherwise. */
    val smtpImplicitTls: Boolean,
)
