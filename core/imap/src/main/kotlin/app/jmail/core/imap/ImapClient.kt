package app.jmail.core.imap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.Socket
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ImapException(message: String) : Exception(message)

/** Opens authenticated IMAP sessions. The session object carries the live connection. */
class ImapClient {
    suspend fun connect(config: MailServerConfig): ImapSession = withContext(Dispatchers.IO) {
        val plain = Socket(config.host, config.port)
        val socket = when (config.security) {
            MailSecurity.TLS -> tlsFactory.createSocket(plain, config.host, config.port, true) as SSLSocket
            else -> plain
        }
        val session = ImapSession(socket)
        session.readGreeting()
        if (config.security == MailSecurity.STARTTLS) {
            session.command("STARTTLS")
            session.upgradeTls(config.host, config.port)
        }
        session.login(config.username, config.password)
        session
    }

    private companion object {
        val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    }
}

/**
 * A connected, logged-in IMAP session. Stateful (SELECT then FETCH), so callers
 * keep it for a unit of work and [close] it after. Not thread-safe.
 */
class ImapSession(private var socket: Socket) : Closeable {
    private var input = ImapParser(BufferedInputStream(socket.inputStream))
    private var output: OutputStream = socket.outputStream
    private var tagN = 0

    internal fun readGreeting() {
        input.readResponse() // untagged "* OK ..."
    }

    internal fun upgradeTls(host: String, port: Int) {
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, host, port, true) as SSLSocket
        tls.startHandshake()
        socket = tls
        input = ImapParser(BufferedInputStream(tls.inputStream))
        output = tls.outputStream
    }

    internal fun login(username: String, password: String) {
        command("LOGIN ${quote(username)} ${quote(password)}")
    }

    /** Send a tagged command and collect untagged responses up to the tagged result. */
    internal fun command(line: String): ImapResult {
        val tag = "a${++tagN}"
        output.write("$tag $line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val untagged = mutableListOf<List<Any?>>()
        while (true) {
            val resp = input.readResponse()
            if (resp.isEmpty()) {
                if (socket.isClosed) throw ImapException("Connection closed")
                continue
            }
            when (resp[0]) {
                tag -> {
                    val status = resp.getOrNull(1) as? String ?: "BAD"
                    if (status != "OK") throw ImapException("$line failed: ${resp.drop(1).joinToString(" ")}")
                    return ImapResult(status, untagged, resp)
                }
                else -> untagged.add(resp) // "*" untagged or "+" continuation
            }
        }
    }

    /** Create a mailbox (folder). Succeeds quietly if it already exists. */
    fun createFolder(path: String) {
        runCatching { command("CREATE ${quote(path)}") }
    }

    /** Move a message to another mailbox; returns its new UID in the destination if reported. */
    fun move(uid: Long, destination: String): Long? {
        val result = runCatching { command("UID MOVE $uid ${quote(destination)}") }.getOrElse {
            command("UID COPY $uid ${quote(destination)}").also {
                command("UID STORE $uid +FLAGS (\\Deleted)")
                runCatching { command("UID EXPUNGE $uid") }
            }
        }
        return parseCopyUid(result)
    }

    /** Fetch a single message by UID (envelope + flags), or null if not found. */
    fun fetchByUid(uid: Long): ImapMessage? {
        val result = command("UID FETCH $uid (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
        return result.untagged.firstNotNullOfOrNull { parseFetch(it) }
    }

    private fun parseCopyUid(result: ImapResult): Long? {
        val text = (result.untagged + listOf(result.tagged)).joinToString(" ") { resp ->
            resp.joinToString(" ") { flatten(it) }
        }
        // [COPYUID <uidvalidity> <sourceUid> <destUid>]
        val m = Regex("COPYUID\\s+\\d+\\s+[\\d,:]+\\s+(\\d+)").find(text) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    private fun flatten(v: Any?): String = when (v) {
        is List<*> -> v.joinToString(" ") { flatten(it) }
        null -> "NIL"
        else -> v.toString()
    }

    /** LIST every mailbox, inferring a role from special-use attributes or the name. */
    fun listFolders(): List<ImapFolder> {
        val result = command("LIST \"\" \"*\"")
        return result.untagged.mapNotNull { resp ->
            // * LIST (attrs) "delim" "name"
            if (resp.getOrNull(1) != "LIST") return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val attrs = (resp.getOrNull(2) as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()
            val delim = resp.getOrNull(3) as? String ?: "/"
            val path = resp.getOrNull(4) as? String ?: return@mapNotNull null
            val name = path.substringAfterLast(delim)
            ImapFolder(name = name, path = path, role = roleOf(name, attrs), delimiter = delim)
        }
    }

    fun select(path: String): ImapMailboxStatus {
        val result = command("SELECT ${quote(path)}")
        var exists = 0
        var uidValidity = 0L
        var uidNext = 0L
        for (resp in result.untagged) {
            // * <n> EXISTS  |  * OK [UIDVALIDITY n] ...  |  * OK [UIDNEXT n] ...
            if (resp.getOrNull(2) == "EXISTS") exists = (resp.getOrNull(1) as? String)?.toIntOrNull() ?: exists
            val flat = resp.joinToString(" ") { it?.toString() ?: "NIL" }
            Regex("UIDVALIDITY (\\d+)").find(flat)?.let { uidValidity = it.groupValues[1].toLong() }
            Regex("UIDNEXT (\\d+)").find(flat)?.let { uidNext = it.groupValues[1].toLong() }
        }
        return ImapMailboxStatus(exists, uidValidity, uidNext)
    }

    /**
     * Fetch a page of messages by sequence number, newest first. [offset] skips the
     * newest N messages; up to [limit] are returned. Uses the [exists] count from a
     * prior SELECT to map "newest" to high sequence numbers.
     */
    fun fetchPage(exists: Int, offset: Int, limit: Int): List<ImapMessage> {
        val highest = exists - offset
        if (highest < 1) return emptyList()
        val lowest = (highest - limit + 1).coerceAtLeast(1)
        val result = command("FETCH $lowest:$highest (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
        return result.untagged.mapNotNull { parseFetch(it) }.sortedByDescending { it.uid }
    }

    /** Number of unseen messages in the selected mailbox. */
    fun unseenCount(): Int {
        val result = command("SEARCH UNSEEN")
        val line = result.untagged.firstOrNull { it.getOrNull(1) == "SEARCH" } ?: return 0
        return line.drop(2).count { it is String }
    }

    /** UIDs of messages in the selected mailbox matching a free-text query. */
    fun searchText(query: String): List<Long> {
        val result = command("UID SEARCH TEXT ${quote(query)}")
        val line = result.untagged.firstOrNull { it.getOrNull(1) == "SEARCH" } ?: return emptyList()
        return line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() }
    }

    /** Fetch several messages by UID (envelope + flags). */
    fun fetchUids(uids: List<Long>): List<ImapMessage> {
        if (uids.isEmpty()) return emptyList()
        val result = command("UID FETCH ${uids.joinToString(",")} (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
        return result.untagged.mapNotNull { parseFetch(it) }
    }

    /** Raw content of one MIME section (e.g. an attachment), still transfer-encoded. */
    fun fetchSection(uid: Long, section: String): String {
        val result = command("UID FETCH $uid (BODY.PEEK[$section])")
        val fetch = result.untagged.firstOrNull { it.getOrNull(2) == "FETCH" } ?: return ""
        @Suppress("UNCHECKED_CAST")
        val items = fetch.getOrNull(3) as? List<Any?> ?: return ""
        val idx = items.indexOfFirst { it is String && it.startsWith("BODY", true) }
        return (items.getOrNull(idx + 1) as? String).orEmpty()
    }

    /** Raw RFC822 source of a message, for parsing the body/attachments. */
    fun fetchSource(uid: Long): String {
        val result = command("UID FETCH $uid (BODY.PEEK[])")
        val fetch = result.untagged.firstOrNull { it.getOrNull(2) == "FETCH" } ?: return ""
        @Suppress("UNCHECKED_CAST")
        val items = fetch.getOrNull(3) as? List<Any?> ?: return ""
        val idx = items.indexOfFirst { it is String && it.startsWith("BODY", true) }
        return (items.getOrNull(idx + 1) as? String).orEmpty()
    }

    fun setFlag(uid: Long, flag: String, set: Boolean) {
        val op = if (set) "+FLAGS" else "-FLAGS"
        command("UID STORE $uid $op ($flag)")
    }

    fun delete(uid: Long) {
        command("UID STORE $uid +FLAGS (\\Deleted)")
        runCatching { command("UID EXPUNGE $uid") }.onFailure { command("EXPUNGE") }
    }

    /** APPEND a raw message into [mailbox] (e.g. a sent copy into Sent), with [flags]. */
    fun append(mailbox: String, message: String, flags: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val tag = "a${++tagN}"
        val flagPart = if (flags.isNotBlank()) "($flags) " else ""
        output.write("$tag APPEND ${quote(mailbox)} $flagPart{${bytes.size}}\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val cont = input.readResponse()
        if (cont.getOrNull(0) != "+") throw ImapException("APPEND not accepted: $cont")
        output.write(bytes)
        output.write("\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        while (true) {
            val resp = input.readResponse()
            if (resp.getOrNull(0) == tag) {
                val status = resp.getOrNull(1) as? String ?: "BAD"
                if (status != "OK") throw ImapException("APPEND failed: ${resp.drop(1).joinToString(" ")}")
                return
            }
        }
    }

    override fun close() {
        runCatching { command("LOGOUT") }
        runCatching { socket.close() }
    }

    // ---- FETCH parsing ----

    private fun parseFetch(resp: List<Any?>): ImapMessage? {
        if (resp.getOrNull(2) != "FETCH") return null
        @Suppress("UNCHECKED_CAST")
        val items = resp.getOrNull(3) as? List<Any?> ?: return null
        val map = pairUp(items)

        val uid = (map["UID"] as? String)?.toLongOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        val flags = (map["FLAGS"] as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val envelope = map["ENVELOPE"] as? List<Any?>

        val subject = decodeWords(envelope?.getOrNull(1) as? String)
        val dateMillis = parseDate(envelope?.getOrNull(0) as? String)
        @Suppress("UNCHECKED_CAST")
        val fromAddr = (envelope?.getOrNull(2) as? List<Any?>)?.firstOrNull() as? List<Any?>
        val fromName = decodeWords(fromAddr?.getOrNull(0) as? String)
        val mailbox = fromAddr?.getOrNull(2) as? String
        val hostPart = fromAddr?.getOrNull(3) as? String
        val fromEmail = if (mailbox != null && hostPart != null) "$mailbox@$hostPart" else null
        val messageId = envelope?.getOrNull(9) as? String
        val inReplyTo = envelope?.getOrNull(8) as? String

        return ImapMessage(
            uid = uid,
            subject = subject,
            fromName = fromName,
            fromEmail = fromEmail,
            dateMillis = dateMillis,
            seen = flags.any { it.equals("\\Seen", true) },
            flagged = flags.any { it.equals("\\Flagged", true) },
            answered = flags.any { it.equals("\\Answered", true) },
            hasAttachment = hasAttachment(map["BODYSTRUCTURE"]),
            messageId = messageId,
            inReplyTo = inReplyTo,
        )
    }

    /** Turn a flat FETCH item list [k1, v1, k2, v2, ...] into a name→value map. */
    private fun pairUp(items: List<Any?>): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        var i = 0
        while (i < items.size) {
            val key = (items[i] as? String)?.uppercase()
            if (key == null) {
                i++
                continue
            }
            map[key] = items.getOrNull(i + 1)
            i += 2
        }
        return map
    }

    private fun hasAttachment(bodystructure: Any?): Boolean {
        fun walk(node: Any?): Boolean = when (node) {
            is List<*> -> node.any { child ->
                (child is String && child.equals("attachment", true)) || walk(child)
            }
            else -> false
        }
        return walk(bodystructure)
    }

    private fun roleOf(name: String, attrs: List<String>): String? {
        attrs.firstNotNullOfOrNull { attr ->
            when (attr.lowercase()) {
                "\\sent" -> "sent"
                "\\drafts" -> "drafts"
                "\\trash" -> "trash"
                "\\junk" -> "junk"
                "\\archive" -> "archive"
                "\\all" -> "all"
                else -> null
            }
        }?.let { return it }
        return when (name.lowercase()) {
            "inbox" -> "inbox"
            "sent", "sent mail", "sent items" -> "sent"
            "drafts" -> "drafts"
            "trash", "deleted", "deleted items" -> "trash"
            "junk", "spam" -> "junk"
            "archive", "archives" -> "archive"
            else -> null
        }
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        fun parseDate(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            val cleaned = raw.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
            return runCatching { ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(cleaned).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }

        /** Decode RFC 2047 encoded-words ("=?utf-8?B?..?=" / "=?..?Q?..?=") in a header. */
        fun decodeWords(text: String?): String? {
            if (text == null) return null
            val pattern = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")
            return pattern.replace(text) { m ->
                val charset = runCatching { charset(m.groupValues[1].substringBefore('*')) }.getOrDefault(Charsets.UTF_8)
                val enc = m.groupValues[2].uppercase()
                val data = m.groupValues[3]
                runCatching {
                    val bytes = if (enc == "B") {
                        Base64.getMimeDecoder().decode(data)
                    } else {
                        decodeQ(data)
                    }
                    String(bytes, charset)
                }.getOrDefault(m.value)
            }.trim()
        }

        private fun decodeQ(data: String): ByteArray {
            val out = ArrayList<Byte>(data.length)
            var i = 0
            while (i < data.length) {
                when (val c = data[i]) {
                    '_' -> { out.add(' '.code.toByte()); i++ }
                    '=' -> {
                        val hex = data.substring(i + 1, (i + 3).coerceAtMost(data.length))
                        out.add(hex.toInt(16).toByte()); i += 3
                    }
                    else -> { out.add(c.code.toByte()); i++ }
                }
            }
            return out.toByteArray()
        }
    }
}

/** Untagged responses collected for one tagged command. */
internal class ImapResult(
    val status: String,
    val untagged: List<List<Any?>>,
    val tagged: List<Any?> = emptyList(),
)
