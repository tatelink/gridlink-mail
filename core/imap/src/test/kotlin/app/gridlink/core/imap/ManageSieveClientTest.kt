@file:Suppress("MaxLineLength", "PropertyWrapping", "Wrapping")

package app.gridlink.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64

/** The dialogue the shipped client writes, against [FakeSieveServer]. */
class ManageSieveClientTest {

    private val chineseScript =
        "require [\"fileinto\"];\r\n" +
            "/*\r\nOK \"not the end of the script\"\r\n*/\r\n" +
            "if header :contains \"subject\" \"报告\" {\r\n" +
            "  fileinto \"项目/报告\";\r\n" +
            "}\r\n"

    @Test
    fun `LISTSCRIPTS gives every name and which one is active`() {
        FakeSieveServer(scripts = mapOf("sterna" to "keep;", "vacation" to "keep;"), active = "sterna").use { server ->
            val listed = server.session { listScripts() }

            assertEquals(listOf(SieveScriptInfo("sterna", true), SieveScriptInfo("vacation", false)), listed)
        }
    }

    @Test
    fun `GETSCRIPT gives the exact text, Chinese folder and a line reading OK included`() {
        FakeSieveServer(scripts = mapOf("sterna" to chineseScript)).use { server ->
            val text = server.session { getScript("sterna") }

            assertEquals(chineseScript, text)
        }
    }

    @Test
    fun `PUTSCRIPT sends a literal the server reads to the last octet`() {
        FakeSieveServer().use { server ->
            server.session { putScript("sterna", chineseScript) }
            server.awaitDone()

            assertEquals("the server found octets left over after the literal", emptyList<String>(), server.literalMismatches)
            assertArrayEquals(chineseScript.toByteArray(Charsets.UTF_8), server.scripts["sterna"])
            val put = server.commands.single { it.verb == "PUTSCRIPT" }
            assertEquals(listOf("sterna"), put.quotedArguments)
        }
    }

    @Test
    fun `SETACTIVE goes out with the name`() {
        FakeSieveServer(scripts = mapOf("sterna" to "keep;")).use { server ->
            server.session { setActive("sterna") }
            server.awaitDone()

            assertEquals("SETACTIVE \"sterna\"", server.commands.single { it.verb == "SETACTIVE" }.line)
            assertEquals("sterna", server.active)
        }
    }

    @Test
    fun `CHECKSCRIPT goes out when VERSION is advertised`() {
        FakeSieveServer().use { server ->
            val checked = server.session { checkScript(chineseScript) }
            server.awaitDone()

            assertTrue(checked)
            assertArrayEquals(
                chineseScript.toByteArray(Charsets.UTF_8),
                server.commands.single { it.verb == "CHECKSCRIPT" }.literals.single(),
            )
        }
    }

    @Test
    fun `CHECKSCRIPT does not go out without VERSION`() {
        val withoutVersion = FakeSieveServer.TLS_CAPABILITIES.filterNot { it.startsWith("\"VERSION\"") }
        FakeSieveServer(tlsCapabilities = withoutVersion).use { server ->
            val checked = server.session { checkScript(chineseScript) }
            server.awaitDone()

            assertFalse(checked)
            assertFalse("CHECKSCRIPT was sent: ${server.commands}", "CHECKSCRIPT" in server.verbs())
        }
    }

    @Test
    fun `a NO on PUTSCRIPT is a refusal carrying the server's words`() {
        FakeSieveServer(
            respond = { if (it.verb == "PUTSCRIPT") listOf("NO (QUOTA/MAXSCRIPTS) \"line 4: unknown extension 'imap4flags'\"") else null },
        ).use { server ->
            val error = assertThrows(ManageSieveException::class.java) {
                server.session { putScript("sterna", chineseScript) }
            }

            assertEquals(ManageSieveException.Kind.REFUSED, error.kind)
            assertEquals("line 4: unknown extension 'imap4flags'", error.serverText)
            assertEquals("QUOTA/MAXSCRIPTS", error.responseCode)
            assertTrue("the server's text is not in the message: ${error.message}", "unknown extension 'imap4flags'" in error.message.orEmpty())
        }
    }

    @Test
    fun `a server without STARTTLS is insecure and never sees AUTHENTICATE`() {
        val noStartTls = FakeSieveServer.CLEAR_CAPABILITIES.filterNot { it == "\"STARTTLS\"" }
        val withPlain = noStartTls.map { if (it.startsWith("\"SASL\"")) "\"SASL\" \"PLAIN\"" else it }
        FakeSieveServer(clearCapabilities = withPlain).use { server ->
            val error = assertThrows(ManageSieveException::class.java) { server.session { listScripts() } }
            server.awaitDone()

            assertEquals(ManageSieveException.Kind.INSECURE, error.kind)
            assertFalse("AUTHENTICATE reached a server without STARTTLS: ${server.commands}", "AUTHENTICATE" in server.verbs())
            assertFalse("the password is in the message", FakeSieveServer.PASSWORD in error.message.orEmpty())
        }
    }

    @Test
    fun `PLAIN offered only in the clear is insecure and never sees AUTHENTICATE`() {
        val clearWithPlain = FakeSieveServer.CLEAR_CAPABILITIES.map { if (it.startsWith("\"SASL\"")) "\"SASL\" \"PLAIN\"" else it }
        val tlsWithoutPlain = FakeSieveServer.TLS_CAPABILITIES.map { if (it.startsWith("\"SASL\"")) "\"SASL\" \"SCRAM-SHA-256\"" else it }
        FakeSieveServer(clearCapabilities = clearWithPlain, tlsCapabilities = tlsWithoutPlain).use { server ->
            val error = assertThrows(ManageSieveException::class.java) { server.session { listScripts() } }
            server.awaitDone()

            assertEquals(ManageSieveException.Kind.INSECURE, error.kind)
            assertEquals(listOf("STARTTLS"), server.verbs())
        }
    }

    @Test
    fun `PLAIN offered only after STARTTLS, as Stalwart does, authenticates after STARTTLS`() {
        FakeSieveServer().use { server ->
            val capabilities = server.session { capabilities }
            server.awaitDone()

            assertEquals(listOf("STARTTLS", "AUTHENTICATE", "LOGOUT"), server.verbs())
            assertEquals(listOf("PLAIN"), capabilities.saslMechanisms)
            assertFalse("the capabilities kept are the clear ones", capabilities.startTls)
            val (mechanism, payload) = server.commands.single { it.verb == "AUTHENTICATE" }.quotedArguments
            assertEquals("PLAIN", mechanism)
            val nul = Char(0)
            assertEquals(
                "$nul${FakeSieveServer.USER}$nul${FakeSieveServer.PASSWORD}",
                String(Base64.getDecoder().decode(payload), Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `a refused AUTHENTICATE is a refusal that does not carry the credentials`() {
        FakeSieveServer(
            respond = { if (it.verb == "AUTHENTICATE") listOf("NO (AUTH-TOO-WEAK) \"Authentication failed\"") else null },
        ).use { server ->
            val error = assertThrows(ManageSieveException::class.java) { server.session { listScripts() } }

            assertEquals(ManageSieveException.Kind.REFUSED, error.kind)
            assertEquals("Authentication failed", error.serverText)
            val message = error.message.orEmpty()
            assertFalse("the password is in the message: $message", FakeSieveServer.PASSWORD in message)
            assertFalse(
                "the SASL payload is in the message: $message",
                saslPlainPayload(FakeSieveServer.USER, FakeSieveServer.PASSWORD) in message,
            )
        }
    }

    @Test
    fun `a BYE is a refusal`() {
        FakeSieveServer(
            respond = { if (it.verb == "LISTSCRIPTS") listOf("BYE (TRYLATER) \"shutting down\"") else null },
        ).use { server ->
            val error = assertThrows(ManageSieveException::class.java) { server.session { listScripts() } }

            assertEquals(ManageSieveException.Kind.REFUSED, error.kind)
            assertEquals("TRYLATER", error.responseCode)
        }
    }

    @Test
    fun `a closed port is unreachable`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val error = assertThrows(ManageSieveException::class.java) {
            runBlocking {
                ManageSieveClient(SieveTransport.STARTTLS_WITHOUT_HANDSHAKE_FOR_TESTS)
                    .session("127.0.0.1", closedPort, FakeSieveServer.USER, FakeSieveServer.PASSWORD) { listScripts() }
            }
        }

        assertEquals(ManageSieveException.Kind.UNREACHABLE, error.kind)
    }

    @Test
    fun `a script name sent as a literal, followed by ACTIVE, is read whole`() {
        FakeSieveServer(
            respond = { if (it.verb == "LISTSCRIPTS") listOf("{6}", "sterna ACTIVE", "\"vacation\"", "OK") else null },
        ).use { server ->
            val listed = server.session { listScripts() }

            assertEquals(listOf(SieveScriptInfo("sterna", true), SieveScriptInfo("vacation", false)), listed)
        }
    }

    @Test
    fun `a server that accepts and never speaks is unreachable within the command delay`() {
        FakeSieveServer(mute = true).use { server ->
            val started = System.nanoTime()
            val error = assertThrows(ManageSieveException::class.java) {
                runBlocking {
                    ManageSieveClient(SieveTransport.STARTTLS_WITHOUT_HANDSHAKE_FOR_TESTS).session(
                        server.host,
                        server.port,
                        FakeSieveServer.USER,
                        FakeSieveServer.PASSWORD,
                        connectTimeoutMs = 300,
                        commandTimeoutMs = 300,
                    ) { listScripts() }
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(ManageSieveException.Kind.UNREACHABLE, error.kind)
            assertTrue("the client waited ${elapsedMs}ms on a mute server with a 300ms delay", elapsedMs < 5_000)
            assertEquals(emptyList<String>(), server.verbs())
        }
    }

    @Test
    fun `the public constructor selects the handshaking transport`() {
        assertEquals(SieveTransport.STARTTLS, ManageSieveClient().transport)
        assertEquals(4190, MANAGESIEVE_PORT)
    }
}

/** The codec, executed on bytes. */
class ManageSieveCodecTest {

    private fun response(wire: String) = SieveReader(ByteArrayInputStream(wire.toByteArray(Charsets.UTF_8))).readResponse()

    @Test
    fun `a literal header counts UTF-8 octets, not characters`() {
        // 17 characters, 25 octets: each of the four ideographs is three.
        val literal = sieveLiteral("fileinto \"项目/报告\";")
        val header = String(literal, Charsets.US_ASCII).substringBefore("\r\n")

        assertEquals("{25+}", header)
        assertEquals(25 + "{25+}\r\n".length, literal.size)
    }

    @Test
    fun `quoted strings unescape backslash and quote`() {
        val parsed = response("\"a \\\"b\\\" \\\\c\" ACTIVE\r\nOK\r\n")

        assertEquals(listOf(SieveToken.Text("a \"b\" \\c"), SieveToken.Atom("ACTIVE")), parsed.lines.single())
        assertEquals("\"x\\\\y\\\"z\"", String(sieveQuoted("x\\y\"z"), Charsets.UTF_8))
    }

    @Test
    fun `a literal is read by octets, in both spellings, and may carry the text of a NO`() {
        val script = "keep;\r\nOK\r\n报告"
        val octets = script.toByteArray(Charsets.UTF_8).size
        val parsed = response("{$octets}\r\n$script\r\nOK\r\n")
        assertEquals(listOf(SieveToken.Text(script)), parsed.lines.single())

        val refused = response("NO (WARNINGS) {$octets+}\r\n$script\r\n")
        assertEquals("NO", refused.status)
        assertEquals("WARNINGS", refused.code)
        assertEquals(script, refused.text)
    }

    @Test
    fun `a name with a line break goes out as a literal`() {
        assertEquals("\"sterna\"", String(sieveString("sterna"), Charsets.UTF_8))
        assertEquals("{3+}\r\na\r\n", String(sieveString("a\r\n"), Charsets.UTF_8))
    }

    @Test
    fun `capabilities are read by name, case-insensitively`() {
        val capabilities = parseSieveCapabilities(response(FakeSieveServer.TLS_CAPABILITIES.joinToString("\r\n") + "\r\nOK\r\n").lines)

        assertEquals(listOf("PLAIN"), capabilities.saslMechanisms)
        assertEquals("1.0", capabilities.version)
        assertEquals(listOf("fileinto", "vacation"), capabilities.sieveExtensions)
        assertFalse(capabilities.startTls)
        assertTrue(parseSieveCapabilities(response("\"starttls\"\r\nOK\r\n").lines).startTls)
    }

    @Test
    fun `a response code carrying a string keeps the text that follows it as the server's words`() {
        val error = assertThrows(ManageSieveException::class.java) {
            response("NO (TAG \"x\") \"le vrai message\"\r\n").requireOk("PUTSCRIPT")
        }

        assertEquals("le vrai message", error.serverText)
        assertEquals("TAG x", error.responseCode)
    }

    @Test
    fun `a literal above the ceiling fails on its header, before any body is read`() {
        val over = runCatching { response("{${MANAGESIEVE_MAX_LITERAL_OCTETS + 1}}\r\n") }.exceptionOrNull()
        assertTrue("expected a ManageSieveException on the header, got $over", over is ManageSieveException)

        // At the ceiling the header is accepted and the body is waited for: the stream ends there.
        val at = runCatching { response("{$MANAGESIEVE_MAX_LITERAL_OCTETS}\r\n") }.exceptionOrNull()
        assertTrue("expected the body to be read at the ceiling, got $at", at is java.io.EOFException)
    }

    @Test
    fun `every I-O failure, a TLS handshake included, is unreachable`() {
        val failures = listOf(
            javax.net.ssl.SSLHandshakeException("Remote host terminated the handshake"),
            javax.net.ssl.SSLException("Connection reset"),
            java.net.SocketTimeoutException("Read timed out"),
            java.net.UnknownHostException("sieve.example.test"),
            java.net.ConnectException("Connection refused"),
            java.io.EOFException("connection closed"),
        )

        assertEquals(
            failures.associate { it.javaClass.simpleName to ManageSieveException.Kind.UNREACHABLE },
            failures.associate { it.javaClass.simpleName to manageSieveFailureKind(it) },
        )
    }

    @Test
    fun `a stream that ends mid-response fails, and is not a response`() {
        val error = runCatching { response("\"SASL\" \"PLAIN\"\r\n") }.exceptionOrNull()

        assertTrue("expected an EOF, got $error", error is java.io.EOFException)
    }
}
