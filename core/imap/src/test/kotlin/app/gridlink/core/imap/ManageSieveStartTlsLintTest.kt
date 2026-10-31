@file:Suppress("MaxLineLength", "PropertyWrapping")

package app.gridlink.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint on `ManageSieveClient.converse`. No JVM test reaches its TLS branch, having no
 * certificate, so what happens around the handshake is proved by the text: the hostname check
 * before `startHandshake`, `AUTHENTICATE` after it, and the capabilities that decide `PLAIN` read
 * after it too. The rest of the dialogue runs for real in [ManageSieveClientTest], through the test
 * transport, which sends and answers `STARTTLS` and skips only the handshake.
 *
 * The opening of the connection, from the socket to the capabilities re-read after the handshake, is
 * pinned as an exact list of consecutive lines: nothing can be inserted into it, a line that loosens
 * the socket's `sslParameters` between the hostname check and the handshake included. Other rules
 * find their candidate lines by a fragment, then require those lines, whole, to be exactly the
 * pinned ones, so a lengthened, duplicated or commented-out line fails instead of matching.
 *
 * What it does not cover:
 * - Textual order is not execution order. A pinned line moved into a `catch`, a lambda or a branch
 *   keeps its position in the file; the rules read positions.
 * - It proves the TLS branch is written, never that it works: a handshake against a real certificate
 *   is a device test.
 * - `verifyingHostname` is `ImapClient`'s, and its body is not read here. Neither are the values of
 *   the two delay constants, only where they go.
 * - Lines starting with `//` are dropped before the exact list is compared, so a comment may change
 *   freely; a commented-out statement still fails, being missing.
 * - The `AUTHENTICATE` rule looks for the verb in one string literal of `ManageSieveClient.kt`; the
 *   verb assembled from pieces escapes it. The `authenticatePlain` rule reads every shipped file of
 *   `core:imap` (the wire class is `internal`, no other module can reach it) and looks for the name
 *   followed by `(`: a callable reference `SieveWire::authenticatePlain` escapes it.
 * - Whether the public constructor picks the handshake is not read here: `ManageSieveClientTest`
 *   executes it.
 */
class ManageSieveStartTlsLintTest {

    private val body = ImapSource.functionBody("ManageSieveClient", "converse")

    private fun dump() = body.joinToString("\n")

    private fun onlyIndexOf(line: String): Int {
        val hits = body.indices.filter { body[it] == line }
        assertEquals("expected exactly one `$line` in converse():\n" + dump(), 1, hits.size)
        return hits.single()
    }

    private fun linesMentioning(fragment: String): List<Int> = body.indices.filter { fragment in body[it] }

    private fun assertOnlyLines(fragment: String, vararg pinned: Int) {
        assertEquals(
            "the lines of converse() mentioning `$fragment` are not exactly the pinned ones:\n" + dump(),
            pinned.toList(),
            linesMentioning(fragment),
        )
    }

    private val handshake get() = onlyIndexOf("secure.startHandshake()")
    private val rebind get() = onlyIndexOf("wire = SieveWire(secure)")
    private val startTlsCommand get() = onlyIndexOf("wire.command(\"STARTTLS\").requireOk(\"STARTTLS\")")
    private val authenticate get() = onlyIndexOf("wire.authenticatePlain(username, password)")

    @Test
    fun `the connection and its TLS upgrade are exactly these lines, with nothing inserted`() {
        val expected = listOf(
            "{",
            "val plain = Socket()",
            "var socket: Socket = plain",
            "try {",
            "plain.connect(InetSocketAddress(host, port), connectTimeout)",
            "plain.soTimeout = commandTimeout",
            "var wire = SieveWire(plain)",
            "val clearCapabilities = readCapabilities(wire)",
            "if (!clearCapabilities.startTls) throw insecure(\"the server does not offer STARTTLS\")",
            "wire.command(\"STARTTLS\").requireOk(\"STARTTLS\")",
            "if (transport == SieveTransport.STARTTLS) {",
            "val secure = tlsFactory.createSocket(plain, host, port, true) as SSLSocket",
            "secure.verifyingHostname()",
            "secure.soTimeout = commandTimeout",
            "secure.startHandshake()",
            "socket = secure",
            "wire = SieveWire(secure)",
            "}",
            "val tlsCapabilities = readCapabilities(wire)",
        )
        val code = body.filterNot { it.startsWith("//") }

        assertEquals(
            "the opening of converse() is no longer, line for line, what this was written against:\n" + dump(),
            expected,
            code.take(expected.size),
        )
    }

    @Test
    fun `the delays reach converse in their own places, and the TLS factory is the platform default`() {
        assertEquals(
            listOf(
                "fun <T> session(",
                "host: String,",
                "port: Int,",
                "username: String,",
                "password: String,",
                "connectTimeoutMs: Int = MANAGESIEVE_CONNECT_TIMEOUT_MS,",
                "commandTimeoutMs: Int = MANAGESIEVE_COMMAND_TIMEOUT_MS,",
                "block: ManageSieveSession.() -> T,",
                ")",
            ),
            ImapSource.functionSignature("ManageSieveClient", "session"),
        )
        assertEquals(
            listOf(
                "{",
                "converse(host, port, username, password, connectTimeoutMs.coerceAtLeast(0), commandTimeoutMs.coerceAtLeast(0), block)",
                "}",
            ),
            ImapSource.functionBody("ManageSieveClient", "session"),
        )
        assertEquals(
            listOf(
                "fun <T> converse(",
                "host: String,",
                "port: Int,",
                "username: String,",
                "password: String,",
                "connectTimeout: Int,",
                "commandTimeout: Int,",
                "block: ManageSieveSession.() -> T,",
                ")",
            ),
            ImapSource.functionSignature("ManageSieveClient", "converse"),
        )
        assertEquals(
            listOf(
                "val secure = tlsFactory.createSocket(plain, host, port, true) as SSLSocket",
                "val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory",
            ),
            ImapSource.fileLines("ManageSieveClient").filter {
                ("tlsFactory" in it || "SSLSocketFactory" in it) && !it.startsWith("import ")
            },
        )
    }

    @Test
    fun `AUTHENTICATE can only follow the handshake`() {
        assertOnlyLines("authenticatePlain", authenticate)
        assertOnlyLines("AUTHENTICATE")
        assertTrue(
            "authenticatePlain is not after STARTTLS, the handshake and the new wire " +
                "(starttls=$startTlsCommand, handshake=$handshake, rebind=$rebind, auth=$authenticate):\n" + dump(),
            authenticate > startTlsCommand && authenticate > handshake && authenticate > rebind,
        )

        val file = ImapSource.fileLines("ManageSieveClient")
        val code = file.filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        val writesVerb = code.filter { "\"AUTHENTICATE\"" in it }
        val method = ImapSource.functionBody("ManageSieveClient", "authenticatePlain")
        assertEquals("the verb AUTHENTICATE is written somewhere else than authenticatePlain: $writesVerb", 1, writesVerb.size)
        assertTrue("the verb AUTHENTICATE is not written inside authenticatePlain: $method", writesVerb.single() in method)

        val declaration = "fun authenticatePlain(username: String, password: String) {"
        val calls = ImapSource.fileNames().flatMap { name ->
            ImapSource.fileLines(name)
                .filter { "authenticatePlain(" in it && it != declaration }
                .map { "$name: $it" }
        }
        assertEquals(
            "authenticatePlain is called from somewhere else than converse()",
            listOf("ManageSieveClient: wire.authenticatePlain(username, password)"),
            calls,
        )
    }

    @Test
    fun `PLAIN is decided on the capabilities re-read after STARTTLS, and the clear ones decide only STARTTLS`() {
        val clearRead = onlyIndexOf("val clearCapabilities = readCapabilities(wire)")
        val startTlsCheck = onlyIndexOf("if (!clearCapabilities.startTls) throw insecure(\"the server does not offer STARTTLS\")")
        val tlsRead = onlyIndexOf("val tlsCapabilities = readCapabilities(wire)")
        val plainCheck = onlyIndexOf("if (!tlsCapabilities.offersPlain) throw insecure(\"the server does not offer PLAIN over TLS\")")
        val session = onlyIndexOf("val session = ManageSieveSession(wire, tlsCapabilities)")

        assertOnlyLines("clearCapabilities", clearRead, startTlsCheck)
        assertOnlyLines("tlsCapabilities", tlsRead, plainCheck, session)
        assertOnlyLines("readCapabilities", clearRead, tlsRead)
        assertOnlyLines("offersPlain", plainCheck)
        val order = listOf(clearRead, startTlsCheck, startTlsCommand, handshake, rebind, tlsRead, plainCheck, authenticate, session)
        assertEquals(
            "not in the order clear read, STARTTLS check, STARTTLS, handshake, new wire, re-read, PLAIN check, " +
                "AUTHENTICATE, session: $order\n" + dump(),
            order.sorted(),
            order,
        )
    }
}
