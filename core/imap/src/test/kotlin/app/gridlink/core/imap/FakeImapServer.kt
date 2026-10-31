package app.gridlink.core.imap

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * A one-connection, scripted IMAP server on loopback, so the folder walk can be driven end to
 * end on the JVM: what SELECT/SEARCH/FETCH commands actually leave the client, in what order,
 * and how many of them. [responder] is handed the tag and the command line (tag stripped) and
 * returns the complete raw response, tagged line included.
 *
 * [responder] alone is strictly request/response, which cannot express the failure of #156: a
 * server that answers, goes quiet, and then GOES AWAY while the client is still waiting on it.
 * [cutAfter] is that server, and it is the same hook, in the same shape, as [FakeSmtpServer]'s.
 */
internal class FakeImapServer(
    /**
     * Write these lines and then **stop answering, for good** — the IMAP twin of
     * [FakeSmtpServer]'s hook of the same name, and the one thing [responder] cannot express: a
     * responder always answers the line it was given and goes back to waiting for the next one.
     *
     * Called with the line as [commands] records it (tag stripped), or [GREETING] for the server
     * banner, which no command of the client precedes. An empty list cuts before a single byte is
     * written.
     *
     * The cut is a half-close (`shutdownOutput`): the client gets its EOF, and whatever it writes
     * afterwards is still received and recorded in [commands], tag stripped exactly as the serve
     * loop records it — that is the witness of a client still talking to a server that has already
     * gone (the `DONE` of an IDLE, the `LOGOUT` of a close). ⛔ Tag stripped and not verbatim for a
     * reason: [issued] hides credentials by matching `LOGIN` at the START of the line, and a line
     * kept as `a1 LOGIN tester secret` would walk straight past that filter into a failure message.
     * Wait for these lines with [awaitDone].
     */
    private val cutAfter: (String) -> List<String>? = { null },
    /**
     * The silence between the last line [cutAfter] writes and the half-close: the server that
     * answers `+ idling`, says nothing for a while, and only then goes away. Without it the EOF
     * treads on the heels of the continuation and the client is never actually parked in a read,
     * which is the state the IDLE defect lives in.
     */
    private val cutDelayMs: Long = 0,
    /**
     * ⛔ LAST on purpose, and it must stay last: every existing call site writes
     * `FakeImapServer { tag, line -> … }`, and a trailing lambda binds to the LAST parameter.
     * Moving it up compiles nowhere.
     */
    private val responder: (tag: String, line: String) -> String,
) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    /** Every command line received, tag stripped, in order. */
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    val config = MailServerConfig(
        host = server.inetAddress.hostAddress,
        port = server.localPort,
        security = MailSecurity.NONE,
        username = "tester",
        password = "secret",
    )

    /**
     * The accepted connection, so [close] can end it. Interrupting the serving thread does NOT
     * unblock a socket read, and a cut session spends its last seconds in one.
     */
    @Volatile
    private var accepted: java.net.Socket? = null

    private val thread = Thread(::serve, "fake-imap").apply { isDaemon = true; start() }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun serve() {
        runCatching {
            server.accept().use { socket ->
                accepted = socket
                // Asymmetric on purpose. READING is UTF-8 because that is what the client writes
                // (a `UID SEARCH CHARSET UTF-8 SUBJECT "école"` really does go out as UTF-8, and
                // a test asserts on that line as text). WRITING is ISO-8859-1 so a scripted
                // response goes out BYTE FOR BYTE: one char of the response string, one octet on
                // the wire. Without it no test could put a raw 8-bit byte in front of the parser
                // — the writer would helpfully UTF-8-encode it and the case under test would
                // never reach the socket. Safe for every existing test: none of them puts a
                // non-ASCII character in a response (the Cyrillic ones travel in modified UTF-7).
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = OutputStreamWriter(socket.outputStream, Charsets.ISO_8859_1)

                /**
                 * Honour [cutAfter] for [trigger]: write the partial response, go quiet for
                 * [cutDelayMs], half-close, then keep recording whatever the client still writes
                 * until it goes quiet ([QUIET_MS]) or closes. True when the session was cut and
                 * the serve loop must end.
                 */
                fun cut(trigger: String): Boolean {
                    val partial = cutAfter(trigger) ?: return false
                    partial.forEach { writer.write("$it\r\n") }
                    writer.flush()
                    if (cutDelayMs > 0) Thread.sleep(cutDelayMs)
                    runCatching { socket.shutdownOutput() }
                    runCatching { socket.soTimeout = QUIET_MS }
                    while (true) {
                        val after = runCatching { reader.readLine() }.getOrNull() ?: break
                        commands.add(after.substringAfter(' ')) // tag stripped, as above: see [cutAfter]
                    }
                    return true
                }

                if (cut(GREETING)) return@use
                writer.write("* OK fake IMAP ready\r\n")
                writer.flush()
                while (true) {
                    val raw = reader.readLine() ?: break
                    val tag = raw.substringBefore(' ')
                    val line = raw.substringAfter(' ')
                    commands.add(line)
                    if (cut(line)) break
                    writer.write(responder(tag, line))
                    writer.flush()
                    if (line.startsWith("LOGOUT")) break
                }
            }
        }
    }

    /** Open a logged-in session against this server. */
    fun session(): ImapSession = ImapClient().openSession(config)

    /** Command lines received so far, excluding the LOGIN (whose arguments are credentials). */
    fun issued(): List<String> = commands.toList().filterNot { it.startsWith("LOGIN") }

    /**
     * Block until the served connection is finished. After a cut this is what makes an assertion
     * on [commands] race-free: the client keeps writing for a moment after the EOF, and a test
     * that read the list before the server had taken those lines in would call that silence a pass.
     *
     * A join that merely EXPIRES would hand the test a list still being mutated, silently: fail.
     */
    fun awaitDone(timeoutMs: Long = 10_000) {
        thread.join(timeoutMs)
        check(!thread.isAlive) {
            "the fake server was still serving after ${timeoutMs}ms — the assertions below would " +
                "have read a list still being written; commands so far: ${commands.toList()}"
        }
    }

    override fun close() {
        runCatching { server.close() }
        // The accepted socket too: closing the LISTENING socket leaves an established connection
        // untouched, and a cut session's serving thread is parked on a read of it.
        runCatching { accepted?.close() }
        thread.interrupt()
    }

    companion object {
        /** [cutAfter] trigger for the server banner, which no command of the client precedes. */
        const val GREETING = "<greeting>"

        /** How long a cut connection keeps listening for a client that has not noticed yet. */
        private const val QUIET_MS = 1_500
    }
}

/** A canned OK response with no untagged lines. */
internal fun ok(tag: String) = "$tag OK done\r\n"

/**
 * A canned SELECT response for a folder holding [exists] messages, announcing [uidValidity] as
 * its numbering — the value a renumbering test moves (Codeberg #99).
 */
internal fun selectResponse(tag: String, exists: Int = 10, uidValidity: Long = 1L): String =
    "* $exists EXISTS\r\n* OK [UIDVALIDITY $uidValidity] ok\r\n* OK [UIDNEXT ${exists + 1}] ok\r\n" +
        "$tag OK [READ-WRITE] selected\r\n"

/** A canned `UID SEARCH` result listing [uids]. */
internal fun searchResponse(tag: String, uids: List<Long>): String =
    "* SEARCH ${uids.joinToString(" ")}\r\n$tag OK search completed\r\n"

/**
 * A canned `UID FETCH` result: one envelope per requested uid, with an attachment part in the
 * BODYSTRUCTURE when [withAttachment] says so — that is exactly what the local attachment
 * filter reads. [flags] gives the FLAGS list of each uid, `\Seen` unless a test says otherwise.
 */
internal fun fetchResponse(
    tag: String,
    uids: List<Long>,
    flags: (Long) -> String = { "\\Seen" },
    withAttachment: (Long) -> Boolean,
): String {
    val body = uids.joinToString("") { uid ->
        val flagList = flags(uid)
        val structure = if (withAttachment(uid)) {
            """(("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 12 1)""" +
                """("application" "pdf" ("name" "f.pdf") NIL NIL "base64" 900 NIL """ +
                """("attachment" ("filename" "f.pdf")) NIL) "mixed")"""
        } else {
            """("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 12 1)"""
        }
        "* $uid FETCH (UID $uid FLAGS ($flagList) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:0${uid % 10} +0000\" \"Message $uid\" " +
            "((\"Alex Rivera\" NIL \"alex.rivera\" \"masto.top\")) NIL NIL " +
            "((\"Team\" NIL \"team\" \"masto.top\")) NIL NIL NIL \"<$uid@masto.top>\") " +
            "BODYSTRUCTURE $structure)\r\n"
    }
    return body + "$tag OK fetch completed\r\n"
}

/** The UIDs named in a `UID FETCH <set> (...)` command line. */
internal fun uidsOf(fetchCommand: String): List<Long> =
    fetchCommand.removePrefix("UID FETCH ").substringBefore(' ')
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
