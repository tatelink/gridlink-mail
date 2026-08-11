package app.gridlink.core.data.mail

import app.gridlink.core.jmap.JmapException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Why a sign-in attempt did not end in a session.
 *
 * The point of naming these separately is that they have different fixes, and the screen can only
 * say the right sentence if it is told which one happened. Before this existed, every one of them
 * arrived at the UI as the same "no server found, type it in manually" — which is correct advice
 * for exactly one of the cases below and actively misleading for the rest. Somebody on aeroplane
 * mode was being asked to go and find their JMAP hostname.
 */
enum class SignInFailure {
    /** The device has no usable network at all. Nothing about the account is known to be wrong. */
    OFFLINE,

    /** The name does not resolve. A typo in the domain, or a server that is not published in DNS. */
    DNS,

    /** Resolved, but nothing accepted a connection: wrong port, firewall, server down. */
    REFUSED,

    /** Connected, but the TLS handshake failed: an expired, self-signed or mismatched certificate. */
    TLS,

    /** Something answered but not in time. The usual shape of a captive portal or a black-holed port. */
    TIMEOUT,

    /** Reached and spoke, but turned the credentials down (HTTP 401/403, or a refused IMAP LOGIN). */
    REJECTED,

    /** Something is listening and answering, but it is not a mail server we can talk to. */
    NOT_A_SERVER,

    /** Anything else. The step's own text is the only description available. */
    OTHER,
}

/**
 * One thing a sign-in attempt tried, and what came back.
 *
 * 🔴 Both fields are shown to the user and copied to their clipboard, so **nothing secret may ever
 * be put in either**. Nothing here is built from a password, a token or an auth header; the IMAP
 * client redacts its own command verbs before they reach an exception message (see `ImapClient`),
 * and the JMAP client's messages carry a URL and an HTTP code. Keep it that way: this log exists to
 * be pasted into an email to a stranger.
 */
data class SignInStep(val what: String, val outcome: String)

/**
 * The record of one sign-in attempt, in the order it happened.
 *
 * This is the "copyable debug log" half of a sign-in that never hangs. A user whose sign-in fails
 * on their own server cannot be asked to read a logcat, and the app deliberately collects no
 * telemetry, so the only way a failure reaches anyone who can act on it is if the person holding
 * the phone can copy it out. Steps accumulate even on the successful path, because the interesting
 * failures are the ones that eventually succeeded after four wrong turns.
 *
 * ⚠️ Not thread-safe on purpose. One attempt is one coroutine; a log shared across attempts would
 * interleave two stories into one unreadable one.
 */
class SignInLog {
    private val steps = mutableListOf<SignInStep>()

    fun add(what: String, outcome: String) {
        steps += SignInStep(what, outcome)
    }

    /** Record [what] as having ended in [t], classified. Returns the classification. */
    fun add(what: String, t: Throwable): SignInFailure {
        val failure = classifySignInFailure(t)
        steps += SignInStep(what, describeSignInFailure(t))
        return failure
    }

    fun steps(): List<SignInStep> = steps.toList()
}

/**
 * What [t] means for somebody trying to sign in.
 *
 * ⚠️ Cause-walking is deliberate: OkHttp and the IMAP client both wrap the interesting exception
 * inside an [IOException] whose own message is a generic one, so classifying only the outermost
 * throwable turns every distinguishable failure back into [OTHER].
 */
fun classifySignInFailure(t: Throwable): SignInFailure {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < 8) {
        when (current) {
            is UnknownHostException -> return SignInFailure.DNS
            is SSLHandshakeException, is SSLException -> return SignInFailure.TLS
            is SocketTimeoutException -> return SignInFailure.TIMEOUT
            is NoRouteToHostException -> return SignInFailure.REFUSED
            is ConnectException -> return SignInFailure.REFUSED
            is JmapException ->
                if (current.httpCode == 401 || current.httpCode == 403) return SignInFailure.REJECTED
                else return SignInFailure.NOT_A_SERVER
        }
        current = current.cause?.takeIf { it !== current }
        depth++
    }
    return SignInFailure.OTHER
}

/**
 * A one-line, secret-free account of [t] for the copyable log.
 *
 * The exception's own message is kept when it has one, because on the failures that matter it names
 * the host or the HTTP code, and a classification alone ("could not connect") is exactly the kind of
 * message this whole item exists to get rid of.
 */
fun describeSignInFailure(t: Throwable): String {
    val kind = classifySignInFailure(t)
    val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    return "$kind: $message"
}

/**
 * Of two failures seen while sweeping candidate hosts, the one worth reporting.
 *
 * 🔴 [SignInFailure.DNS] ranks barely above nothing, because most candidates are GUESSES: `jmap.`
 * and `api.` subdomains that the overwhelming majority of domains have never published. "That name
 * does not resolve" is the expected answer from a guess and says nothing about the user's account.
 * A refusal, a TLS failure or a rejection came from a host that actually exists, so any of those
 * outranks it however late in the sweep it turned up.
 */
fun moreTelling(current: SignInFailure, candidate: SignInFailure): SignInFailure =
    if (rank(candidate) > rank(current)) candidate else current

private fun rank(failure: SignInFailure): Int = when (failure) {
    SignInFailure.OTHER -> 0
    SignInFailure.DNS -> 1
    SignInFailure.TIMEOUT -> 2
    SignInFailure.REFUSED -> 3
    SignInFailure.NOT_A_SERVER -> 4
    SignInFailure.TLS -> 5
    SignInFailure.OFFLINE -> 6
    SignInFailure.REJECTED -> 7
}

/** The log as plain text, for the clipboard. */
fun List<SignInStep>.asClipboardText(): String =
    joinToString(separator = "\n") { "${it.what}: ${it.outcome}" }
