package app.sterna.core.imap

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * A one-connection, scripted SMTP submission server on loopback — the `SmtpClient` sibling of
 * [FakeImapServer]. `send()` takes host and port from [MailServerConfig] and opens no TLS when the
 * security is [MailSecurity.NONE], so a server here observes the **exact command sequence** the
 * shipped client writes, with nothing changed in production code to accommodate it.
 *
 * The server never decides what the client should do: it only ANNOUNCES capabilities ([authLine])
 * and answers. Tests assert on [commands], the lines that actually left the client.
 */
internal class FakeSmtpServer(
    /** The `AUTH` capability to advertise in the EHLO response (e.g. `"AUTH PLAIN LOGIN"`), or none. */
    private val authLine: String? = null,
    /** When true every canned reply goes out as a multi-line ESMTP response (`250-…` then `250 …`). */
    private val multiline: Boolean = false,
    /**
     * Full override for one command: a non-null result is written verbatim (one entry per line,
     * CRLF added) instead of the canned reply. Not for `DATA` — the dot-terminated body mode is
     * driven by the canned path.
     */
    private val respond: (String) -> List<String>? = { null },
) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    /** Every command line received, in order, exactly as written by the client. */
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /** The DATA payload of the accepted message, or null when nothing was accepted. */
    @Volatile
    var delivered: String? = null
        private set

    val config = MailServerConfig(
        host = server.inetAddress.hostAddress,
        port = server.localPort,
        security = MailSecurity.NONE,
        username = USER,
        // Long on purpose: base64 of it, and of the SASL PLAIN blob, both exceed 76 characters, so a
        // line-wrapping encoder (OutgoingMime's getMimeEncoder) would split the command in two and
        // the sequence assertions would see the halves.
        password = PASSWORD,
    )

    private val thread = Thread(::serve, "fake-smtp").apply { isDaemon = true; start() }

    private fun serve() {
        runCatching {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                // ISO-8859-1 so a scripted reply goes out byte for byte, as in FakeImapServer.
                val writer = OutputStreamWriter(socket.outputStream, Charsets.ISO_8859_1)
                fun reply(lines: List<String>) {
                    lines.forEach { writer.write("$it\r\n") }
                    writer.flush()
                }
                reply(canned("220", "fake ESMTP ready"))
                var loginStep = 0 // 1 = awaiting the base64 user, 2 = awaiting the base64 password
                var inData = false
                val body = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (inData) {
                        if (line == ".") {
                            inData = false
                            delivered = body.toString()
                            reply(canned("250", "2.0.0 queued as FAKE1"))
                        } else {
                            body.append(line).append("\r\n")
                        }
                        continue
                    }
                    commands.add(line)
                    val scripted = respond(line)
                    if (scripted != null) {
                        reply(scripted)
                        continue
                    }
                    val response = when {
                        line.startsWith("EHLO", ignoreCase = true) -> ehloReply()
                        line.equals("AUTH LOGIN", ignoreCase = true) -> {
                            loginStep = 1
                            listOf("334 VXNlcm5hbWU6")
                        }
                        line.startsWith("AUTH PLAIN", ignoreCase = true) -> canned("235", "2.7.0 authenticated")
                        loginStep == 1 -> {
                            loginStep = 2
                            listOf("334 UGFzc3dvcmQ6")
                        }
                        loginStep == 2 -> {
                            loginStep = 0
                            canned("235", "2.7.0 authenticated")
                        }
                        line.startsWith("MAIL FROM", ignoreCase = true) -> canned("250", "2.1.0 sender ok")
                        line.startsWith("RCPT TO", ignoreCase = true) -> canned("250", "2.1.5 recipient ok")
                        line.equals("DATA", ignoreCase = true) -> {
                            inData = true
                            listOf("354 end with <CRLF>.<CRLF>")
                        }
                        line.equals("QUIT", ignoreCase = true) -> listOf("221 2.0.0 bye")
                        else -> canned("250", "2.0.0 ok")
                    }
                    reply(response)
                    if (line.equals("QUIT", ignoreCase = true)) break
                }
            }
        }
    }

    /** The EHLO response: greeting line, the advertised AUTH capability when there is one, HELP. */
    private fun ehloReply(): List<String> = buildList {
        add("250-fake.local greets you")
        authLine?.let { add("250-$it") }
        add("250 HELP")
    }

    private fun canned(code: String, text: String): List<String> =
        if (multiline) listOf("$code-$text", "$code-still $text", "$code $text") else listOf("$code $text")

    /**
     * Commands received so far, minus `QUIT` — the client writes it after the send has already
     * returned, so whether it arrived before the assertions is a race, and it proves nothing.
     */
    fun issued(): List<String> = commands.toList().filterNot { it.equals("QUIT", ignoreCase = true) }

    override fun close() {
        runCatching { server.close() }
        thread.interrupt()
    }

    companion object {
        /**
         * An address, not a bare word, and that is the whole point: a real SMTP username is an
         * address, and a fixture without an '@' cannot tell apart ANY per-account mangling of it.
         * With `USER = "tester"`, `config.username.substringBefore('@')` at the AUTH call site left
         * the suite green while breaking every account that logs in with its address.
         */
        const val USER = "tester@example.org"
        const val PASSWORD = "correct-horse-battery-staple-correct-horse-battery-staple-0123456789"
    }
}

/** A message fixture for the submission tests. */
internal fun outgoingFixture(subject: String = "hello there") = OutgoingMessage(
    from = "Tester <tester@example.org>",
    to = listOf("dest@example.org"),
    subject = subject,
    body = "body text",
    messageId = "mid-1@example.org",
    dateMillis = 1_750_000_000_000L,
)
