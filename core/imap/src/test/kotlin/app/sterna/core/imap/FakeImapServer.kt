package app.sterna.core.imap

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
 */
internal class FakeImapServer(
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

    private val thread = Thread(::serve, "fake-imap").apply { isDaemon = true; start() }

    private fun serve() {
        runCatching {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
                writer.write("* OK fake IMAP ready\r\n")
                writer.flush()
                while (true) {
                    val raw = reader.readLine() ?: break
                    val tag = raw.substringBefore(' ')
                    val line = raw.substringAfter(' ')
                    commands.add(line)
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

    override fun close() {
        runCatching { server.close() }
        thread.interrupt()
    }
}

/** A canned OK response with no untagged lines. */
internal fun ok(tag: String) = "$tag OK done\r\n"

/** A canned SELECT response for a folder holding [exists] messages. */
internal fun selectResponse(tag: String, exists: Int = 10): String =
    "* $exists EXISTS\r\n* OK [UIDVALIDITY 1] ok\r\n* OK [UIDNEXT ${exists + 1}] ok\r\n$tag OK [READ-WRITE] selected\r\n"

/** A canned `UID SEARCH` result listing [uids]. */
internal fun searchResponse(tag: String, uids: List<Long>): String =
    "* SEARCH ${uids.joinToString(" ")}\r\n$tag OK search completed\r\n"

/**
 * A canned `UID FETCH` result: one envelope per requested uid, with an attachment part in the
 * BODYSTRUCTURE when [withAttachment] says so — that is exactly what the local attachment
 * filter reads.
 */
internal fun fetchResponse(tag: String, uids: List<Long>, withAttachment: (Long) -> Boolean): String {
    val body = uids.joinToString("") { uid ->
        val structure = if (withAttachment(uid)) {
            """(("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 12 1)""" +
                """("application" "pdf" ("name" "f.pdf") NIL NIL "base64" 900 NIL """ +
                """("attachment" ("filename" "f.pdf")) NIL) "mixed")"""
        } else {
            """("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 12 1)"""
        }
        "* $uid FETCH (UID $uid FLAGS (\\Seen) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
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
