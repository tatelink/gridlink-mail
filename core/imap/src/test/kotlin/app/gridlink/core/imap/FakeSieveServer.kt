@file:Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "MaxLineLength", "ReturnCount", "Wrapping")

package app.gridlink.core.imap

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** One command as the fake server received it: its text with each literal header left in place, and each literal's octets. */
internal class SieveCommand(val line: String, val literals: List<ByteArray>) {
    val verb: String get() = line.substringBefore(' ').uppercase()

    /** The quoted-string arguments, unescaped, in order. */
    val quotedArguments: List<String>
        get() = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(line)
            .map { it.groupValues[1].replace(Regex("\\\\(.)"), "$1") }
            .toList()

    override fun toString(): String = line
}

/**
 * A one-connection, scripted ManageSieve server on loopback, the sibling of [FakeSmtpServer]. The
 * client under test runs [SieveTransport.STARTTLS_WITHOUT_HANDSHAKE_FOR_TESTS]: `STARTTLS` is
 * offered, sent and answered, then both sides carry on in the clear, and the capabilities are
 * re-issued as RFC 5804 §2.2 has a server do after its handshake.
 *
 * It reads a literal exactly as announced, `n` octets, and requires what follows them to end the
 * command or start another argument. A length counted in characters leaves octets over, and the
 * command is answered `NO` and recorded in [literalMismatches].
 */
internal class FakeSieveServer(
    /** Capability lines sent in the greeting, before STARTTLS. */
    private val clearCapabilities: List<String> = CLEAR_CAPABILITIES,
    /** Capability lines re-issued after STARTTLS. */
    private val tlsCapabilities: List<String> = TLS_CAPABILITIES,
    scripts: Map<String, String> = emptyMap(),
    active: String? = null,
    /** A non-null result replaces the canned reply to that command, one entry per line, CRLF added. */
    private val respond: (SieveCommand) -> List<String>? = { null },
    /** Accept the connection and never send the greeting. */
    private val mute: Boolean = false,
) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    val host: String = server.inetAddress.hostAddress
    val port: Int = server.localPort

    val commands: MutableList<SieveCommand> = Collections.synchronizedList(mutableListOf<SieveCommand>())

    /** Stored scripts, as the octets received for them. */
    val scripts: MutableMap<String, ByteArray> =
        ConcurrentHashMap(scripts.mapValues { it.value.toByteArray(Charsets.UTF_8) })

    @Volatile
    var active: String? = active
        private set

    val literalMismatches: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var accepted: Socket? = null

    private val thread = Thread(::serve, "fake-sieve").apply { isDaemon = true; start() }

    private fun serve() {
        runCatching {
            server.accept().use { socket ->
                accepted = socket
                val input = BufferedInputStream(socket.inputStream)
                val output = socket.outputStream
                fun reply(lines: List<String>) {
                    lines.forEach { output.write("$it\r\n".toByteArray(Charsets.UTF_8)) }
                    output.flush()
                }
                fun replyBytes(bytes: ByteArray) {
                    output.write(bytes)
                    output.flush()
                }

                if (!mute) reply(clearCapabilities + "OK \"fake ManageSieve ready\"")
                while (true) {
                    val command = readCommand(input) ?: break
                    commands.add(command)
                    if (command.line.startsWith(MISMATCH)) {
                        reply(listOf("NO \"literal length does not match its octets\""))
                        continue
                    }
                    val scripted = respond(command)
                    if (scripted != null) {
                        reply(scripted)
                        continue
                    }
                    val names = command.quotedArguments
                    when (command.verb) {
                        "STARTTLS" -> reply(listOf("OK \"begin TLS negotiation now\"") + tlsCapabilities + "OK \"TLS negotiated\"")
                        "CAPABILITY" -> reply(tlsCapabilities + "OK")
                        "AUTHENTICATE" -> reply(listOf("OK \"logged in\""))
                        "LISTSCRIPTS" -> reply(
                            this.scripts.keys.sorted().map { if (it == this.active) "\"$it\" ACTIVE" else "\"$it\"" } + "OK",
                        )
                        "GETSCRIPT" -> {
                            val octets = this.scripts[names.first()]
                            if (octets == null) {
                                reply(listOf("NO (NONEXISTENT) \"no such script\""))
                            } else {
                                replyBytes("{${octets.size}}\r\n".toByteArray(Charsets.US_ASCII) + octets + "\r\nOK\r\n".toByteArray(Charsets.US_ASCII))
                            }
                        }
                        "CHECKSCRIPT" -> reply(listOf("OK"))
                        "PUTSCRIPT" -> {
                            this.scripts[names.first()] = command.literals.single()
                            reply(listOf("OK"))
                        }
                        "SETACTIVE" -> {
                            this.active = names.first().ifEmpty { null }
                            reply(listOf("OK"))
                        }
                        "LOGOUT" -> {
                            reply(listOf("OK \"bye\""))
                            break
                        }
                        else -> reply(listOf("NO \"unknown command\""))
                    }
                }
            }
        }
    }

    /** `null` at end of stream. */
    private fun readCommand(input: InputStream): SieveCommand? {
        val text = StringBuilder()
        val literals = mutableListOf<ByteArray>()
        var line = readLine(input) ?: return null
        while (true) {
            text.append(line)
            val header = LITERAL_HEADER.find(line) ?: break
            val octets = ByteArray(header.groupValues[1].toInt())
            var read = 0
            while (read < octets.size) {
                val n = input.read(octets, read, octets.size - read)
                if (n < 0) return null
                read += n
            }
            literals += octets
            line = readLine(input) ?: return null
            if (line.isNotEmpty() && !line.startsWith(" ")) {
                literalMismatches += text.toString() + " / left over: " + line
                return SieveCommand("$MISMATCH $text", literals)
            }
        }
        return SieveCommand(text.toString(), literals)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) {
                val raw = bytes.toByteArray()
                val end = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, end, Charsets.UTF_8)
            }
            bytes.write(b)
        }
    }

    fun verbs(): List<String> = commands.toList().map { it.verb }

    fun awaitDone(timeoutMs: Long = 10_000) {
        thread.join(timeoutMs)
        check(!thread.isAlive) { "the fake server was still serving after ${timeoutMs}ms; commands so far: ${commands.toList()}" }
    }

    /** Runs [block] in a session against this server, through the test transport. */
    fun <T> session(block: ManageSieveSession.() -> T): T = runBlocking {
        ManageSieveClient(SieveTransport.STARTTLS_WITHOUT_HANDSHAKE_FOR_TESTS)
            .session(host, port, USER, PASSWORD, block = block)
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { accepted?.close() }
        thread.interrupt()
    }

    companion object {
        const val USER = "tester@example.org"
        const val PASSWORD = "correct-horse-battery-staple-correct-horse-battery-staple-0123456789"

        /** The shape Stalwart shows before STARTTLS: OAuth mechanisms only, `PLAIN` withheld until the handshake. */
        val CLEAR_CAPABILITIES = listOf(
            "\"IMPLEMENTATION\" \"fake sieve\"",
            "\"SASL\" \"OAUTHBEARER XOAUTH2\"",
            "\"SIEVE\" \"fileinto vacation\"",
            "\"STARTTLS\"",
            "\"VERSION\" \"1.0\"",
        )

        val TLS_CAPABILITIES = listOf(
            "\"IMPLEMENTATION\" \"fake sieve\"",
            "\"SASL\" \"PLAIN\"",
            "\"SIEVE\" \"fileinto vacation\"",
            "\"VERSION\" \"1.0\"",
        )

        private const val MISMATCH = "<literal-mismatch>"
        private val LITERAL_HEADER = Regex("""\{(\d+)\+?}$""")
    }
}
