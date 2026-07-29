package app.sterna.core.imap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ImapException(message: String) : Exception(message)

/**
 * Turn on RFC 2818 endpoint identification so the TLS handshake verifies the peer
 * certificate's CN/SAN against [host]. A bare [SSLSocket] only validates the chain,
 * not the hostname, so without this an attacker with any CA-valid certificate can
 * MITM the connection and capture credentials. Must be called before the handshake.
 */
internal fun SSLSocket.verifyingHostname(): SSLSocket = apply {
    sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
}

/** Opens authenticated IMAP sessions. The session object carries the live connection. */
class ImapClient {
    suspend fun connect(config: MailServerConfig, connectTimeoutMs: Int = 0): ImapSession =
        withContext(Dispatchers.IO) { openSession(config, connectTimeoutMs) }

    /**
     * Blocking connect + login. Use when already on a dedicated IO thread (e.g. IDLE).
     *
     * [connectTimeoutMs] bounds the TCP connect AND the greeting/STARTTLS/login reads that
     * follow, after which the socket goes back to its blocking default so no later operation
     * inherits a timeout. `0` IS that default — a connect the OS gives up on after minutes —
     * and is what every caller that passes nothing has always had. Name resolution is not
     * covered by it (no socket option is), exactly as before.
     */
    fun openSession(config: MailServerConfig, connectTimeoutMs: Int = 0): ImapSession {
        val timeout = connectTimeoutMs.coerceAtLeast(0)
        val plain = Socket()
        plain.connect(InetSocketAddress(config.host, config.port), timeout)
        val socket = when (config.security) {
            MailSecurity.TLS ->
                (tlsFactory.createSocket(plain, config.host, config.port, true) as SSLSocket).verifyingHostname()
            else -> plain
        }
        val session = ImapSession(socket)
        session.withReadTimeout(timeout) {
            session.readGreeting()
            if (config.security == MailSecurity.STARTTLS) {
                session.command("STARTTLS")
                session.upgradeTls(config.host, config.port)
            }
            if (config.accessToken != null) {
                session.authenticateXoauth2(config.username, config.accessToken)
            } else {
                session.login(config.username, config.password)
            }
        }
        return session
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
        val tls = ((SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, host, port, true) as SSLSocket).verifyingHostname()
        tls.startHandshake()
        socket = tls
        input = ImapParser(BufferedInputStream(tls.inputStream))
        output = tls.outputStream
    }

    internal fun login(username: String, password: String) {
        command("LOGIN ${quote(username)} ${quote(password)}")
    }

    /**
     * Authenticate with SASL XOAUTH2 (OAuth bearer token) instead of a password.
     * On a bad token the server sends a "+" base64 error challenge and then waits for an
     * empty client response before issuing the tagged NO — we acknowledge it so the
     * failure surfaces as an auth error instead of hanging.
     */
    internal fun authenticateXoauth2(username: String, accessToken: String) {
        val tag = "a${++tagN}"
        output.write("$tag AUTHENTICATE XOAUTH2 ${xoauth2Payload(username, accessToken)}\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        while (true) {
            val resp = input.readResponse()
            if (resp.isEmpty()) {
                if (socket.isClosed) throw ImapException("Connection closed")
                continue
            }
            when (resp[0]) {
                tag -> {
                    val status = resp.getOrNull(1) as? String ?: "BAD"
                    if (status != "OK") throw ImapException("AUTHENTICATE … failed: ${resp.drop(1).joinToString(" ")}")
                    return
                }
                "+" -> {
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                // else: an untagged "*" status line — ignore and read on for the tag.
            }
        }
    }

    /** Strip credential-bearing arguments before a command appears in an error/log. */
    private fun redactCommand(line: String): String {
        val verb = line.substringBefore(' ').uppercase()
        return if (verb == "LOGIN" || verb == "AUTHENTICATE") "$verb …" else line
    }

    /**
     * Bound every socket read made inside [block] to [millis], then restore the socket's
     * previous setting — the shape [idle] already uses, so one operation can be given a
     * deadline without any other inheriting it. `0` means "block forever", the JVM default.
     *
     * A per-READ bound, not a total: a server dribbling one byte at a time stays under it
     * indefinitely. It is what a socket offers, and it is what turns "hangs until the OS gives
     * up, minutes later" into "gives up on a silent peer in [millis]".
     */
    fun <T> withReadTimeout(millis: Int, block: () -> T): T {
        val previous = socket.soTimeout
        socket.soTimeout = millis.coerceAtLeast(0)
        try {
            return block()
        } finally {
            // The field can have been swapped by a STARTTLS upgrade inside the block; restore
            // on whatever socket is current.
            runCatching { socket.soTimeout = previous }
        }
    }

    /**
     * Send a tagged command and collect untagged responses up to the tagged result.
     *
     * [maxTokensPerLine] caps what each response line RETAINS (see [ImapParser.readResponse]);
     * the default keeps everything. Only a caller that already knows it will discard the
     * surplus should lower it — and it must leave room for a tagged status line.
     */
    internal fun command(line: String, maxTokensPerLine: Int = Int.MAX_VALUE): ImapResult {
        val tag = "a${++tagN}"
        output.write("$tag $line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val untagged = mutableListOf<List<Any?>>()
        while (true) {
            val resp = input.readResponse(maxTokensPerLine)
            if (resp.isEmpty()) {
                if (socket.isClosed) throw ImapException("Connection closed")
                continue
            }
            when (resp[0]) {
                tag -> {
                    val status = resp.getOrNull(1) as? String ?: "BAD"
                    // Redact the echoed command: LOGIN/AUTHENTICATE carry the password,
                    // and this message surfaces to the UI and logs.
                    if (status != "OK") throw ImapException("${redactCommand(line)} failed: ${resp.drop(1).joinToString(" ")}")
                    return ImapResult(status, untagged, resp)
                }
                else -> untagged.add(resp) // "*" untagged or "+" continuation
            }
        }
    }

    /**
     * Run one IMAP IDLE cycle on the selected mailbox: wait up to [timeoutMs] for the
     * server to report a change. Returns true if new mail arrived (an untagged EXISTS or
     * RECENT), false on the keep-alive timeout. Always ends IDLE (DONE) and consumes the
     * tagged result before returning, so the caller can immediately IDLE again.
     */
    fun idle(timeoutMs: Int): Boolean {
        val tag = "a${++tagN}"
        output.write("$tag IDLE\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val previousTimeout = socket.soTimeout
        socket.soTimeout = timeoutMs
        var changed = false
        try {
            while (true) {
                val resp = try {
                    input.readResponse()
                } catch (_: java.net.SocketTimeoutException) {
                    break // keep-alive window elapsed — refresh IDLE
                }
                if (resp.isEmpty()) {
                    if (socket.isClosed) throw ImapException("Connection closed during IDLE")
                    continue
                }
                // Untagged "* <n> EXISTS" / "* <n> RECENT" announce new mail.
                val kind = resp.getOrNull(2)
                if (kind == "EXISTS" || kind == "RECENT") {
                    changed = true
                    break
                }
                // "+ idling" continuation and other status updates: keep waiting.
            }
        } finally {
            socket.soTimeout = previousTimeout
            // End IDLE and drain to its tagged completion so the stream is clean.
            runCatching {
                output.write("DONE\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
                while (true) {
                    val resp = input.readResponse()
                    if (resp.isEmpty()) {
                        if (socket.isClosed) break else continue
                    }
                    if (resp[0] == tag) break
                }
            }
        }
        return changed
    }

    /** Create a mailbox (folder). Succeeds quietly if it already exists. */
    fun createFolder(path: String) {
        runCatching { command("CREATE ${quote(path)}") }
    }

    /** Rename a mailbox from [oldPath] to [newPath]. */
    fun renameFolder(oldPath: String, newPath: String) {
        command("RENAME ${quote(oldPath)} ${quote(newPath)}")
    }

    /** Delete a mailbox. */
    fun deleteFolder(path: String) {
        command("DELETE ${quote(path)}")
    }

    /** Move a message to another mailbox; returns its new UID in the destination if reported. */
    fun move(uid: Long, destination: String): Long? = move(listOf(uid), destination)[uid]

    /**
     * Move many messages to [destination] with one `UID MOVE <set> <dest>` per chunk
     * (falling back to `UID COPY` + `+FLAGS (\Deleted)` + `UID EXPUNGE`), instead of one
     * command per message — the bulk-action fix (Codeberg #29). The caller must have
     * SELECTed the source mailbox. Returns the source-UID → destination-UID mapping parsed
     * from COPYUID (RFC 4315 / RFC 6851), empty when the server reports none — the move
     * still happened; only per-message Undo positioning is lost. Chunked to stay well under
     * command-length limits.
     */
    fun move(uids: List<Long>, destination: String): Map<Long, Long> {
        val mapping = LinkedHashMap<Long, Long>()
        for (chunk in uids.distinct().chunked(UID_SET_CHUNK)) {
            val set = compressUidSet(chunk)
            if (set.isEmpty()) continue
            val result = runCatching { command("UID MOVE $set ${quote(destination)}") }.getOrElse {
                val copy = command("UID COPY $set ${quote(destination)}")
                command("UID STORE $set +FLAGS (\\Deleted)")
                runCatching { command("UID EXPUNGE $set") }.onFailure { runCatching { command("EXPUNGE") } }
                copy
            }
            mapping.putAll(parseCopyUidMap(result))
        }
        return mapping
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

    /**
     * Parse the ordered source-UID → destination-UID mapping from a `COPYUID
     * <uidvalidity> <source-set> <dest-set>` response (RFC 4315). Both sets correspond
     * positionally, so they're expanded preserving order and zipped. Empty if there is no
     * COPYUID or the two sets don't line up.
     */
    private fun parseCopyUidMap(result: ImapResult): Map<Long, Long> {
        val text = (result.untagged + listOf(result.tagged)).joinToString(" ") { resp ->
            resp.joinToString(" ") { flatten(it) }
        }
        val m = Regex("COPYUID\\s+\\d+\\s+([\\d,:]+)\\s+([\\d,:]+)").find(text) ?: return emptyMap()
        return copyUidMapping(m.groupValues[1], m.groupValues[2])
    }

    private fun flatten(v: Any?): String = when (v) {
        is List<*> -> v.joinToString(" ") { flatten(it) }
        null -> "NIL"
        else -> v.toString()
    }

    /** LIST every mailbox, inferring a role from special-use attributes or the name. */
    fun listFolders(): List<ImapFolder> = parseListFolders(command("LIST \"\" \"*\"").untagged)

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

    /**
     * UIDs in the SELECTed mailbox matching a built [ImapSearchCommand].
     *
     * `CHARSET UTF-8` is declared only when a search value actually needs it; RFC 3501 lets a
     * server support US-ASCII alone and answer `NO [BADCHARSET]`, so that case retries once
     * without the declaration rather than turning an accented search into a failed one.
     */
    fun searchUids(search: ImapSearchCommand): List<Long> {
        val result = try {
            command("UID SEARCH ${search.arguments()}")
        } catch (e: ImapException) {
            if (!search.needsUtf8) throw e
            command("UID SEARCH ${search.arguments(declareUtf8 = false)}")
        }
        val line = result.untagged.firstOrNull { it.getOrNull(1) == "SEARCH" } ?: return emptyList()
        return line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() }
    }

    /**
     * At most [cap] UIDs from the SELECTed mailbox, via `UID SEARCH ALL`.
     *
     * The whole folder, not the synced window, and without fetching a single envelope: one
     * command, one line of numbers. `ALL` is the RFC 3501 search key every server implements,
     * so this is the cheap way to enumerate a folder whose messages were never scrolled to
     * (used to freeze an "Empty trash", Codeberg #99).
     *
     * [cap] is enforced DURING the parse, not after: without ESEARCH the server cannot be asked
     * for fewer, so a 200 000-message folder is read to the end (the stream must stay in sync)
     * while only [cap] ids are ever held. The ids kept are therefore the first the server listed
     * — ascending UID, i.e. the oldest — and the surplus survives, as it does on JMAP; emptying
     * again clears the next slice. Picking the newest instead would mean holding all 200 000 to
     * sort them, which is the cost this avoids.
     *
     * Unlike [searchUids] this reads EVERY untagged `SEARCH` line, not just the first: a server
     * is free to split a long result across several, and here a dropped tail would silently
     * shorten the list of what to destroy. No `CHARSET` question either — `ALL` is pure ASCII.
     */
    fun allUids(cap: Int): List<Long> {
        if (cap <= 0) return emptyList()
        // +2 leaves room for the "*" and "SEARCH" that open the line (and for a tagged status
        // line, which is far shorter than that).
        val keep = if (cap > Int.MAX_VALUE - 2) Int.MAX_VALUE else cap + 2
        return command("UID SEARCH ALL", maxTokensPerLine = keep).untagged
            .filter { it.getOrNull(1) == "SEARCH" }
            .flatMap { line -> line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() } }
            .take(cap)
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

    fun delete(uid: Long) = delete(listOf(uid))

    /**
     * Permanently delete many messages with one `UID STORE <set> +FLAGS (\Deleted)` +
     * `UID EXPUNGE <set>` per chunk (Codeberg #29). Falls back to a plain `EXPUNGE` when the
     * server lacks UIDPLUS, matching the single-message path. The caller must have SELECTed
     * the mailbox.
     */
    fun delete(uids: List<Long>) {
        for (chunk in uids.distinct().chunked(UID_SET_CHUNK)) {
            val set = compressUidSet(chunk)
            if (set.isEmpty()) continue
            command("UID STORE $set +FLAGS (\\Deleted)")
            runCatching { command("UID EXPUNGE $set") }.onFailure { command("EXPUNGE") }
        }
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
        // Envelope "to" (index 5): every parseable address — Sent-folder rows show the
        // recipients, not the sender (Codeberg #59). Group-syntax delimiters (no host) are
        // skipped.
        @Suppress("UNCHECKED_CAST")
        val toAddrs = ((envelope?.getOrNull(5) as? List<Any?>).orEmpty()).mapNotNull { entry ->
            val addr = entry as? List<Any?> ?: return@mapNotNull null
            val name = decodeWords(addr.getOrNull(0) as? String)
            val box = addr.getOrNull(2) as? String
            val host = addr.getOrNull(3) as? String
            val email = if (box != null && host != null) "$box@$host" else null
            if (email == null && name == null) null else ImapAddress(name = name, email = email)
        }
        val messageId = envelope?.getOrNull(9) as? String
        val inReplyTo = envelope?.getOrNull(8) as? String

        return ImapMessage(
            uid = uid,
            subject = subject,
            fromName = fromName,
            fromEmail = fromEmail,
            to = toAddrs,
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
            }.let { stripBidiAndControls(it) }.trim()
        }

        /**
         * Remove control characters and Unicode bidi overrides from a decoded header before it
         * is shown. A crafted display name can otherwise embed RTL/LTR overrides (e.g. to make
         * "moc.knab@troppus" read as a bank address) or control chars for spoofing/UI confusion.
         */
        private fun stripBidiAndControls(s: String): String = s.filterNot { c ->
            val code = c.code
            (code in 0x00..0x08) || (code in 0x0B..0x1F) || (code in 0x7F..0x9F) ||
                (code in 0x202A..0x202E) || (code in 0x2066..0x2069) || code == 0x200F || code == 0x200E
        }

        private fun decodeQ(data: String): ByteArray {
            val out = ArrayList<Byte>(data.length)
            var i = 0
            while (i < data.length) {
                when (val c = data[i]) {
                    '_' -> { out.add(' '.code.toByte()); i++ }
                    '=' -> {
                        val hex = data.substring(i + 1, (i + 3).coerceAtMost(data.length))
                        val byte = if (hex.length == 2) hex.toIntOrNull(16) else null
                        if (byte != null) { out.add(byte.toByte()); i += 3 }
                        else { out.add('='.code.toByte()); i++ } // dangling/invalid escape: keep literal '='
                    }
                    else -> { out.add(c.code.toByte()); i++ }
                }
            }
            return out.toByteArray()
        }
    }
}

/**
 * IMAP quoted-string. Per RFC 3501 a quoted-string may not contain CR or LF; a raw
 * newline here would terminate the command line and let an attacker-controlled value
 * (folder name, search text) inject a second authenticated IMAP command. Reject it.
 *
 * File-level (not a method) so the SEARCH-command builder in ImapSearch.kt quotes its
 * user-supplied values through the exact same guard.
 */
internal fun quote(s: String): String {
    if (s.any { it == '\r' || it == '\n' }) throw ImapException("Illegal newline in IMAP argument")
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Cap on how many UIDs go into one `UID MOVE`/`UID STORE` sequence-set, so an enormous
 * selection is split across a few commands instead of one over-long line. 200 UIDs
 * compress to well under any server's command-length limit.
 */
private const val UID_SET_CHUNK = 200

/** Untagged responses collected for one tagged command. */
internal class ImapResult(
    val status: String,
    val untagged: List<List<Any?>>,
    val tagged: List<Any?> = emptyList(),
)

/**
 * Map the untagged responses of a `LIST` command to [ImapFolder]s, dropping
 * non-selectable container mailboxes. A `\Noselect` (or `\NonExistent`) entry can't be
 * opened: Gmail nests its special mailboxes under such a container literally named
 * "[Gmail]", so surfacing it only adds a dead level in the drawer. Its selectable
 * children keep their full path and re-parent to top level (the drawer infers parents
 * from the path, finds the dropped container missing, and promotes them). INBOX is
 * never dropped, even if a server mislabels it.
 */
internal fun parseListFolders(untagged: List<List<Any?>>): List<ImapFolder> =
    untagged.mapNotNull { resp ->
        // * LIST (attrs) "delim" "name"
        if (resp.getOrNull(1) != "LIST") return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val attrs = (resp.getOrNull(2) as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()
        val delim = resp.getOrNull(3) as? String ?: "/"
        val path = resp.getOrNull(4) as? String ?: return@mapNotNull null
        val name = path.substringAfterLast(delim)
        val nonSelectable = attrs.any {
            it.equals("\\Noselect", ignoreCase = true) || it.equals("\\NonExistent", ignoreCase = true)
        }
        if (nonSelectable && !path.equals("INBOX", ignoreCase = true)) return@mapNotNull null
        ImapFolder(name = name, path = path, role = roleOf(name, attrs), delimiter = delim)
    }

/** Infer a normalised folder role from SPECIAL-USE attributes first, then the name. */
internal fun roleOf(name: String, attrs: List<String>): String? {
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
