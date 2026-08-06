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
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ImapException(message: String) : Exception(message)

/**
 * The selected mailbox has been renumbered: its UIDVALIDITY is no longer the one the caller's
 * UIDs were read under, so those UIDs mean nothing (RFC 3501 §2.3.1.1).
 *
 * Deliberately NOT an [ImapException]: it is not a protocol error and not a transport failure,
 * and the existing `catch (e: ImapException)` / reconnect-and-retry paths must not absorb it.
 * Retrying cannot help — the folder really was renumbered — and treating it as a failure to be
 * swallowed is how a stale UID gets acted on. See [ImapSession.select].
 */
class ImapUidValidityChanged(
    val mailbox: String,
    val expected: Long,
    val observed: Long,
) : Exception("UIDVALIDITY of $mailbox changed from $expected to $observed")

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

    /**
     * What the server says it supports, learned once and kept for the life of the session,
     * `null` until then. See [capabilities].
     */
    private var advertisedCapabilities: Set<String>? = null

    internal fun readGreeting() {
        // Untagged "* OK [CAPABILITY …] ready". Its capability list is deliberately NOT kept:
        // it is what the server offers to an anonymous, possibly not-yet-encrypted client, and
        // RFC 3501 §7.1 says it may differ from what the same server offers once STARTTLS and
        // authentication have happened — which is the only state this session ever acts in.
        // [capabilities] learns the post-authentication list instead.
        input.readResponse()
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
        rememberCapabilities(command("LOGIN ${quote(username)} ${quote(password)}").tagged)
    }

    /**
     * What this server supports, as capability names, upper-cased (RFC 3501 §6.1.1 — names are
     * case-insensitive). Post-authentication and cached: asked at most once per session, and
     * usually not asked at all, because most servers volunteer the list in the `[CAPABILITY …]`
     * response code of their LOGIN/AUTHENTICATE completion.
     *
     * Deliberately a set of names and not a boolean per feature: the first caller needs UIDPLUS
     * (whether `UID EXPUNGE` exists, see [delete]), and the next one will need something else
     * (`UTF8=ACCEPT`, Codeberg #101). A server answering the query with no list at all is taken
     * at its word — an empty set, i.e. every optional extension absent, which is the reading that
     * makes each caller take its conservative branch.
     */
    fun capabilities(): Set<String> =
        advertisedCapabilities ?: parseCapabilities(command("CAPABILITY").untagged)
            .also { advertisedCapabilities = it }

    /** Whether the server advertises [name], e.g. `UIDPLUS`. Case-insensitive. */
    fun hasCapability(name: String): Boolean = name.uppercase() in capabilities()

    /**
     * Take the `[CAPABILITY …]` response code of an authentication completion as the session's
     * capability list. Free — it saves the round trip [capabilities] would otherwise make — and
     * authoritative: unlike the greeting's, this list is the post-authentication one.
     */
    private fun rememberCapabilities(taggedLine: List<Any?>) {
        val advertised = capabilitiesInResponseCode(taggedLine) ?: return
        if (advertised.isNotEmpty()) advertisedCapabilities = advertised
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
                    rememberCapabilities(resp)
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
        val previous = armReadTimeout(millis)
        try {
            return block()
        } finally {
            disarmReadTimeout(previous)
        }
    }

    /**
     * [withReadTimeout] for a block that SUSPENDS — the same wrapper, and the two must stay the
     * same wrapper. It cannot simply call the other one: a plain function cannot take a suspending
     * lambda, and making the other one suspend would drag `connect` — which runs before any
     * coroutine exists — with it.
     *
     * ⛔ So what they share is not a comment but [armReadTimeout]/[disarmReadTimeout]: the thing
     * that would be silently missing from a copy is the SETTING of the timeout, and there is now
     * one of those, exercised by the budget tests through the other wrapper. `ImapBudgetSharedTest`
     * additionally reads both bodies and refuses to let them differ.
     *
     * It exists for [walkFolder], whose block writes each page to the database between requests.
     * The socket setting therefore stands across those suspensions, which is what it is for: the
     * bound belongs to the reads of this operation, and the reads of this operation are exactly
     * what happens in between.
     */
    suspend fun <T> withReadTimeoutSuspending(millis: Int, block: suspend () -> T): T {
        val previous = armReadTimeout(millis)
        try {
            return block()
        } finally {
            disarmReadTimeout(previous)
        }
    }

    /**
     * Bound this socket's reads to [millis] and answer with what the bound was — the load-bearing
     * half of both `withReadTimeout` wrappers, in ONE place.
     *
     * ⛔ It is one place on purpose. Written out in each wrapper, deleting the assignment from the
     * copy the tests do not reach left every budget test green while `ENUMERATE_BUDGET_MS` — the
     * bound that stops an "Empty trash" enumeration hanging on a mute server — stopped applying to
     * everything the pooled session does.
     */
    private fun armReadTimeout(millis: Int): Int {
        val previous = socket.soTimeout
        socket.soTimeout = millis.coerceAtLeast(0)
        return previous
    }

    /** Put back what [armReadTimeout] answered. The field can have been swapped by a STARTTLS
     *  upgrade inside the block, so this restores on whatever socket is current — and never throws
     *  on the way out of a block that is already failing. */
    private fun disarmReadTimeout(previous: Int) {
        runCatching { socket.soTimeout = previous }
    }

    /**
     * Send a tagged command and collect untagged responses up to the tagged result.
     *
     * [maxTokensKept] caps what the WHOLE response retains (see [ImapParser.readResponse]), not
     * what each line retains: a server free to split its answer over K lines would otherwise be
     * allowed K times the cap — precisely the case a cap exists for. Exactly: the cap, plus at
     * most [STATUS_LINE_TOKENS] per line once it is spent, because a status line must stay
     * readable. The default keeps everything, and only a caller that already knows it will
     * discard the surplus lowers it.
     */
    internal fun command(line: String, maxTokensKept: Int = Int.MAX_VALUE): ImapResult {
        val tag = "a${++tagN}"
        output.write("$tag $line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val untagged = mutableListOf<List<Any?>>()
        var budget = maxTokensKept
        while (true) {
            // A floor per line, whatever is left of the budget: the tagged status line must stay
            // recognisable (its tag, its OK/NO) even once the untagged data has spent the lot,
            // or this loop would no longer recognise its own answer and would read forever.
            val resp = input.readResponse(budget.coerceAtLeast(STATUS_LINE_TOKENS))
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
                else -> {
                    untagged.add(resp) // "*" untagged or "+" continuation
                    budget -= resp.size
                }
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

    /**
     * A mailbox name as it must appear in a command: Unicode in, modified UTF-7 out
     * ([encodeModifiedUtf7]), then quoted.
     *
     * EVERY command that names a mailbox goes through this — SELECT, CREATE, RENAME (both
     * arguments), DELETE, the destination of UID MOVE and of its UID COPY fallback, APPEND —
     * and it lives here rather than at the callers precisely so that adding a command cannot
     * quietly skip it. Above this class a mailbox path is Unicode and nothing else (Codeberg
     * #101): decoding LIST without encoding these seven would make a Cyrillic folder readable
     * and unopenable.
     *
     * Encode BEFORE quote, never after: the encoded form is pure ASCII, so [quote]'s refusal of
     * CR/LF — the guard against a folder name injecting a second authenticated command — still
     * holds, and a newline can no longer even reach it.
     */
    private fun mailboxArg(path: String): String = quote(encodeModifiedUtf7(path))

    /** Create a mailbox (folder). Succeeds quietly if it already exists. */
    fun createFolder(path: String) {
        runCatching { command("CREATE ${mailboxArg(path)}") }
    }

    /** Rename a mailbox from [oldPath] to [newPath]. */
    fun renameFolder(oldPath: String, newPath: String) {
        command("RENAME ${mailboxArg(oldPath)} ${mailboxArg(newPath)}")
    }

    /** Delete a mailbox. */
    fun deleteFolder(path: String) {
        command("DELETE ${mailboxArg(path)}")
    }

    /** Move a message to another mailbox; returns its new UID in the destination if reported. */
    fun move(uid: Long, destination: String): Long? = move(listOf(uid), destination)[uid]

    /**
     * Move many messages to [destination] with one `UID MOVE <set> <dest>` per chunk
     * (falling back to `UID COPY` + `+FLAGS (\Deleted)` + a purge), instead of one
     * command per message — the bulk-action fix (Codeberg #29). The caller must have
     * SELECTed the source mailbox. Returns the source-UID → destination-UID mapping parsed
     * from COPYUID (RFC 4315 / RFC 6851), empty when the server reports none — the move
     * still happened; only per-message Undo positioning is lost. Chunked to stay well under
     * command-length limits.
     *
     * The copy fallback leaves the originals to be erased, and how that is done depends on
     * UIDPLUS exactly as it does in [delete] — same question, same single answer: see
     * [purgeWithoutUidPlus]. The capability is consulted BEFORE the first COPY, so a session
     * that cannot even answer that question fails without having duplicated anything.
     */
    fun move(uids: List<Long>, destination: String): Map<Long, Long> {
        val mapping = LinkedHashMap<Long, Long>()
        // The no-UIDPLUS purge is folder-wide, so it is decided once for the whole move — over
        // the union of the chunks that actually took the copy fallback, and only those: a chunk
        // that went out as a UID MOVE flagged nothing.
        val flaggedByFallback = mutableSetOf<Long>()
        for (chunk in uids.distinct().chunked(UID_SET_CHUNK)) {
            val set = compressUidSet(chunk)
            if (set.isEmpty()) continue
            val result = runCatching { command("UID MOVE $set ${mailboxArg(destination)}") }.getOrElse {
                val byUid = hasCapability(CAP_UIDPLUS)
                val copy = command("UID COPY $set ${mailboxArg(destination)}")
                command("UID STORE $set +FLAGS (\\Deleted)")
                // Best effort, unlike in [delete]: the copy has already landed, so failing here
                // would have the caller retry a move that would copy a second time. Leaving the
                // original flagged is the cheaper error. (`command` is blocking, not suspending —
                // this catch cannot swallow a coroutine cancellation.)
                if (byUid) {
                    runCatching { command("UID EXPUNGE $set") }
                } else {
                    flaggedByFallback += chunk
                }
                copy
            }
            mapping.putAll(parseCopyUidMap(result))
        }
        finishPurgeWithoutUidPlus(flaggedByFallback)
        return mapping
    }

    /** Fetch a single message by UID (envelope + flags), or null if not found — or hidden. */
    fun fetchByUid(uid: Long): ImapMessage? {
        val result = command("UID FETCH $uid (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
        return result.messages().firstOrNull()
    }

    /**
     * The messages of a FETCH response — and the ONE place a message flagged `\Deleted` is
     * dropped, so that it cannot appear in a folder list, a search result, a notification or a
     * restored batch (Codeberg #99).
     *
     * Every fetch in this class funnels through here ([fetchPage], [fetchUids], [fetchByUid],
     * and the search scan by way of [fetchUids]), which is why the rule is stated once instead of
     * at each caller. `\Deleted` means the server has been told the message is to go and only an
     * EXPUNGE will finish the job; until then it is still returned by FETCH, by any client. A
     * purge that legitimately stops short of the EXPUNGE — no UIDPLUS and somebody else's message
     * flagged in the folder, see [delete] — would otherwise have its messages walk straight back
     * into the list on the next sync, which reads as "Empty trash did nothing".
     *
     * HIDING IS NOT DELETING, and nothing here makes those messages unreachable: the Empty-trash
     * snapshot enumerates with `UID SEARCH ALL` ([allUids]) and the purge asks `UID SEARCH
     * DELETED`, neither of which is a FETCH. The destroy path still sees, and still destroys,
     * exactly what it did before. Clear the flag from another client and the message comes back
     * on the next fetch, since nothing about it was cached.
     */
    private fun ImapResult.messages(): List<ImapMessage> =
        untagged.mapNotNull { parseFetch(it) }.filterNot { it.deleted }

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

    /** LIST every mailbox, inferring a role from special-use attributes or the name. */
    fun listFolders(): List<ImapFolder> = parseListFolders(command("LIST \"\" \"*\"").untagged)

    /**
     * SELECT a mailbox and read back its status.
     *
     * [expectedUidValidity] is the UIDVALIDITY this caller's UIDs were read under. When the
     * server now reports a different one, every UID in that folder has been reassigned (RFC 3501
     * §2.3.1.1) — a UID that used to mean one message now means another, or nothing — and the
     * call is refused with [ImapUidValidityChanged] BEFORE a single command that names a UID
     * goes out. That is the whole IMAP side of Codeberg #99: a held-back "Empty trash" that
     * finds a renumbered folder must destroy nothing, not destroy "whatever holds those numbers
     * now".
     *
     * `null`, the default, asks nothing and is what every discovery path passes: the first
     * SELECT of a folder has nothing to compare against. A server that reports no UIDVALIDITY at
     * all (0) cannot be checked either way, and is not turned into a refusal — that would break
     * the folder outright on a non-conforming server, which is the wrong direction for a guard
     * whose job is to be conservative about DESTROYING.
     *
     * THE ONE RETRY: a mailbox whose wire name is not a valid encoding of itself — a bare `&`,
     * which some client (including Sterna up to 1.4.3, which sent folder names raw) may have
     * created — is kept verbatim by [decodeMailboxPath], and encoding it again would turn `R&D`
     * into `R&-D` and address a mailbox that does not exist. Such a name cannot be told apart
     * from the legitimate decoding of `R&-D`: the two are the same string, and one encoder cannot
     * map it to both. So the standard form is tried first, and only when the server refuses it is
     * the verbatim form tried — at which point the alternative is a folder the user cannot open
     * at all. SELECT alone gets this: it is read-only and idempotent, and it is the command that
     * blocks. Retrying a CREATE, a MOVE or an APPEND under a second name could file mail into the
     * wrong place. Same shape as the `BADCHARSET` retry in [searchUids], and free when the first
     * form works, which it does for every conformant name.
     */
    fun select(path: String, expectedUidValidity: Long? = null): ImapMailboxStatus {
        val encoded = mailboxArg(path)
        val result = try {
            command("SELECT $encoded")
        } catch (refused: ImapException) {
            val verbatim = quote(path)
            // Only where the ambiguity can exist: the path is its own plausible wire form, i.e.
            // pure ASCII that the encoder would nonetheless have changed. A non-ASCII path has
            // exactly one wire form, so a refusal there means what it says and putting raw UTF-8
            // on the wire would only be noise.
            if (verbatim == encoded || path.any { it.code !in 0x20..0x7E }) throw refused
            command("SELECT $verbatim")
        }
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
        if (expectedUidValidity != null && expectedUidValidity > 0L &&
            uidValidity > 0L && uidValidity != expectedUidValidity
        ) {
            throw ImapUidValidityChanged(path, expectedUidValidity, uidValidity)
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
        return result.messages().sortedByDescending { it.uid }
    }

    /**
     * Read the newest [limit] messages of the SELECTed folder (which the caller's SELECT said holds
     * [exists] of them) in requests of [pageSize] sequence positions, handing each page to [onPage]
     * AS IT LANDS and keeping only UIDs.
     *
     * ⛔ Why pages at all: [fetchPage] puts the whole window in one `FETCH`, and everything that
     * command brings back is parsed and held before a single message is read out of it. At the
     * window sizes this app offers that is a folder's worth of envelopes on one heap. Paginating is
     * the only honest fix — capping what the parser keeps ([command]'s `maxTokensKept`) would
     * TRUNCATE the answer silently, and the messages it dropped would then be missing from the
     * walk's UIDs and deleted from the cache by the caller's reconcile.
     *
     * ⛔ What the caller owes back: [ImapFolderWalk.moved]. A folder that changed while this read it
     * must not be reconciled against — [folderMoved] states exactly which messages are at risk, and
     * it is NOT "one that fell between two pages": the sequence shift is downwards and this walk
     * descends, so what a renumbering costs is the BOTTOM of the window, plus anything another path
     * cached meanwhile.
     *
     * Movement is asked about in two places, and both are load-bearing:
     * - a `NOOP` before every request but the first, because that is the only way a server may tell
     *   us: RFC 3501 §7.4.1 forbids sending `EXPUNGE` while answering a `FETCH`, so it holds the
     *   news until a command that may carry it — and until it has sent it, it may not renumber
     *   anything either;
     * - the untagged lines of every `FETCH` response, which is not belt-and-braces: a walk of ONE
     *   page (a 50-message window, the unified refresh, a small folder) sends no `NOOP` at all, so
     *   this is then the only detector there is.
     *
     * The walk does NOT stop when it notices movement: the remaining pages are still mail the user
     * wants cached, writing them deletes nothing, and only the reconcile is unsafe. It carries the
     * verdict to the end instead.
     *
     * [onPage] receives only messages the walk has not already handed over, so an overlapping page
     * costs one wasted request and nothing else. ⚠ It runs inside the caller's session — that is
     * what "write each page as it lands" means — so the account's ONE connection is held for the
     * length of the walk INCLUDING the caller's writes, and every other IMAP operation on that
     * account queues behind it. The single `FETCH` this replaces held the connection too, but not
     * across a database write; on today's windows the difference is noise, on an unbounded one it
     * would not be.
     */
    suspend fun walkFolder(
        exists: Int,
        limit: Int,
        pageSize: Int,
        onPage: suspend (List<ImapMessage>) -> Unit,
    ): ImapFolderWalk {
        val lowest = folderWindowLowest(exists, limit)
        // De-duplicated BY UID: the walk's own pages can overlap after a renumbering, and a
        // sequence number is not a name. Insertion-ordered so the result stays newest-first.
        val seen = LinkedHashSet<Long>()
        var moved = false
        var previous: IntRange? = null
        while (true) {
            val page = nextFolderPage(lowest, exists, previous, pageSize) ?: break
            // Every request but the first: give the server its chance to say the folder moved,
            // BEFORE the numbering of the range below is used. `folderMoved(...) || moved` and not
            // the other way round, so the NOOP goes out whatever the verdict so far.
            if (previous != null) moved = folderMoved(command("NOOP").untagged, exists) || moved
            val result = command("FETCH ${page.first}:${page.last} (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
            // Not a second opinion: on a one-page walk no NOOP is ever sent, so this line is the
            // only thing that can notice the folder moving.
            moved = folderMoved(result.untagged, exists) || moved
            // Through messages(), like every other fetch here: it is the one place a `\Deleted`
            // message is dropped, and a walk that read the untagged lines itself would walk them
            // straight back into the list (Codeberg #99).
            val fresh = result.messages().sortedByDescending { it.uid }.filter { seen.add(it.uid) }
            if (fresh.isNotEmpty()) onPage(fresh)
            previous = page
        }
        return ImapFolderWalk(uids = seen.toList(), moved = moved)
    }

    /**
     * Number of unseen messages in the selected mailbox, `\Deleted` ones excluded.
     *
     * `UNDELETED` is not a detail: this count is what the IMAP account badge shows, while the
     * list it labels drops those same messages ([messages]). Counting them here would put an
     * unread badge over a folder with nothing in it to open.
     */
    fun unseenCount(): Int {
        val result = command("SEARCH UNSEEN UNDELETED")
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
     * while only [cap] ids are ever held. The cap spans the whole response, not each line — a
     * server splitting its answer must not be handed K times the cap — give or take the few
     * tokens each further line is always allowed so its status stays readable.
     *
     * The ids kept are therefore the ones the server listed FIRST, i.e. ascending UID, the
     * OLDEST. Note that the JMAP snapshot caps at the other end (its query sorts newest first),
     * so the two protocols keep opposite slices of an over-cap folder; only "the surplus
     * survives and emptying again clears the next slice" is common to both. Keeping the newest
     * here would mean building all 200 000 ids to compare them — a running top-K holds only
     * [cap] of them, but it must still parse every one, which is the work this avoids. Worth
     * revisiting the day a real Trash exceeds the cap.
     *
     * Unlike [searchUids] this reads EVERY untagged `SEARCH` line, not just the first: a server
     * is free to split a long result across several, and here a dropped tail would silently
     * shorten the list of what to destroy. No `CHARSET` question either — `ALL` is pure ASCII.
     */
    fun allUids(cap: Int): List<Long> = uidSearchCapped("ALL", cap)

    /**
     * At most [cap] UIDs matching one plain search [key] (`ALL`, `DELETED`, …), with the cap
     * enforced during the parse — the shape [allUids] documents at length, shared with the
     * "is anything else flagged?" question of [finishPurgeWithoutUidPlus]. Both read EVERY
     * untagged `SEARCH` line, not just the first: a dropped tail would shorten a list that is
     * about to decide what gets destroyed. Keys used here are pure ASCII, so no `CHARSET`.
     */
    private fun uidSearchCapped(key: String, cap: Int): List<Long> {
        if (cap <= 0) return emptyList()
        // +2 leaves room for the "*" and "SEARCH" that open the first line.
        val keep = if (cap > Int.MAX_VALUE - 2) Int.MAX_VALUE else cap + 2
        return command("UID SEARCH $key", maxTokensKept = keep).untagged
            .filter { it.getOrNull(1) == "SEARCH" }
            .flatMap { line -> line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() } }
            .take(cap)
    }

    /** Fetch several messages by UID (envelope + flags); `\Deleted` ones are not returned. */
    fun fetchUids(uids: List<Long>): List<ImapMessage> {
        if (uids.isEmpty()) return emptyList()
        val result = command("UID FETCH ${uids.joinToString(",")} (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
        return result.messages()
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
     * Permanently delete many messages with one `UID STORE <set> +FLAGS (\Deleted)` per chunk
     * (Codeberg #29), erased with `UID EXPUNGE <set>` — "erase exactly these" — when the server
     * implements UIDPLUS (RFC 4315). The caller must have SELECTed the mailbox.
     *
     * Whether it does is asked ONCE, before the first chunk is touched, and not chunk by chunk:
     * the answer cannot change mid-operation, and the alternative it selects is folder-wide, so
     * asking per chunk would mean applying a folder-wide command up to fifty times over an
     * "Empty trash" (Codeberg #99). What happens when the answer is no is [purgeWithoutUidPlus]
     * — one decision, taken in one place, applied once per operation.
     *
     * A refused `UID EXPUNGE` on a server that DOES advertise UIDPLUS throws, and the destroy
     * worker retries: unlike a move, a repeated destroy costs nothing.
     */
    fun delete(uids: List<Long>) {
        val flagged = uids.distinct()
        val sets = flagged.chunked(UID_SET_CHUNK).map { compressUidSet(it) }.filter { it.isNotEmpty() }
        if (sets.isEmpty()) return
        val byUid = hasCapability(CAP_UIDPLUS)
        for (set in sets) {
            command("UID STORE $set +FLAGS (\\Deleted)")
            if (byUid) command("UID EXPUNGE $set")
        }
        // The union of every chunk, not a chunk: the folder-wide question is asked about the
        // whole operation, once, after the last message has been flagged.
        if (!byUid) finishPurgeWithoutUidPlus(flagged.toSet())
    }

    /**
     * Close a purge on a server without UIDPLUS, according to [purgeWithoutUidPlus]. Called at
     * most once per operation, after every message meant to go has been flagged `\Deleted`;
     * [flagged] is the union of every chunk this operation flagged in the selected mailbox.
     */
    private fun finishPurgeWithoutUidPlus(flagged: Set<Long>) {
        if (flagged.isEmpty()) return
        when (purgeWithoutUidPlus) {
            PurgeWithoutUidPlus.LEAVE_FLAGGED -> Unit
            PurgeWithoutUidPlus.EXPUNGE_WHEN_ONLY_OURS -> if (nothingElseIsFlagged(flagged)) command("EXPUNGE")
            PurgeWithoutUidPlus.EXPUNGE_WHOLE_FOLDER -> command("EXPUNGE")
        }
    }

    /**
     * Ask the server what is flagged `\Deleted` in the selected mailbox and answer whether a
     * bare `EXPUNGE` would erase [flagged] and nothing besides.
     *
     * `UID SEARCH DELETED` is one command and one line of numbers, the same cheap enumeration
     * [allUids] uses, and it is asked ONCE per operation — never per chunk, which is the whole
     * point of taking the decision here rather than inside the loop.
     *
     * A STRICT SUBSET is a yes, not a no: every id the server reports is one of ours, so the
     * `EXPUNGE` cannot reach anything else. (It happens when a message has already gone, or when
     * another client cleared a flag.) An empty answer is a no — there is nothing to erase, so the
     * command would only be noise. Anything the server reports that we did not flag is a no.
     *
     * The list is capped at one more than [flagged]: enough to tell "the same or fewer" from
     * "more" — one extra id cannot fit inside a set of our size — and never enough for a folder
     * holding two hundred thousand flagged messages to be held in memory. A truncated answer is
     * therefore always a no, which is the safe direction.
     *
     * THE RACE, stated plainly because this is the design record: another client can flag a
     * message between this `SEARCH` and the `EXPUNGE` that follows it, and that message would be
     * destroyed. The window is the round trip between two adjacent commands — milliseconds —
     * against the unbounded exposure of expunging on no evidence at all, which is what this
     * replaces. It is narrowed, not closed, and IMAP without UIDPLUS offers no way to close it.
     */
    private fun nothingElseIsFlagged(flagged: Set<Long>): Boolean {
        val onServer = uidSearchCapped("DELETED", cap = flagged.size + 1)
        return onServer.isNotEmpty() && onServer.size <= flagged.size && flagged.containsAll(onServer)
    }

    /** APPEND a raw message into [mailbox] (e.g. a sent copy into Sent), with [flags]. */
    fun append(mailbox: String, message: String, flags: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val tag = "a${++tagN}"
        val flagPart = if (flags.isNotBlank()) "($flags) " else ""
        output.write("$tag APPEND ${mailboxArg(mailbox)} $flagPart{${bytes.size}}\r\n".toByteArray(Charsets.UTF_8))
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
        // The local part and the domain need [decodeHeaderBytes] just as much as the display
        // name beside them, and for a sharper reason: an address is not shown and forgotten, it
        // is PERSISTED (EmailEntity.fromEmail), indexed for search, offered as a contact
        // suggestion and prefilled as the recipient of a reply. Getting it wrong sends mail to an
        // address that does not exist.
        //
        // Not hypothetical. RFC 3501 forbids 8-bit bytes in a quoted-string, so a conforming
        // server hands over ANY non-ASCII envelope value as a literal — precisely the token whose
        // reading changed. An EAI address (RFC 6531, which Stalwart speaks) arrives that way.
        val mailbox = (fromAddr?.getOrNull(2) as? String)?.let(::decodeHeaderBytes)
        val hostPart = (fromAddr?.getOrNull(3) as? String)?.let(::decodeHeaderBytes)
        val fromEmail = if (mailbox != null && hostPart != null) "$mailbox@$hostPart" else null
        // Envelope "to" (index 5): every parseable address — Sent-folder rows show the
        // recipients, not the sender (Codeberg #59). Group-syntax delimiters (no host) are
        // skipped.
        @Suppress("UNCHECKED_CAST")
        val toAddrs = ((envelope?.getOrNull(5) as? List<Any?>).orEmpty()).mapNotNull { entry ->
            val addr = entry as? List<Any?> ?: return@mapNotNull null
            val name = decodeWords(addr.getOrNull(0) as? String)
            // Same treatment as the From address above, and for the same reason.
            val box = (addr.getOrNull(2) as? String)?.let(::decodeHeaderBytes)
            val host = (addr.getOrNull(3) as? String)?.let(::decodeHeaderBytes)
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
            deleted = flags.any { it.equals("\\Deleted", true) },
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

/** Render one parsed response token back to text, so a response code can be matched in it. */
internal fun flatten(v: Any?): String = when (v) {
    is List<*> -> v.joinToString(" ") { flatten(it) }
    null -> "NIL"
    else -> v.toString()
}

/** The UIDPLUS extension (RFC 4315): the one that provides `UID EXPUNGE <set>`. */
internal const val CAP_UIDPLUS = "UIDPLUS"

/**
 * Capability names from the untagged `* CAPABILITY …` line of a `CAPABILITY` command, upper-cased.
 * Empty when the server answered without one — read as "no optional extension", the conservative
 * reading, rather than as an unknown to be asked about again.
 */
internal fun parseCapabilities(untagged: List<List<Any?>>): Set<String> =
    untagged.firstOrNull { (it.getOrNull(1) as? String)?.equals("CAPABILITY", ignoreCase = true) == true }
        ?.drop(2)
        ?.mapNotNull { (it as? String)?.uppercase() }
        ?.toSet()
        .orEmpty()

/**
 * Capability names from a `[CAPABILITY …]` response code on [line], or `null` when it carries
 * none — which is not the same as carrying an empty one, hence the nullable return: a login
 * completion without the code leaves the question open for a later `CAPABILITY` command.
 *
 * Matched over the flattened line rather than token by token: `[` and `]` are not IMAP token
 * delimiters, so the parser hands back atoms like `[CAPABILITY` and `UIDPLUS]`. This is the same
 * shape [ImapSession.select] uses to read `[UIDVALIDITY n]`.
 */
internal fun capabilitiesInResponseCode(line: List<Any?>): Set<String>? {
    val flat = line.joinToString(" ") { flatten(it) }
    val code = Regex("\\[CAPABILITY([^\\]]*)\\]", RegexOption.IGNORE_CASE).find(flat) ?: return null
    return code.groupValues[1].split(' ').filter { it.isNotBlank() }.map { it.uppercase() }.toSet()
}

/**
 * What a permanent purge does on a server that does NOT implement UIDPLUS (RFC 4315), i.e. one
 * where `UID EXPUNGE <set>` — "erase exactly these" — does not exist.
 *
 * The only other way to erase is a bare `EXPUNGE`, which erases EVERY message flagged `\Deleted`
 * in the selected folder: the ones this app just flagged, but equally ones flagged from another
 * client, or flagged long ago and left there. Sent blind it destroys mail nobody designated; not
 * sent at all it leaves the folder un-emptied while the screen says otherwise.
 *
 * What breaks the deadlock is that the server can be ASKED what is flagged, and the answer is
 * one cheap command. So the honest option is not a compromise between the two but a condition on
 * the second: expunge when, and only when, the folder's flagged set is provably ours.
 *
 * This is a product decision, not a technical one, which is why it is a single named value
 * rather than a condition spread over the purge paths.
 */
internal enum class PurgeWithoutUidPlus {
    /**
     * Flag `\Deleted` and stop, always. Nothing is ever erased that the user did not designate;
     * in exchange the folder is never actually emptied on such a server.
     */
    LEAVE_FLAGGED,

    /**
     * Ask the server what is flagged, and send one bare `EXPUNGE` only if everything flagged is
     * something this operation flagged (see [ImapSession.nothingElseIsFlagged], which also states
     * the residual race). Otherwise behave as [LEAVE_FLAGGED]. Costs one extra command per
     * operation on servers without UIDPLUS, and nothing at all on servers with it.
     */
    EXPUNGE_WHEN_ONLY_OURS,

    /**
     * One bare `EXPUNGE` for the whole operation, asking nothing. The folder really is emptied —
     * including any message flagged `\Deleted` by somebody else, destroyed with no way back.
     */
    EXPUNGE_WHOLE_FOLDER,
}

/**
 * THE policy line. Changing this single assignment switches every purge path in this file
 * ([ImapSession.delete] and the copy fallback of [ImapSession.move]) and nothing else; both
 * extremes stay one word away, so neither has to be reconstructed to be tried.
 *
 * It ships as [PurgeWithoutUidPlus.EXPUNGE_WHEN_ONLY_OURS]: the Trash is genuinely emptied
 * whenever that can be shown to harm nothing, and destroying less than ordered remains the error
 * it falls back to (Codeberg #99).
 */
internal val purgeWithoutUidPlus = PurgeWithoutUidPlus.EXPUNGE_WHEN_ONLY_OURS

/**
 * Cap on how many UIDs go into one `UID MOVE`/`UID STORE` sequence-set, so an enormous
 * selection is split across a few commands instead of one over-long line. 200 UIDs
 * compress to well under any server's command-length limit.
 */
private const val UID_SET_CHUNK = 200

/** Tokens always readable on a line, however spent a caller's token budget is: enough for a
 *  tagged status line (`a12 NO [BADCHARSET] …`) to stay recognisable. */
private const val STATUS_LINE_TOKENS = 8

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
 *
 * THE ONE PLACE a mailbox name is decoded ([decodeMailboxPath], Codeberg #101): everything
 * above this function holds Unicode, and [ImapSession] encodes again on its way out.
 *
 * [ImapFolder.path] is the FAITHFUL decoding — nothing filtered, and nothing decoded that would
 * not encode back byte for byte — because it is an identifier: it has to reproduce the exact
 * bytes the server sent or the folder stops opening. The anti-spoofing filter therefore applies
 * to [ImapFolder.name], the display leaf, and only there. A path is never displayed and a name
 * is never sent.
 */
internal fun parseListFolders(untagged: List<List<Any?>>): List<ImapFolder> =
    untagged.mapNotNull { resp ->
        // * LIST (attrs) "delim" "name"
        if (resp.getOrNull(1) != "LIST") return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val attrs = (resp.getOrNull(2) as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()
        val delim = resp.getOrNull(3) as? String ?: "/"
        val wire = resp.getOrNull(4) as? String ?: return@mapNotNull null
        val path = decodeMailboxPath(wire)
        val leaf = path.substringAfterLast(delim)
        // A conforming name is modified UTF-7, i.e. pure ASCII on the wire, and its decoding is
        // already text — nothing to reinterpret. A name that arrived with 8-bit bytes is not
        // conforming: some client sent raw UTF-8 (Sterna itself did, up to 1.4.3 — see the
        // retry in [select]), [decodeMailboxPath] rightly kept it verbatim, and what the parser
        // hands over is therefore octets. Reading them before displaying them is the difference
        // between "Помеченные" and a truncated mojibake, since [stripBidiAndControls] eats
        // U+0080–U+009F. Testing the WIRE form, not the decoded one, is what keeps this free of
        // false positives: a legitimately decoded name can never trigger it.
        //
        // [path] is deliberately NOT touched. It is the identifier, it has to reproduce the
        // server's exact bytes, and it is the only reason the folder stays addressable.
        val name = stripBidiAndControls(if (wire.any { it.code >= 0x80 }) decodeHeaderBytes(leaf) else leaf)
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
