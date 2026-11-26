package app.gridlink.core.data.mail

import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.account.ConnectionSecurity
import app.gridlink.core.data.account.MailEndpoint
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.core.data.account.withInboxUnread
import app.gridlink.core.imap.ImapClient
import app.gridlink.core.imap.ImapException
import app.gridlink.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * The background IMAP pass refreshes the stored inbox unread (widget count fix, 2026-11-25).
 *
 * Before, only a foreground refresh moved that number, so mail read in webmail kept the widget and
 * the "All inboxes" header too high until the app was opened. Each pass now asks the server once
 * per account, for the inbox only, and a refusal leaves the old value without failing the pass.
 */
class BackgroundInboxUnreadTest {

    // ---- the pass, end to end against a scripted IMAP server on loopback ----

    @Test
    fun `a pass without the inbox reads its count with one STATUS and no per-folder STATUS`() = runBlocking {
        LoopbackImap { tag, line ->
            when {
                line.startsWith("STATUS") -> "* STATUS INBOX (UNSEEN 2)\r\n$tag OK done\r\n"
                else -> standard(tag, line)
            }
        }.use { server ->
            val result = pass(server, includeInbox = false)

            assertEquals("INBOX", result.inboxPath)
            assertEquals(2, result.inboxUnread)
            assertEquals(listOf("Work"), result.loads.map { it.mailboxId })
            val statuses = server.commands.filter { it.startsWith("STATUS") }
            assertEquals(listOf("STATUS \"INBOX\" (UNSEEN)"), statuses)
        }
    }

    @Test
    fun `a pass that syncs the inbox asks the selected inbox with SEARCH, never STATUS`() = runBlocking {
        LoopbackImap { tag, line ->
            when {
                line.startsWith("SEARCH UNSEEN") -> "* SEARCH 5 6 7\r\n$tag OK done\r\n"
                else -> standard(tag, line)
            }
        }.use { server ->
            val result = pass(server, includeInbox = true)

            assertEquals(3, result.inboxUnread)
            assertTrue(server.commands.none { it.startsWith("STATUS") })
            assertEquals(1, server.commands.count { it.startsWith("SEARCH UNSEEN") })
            // Asked while the inbox, not the extra folder, was selected.
            val search = server.commands.indexOfFirst { it.startsWith("SEARCH UNSEEN") }
            val lastSelectBefore = server.commands.subList(0, search).last { it.startsWith("SELECT") }
            assertTrue(lastSelectBefore, lastSelectBefore.contains("\"INBOX\""))
        }
    }

    @Test
    fun `a refused STATUS leaves the count unknown and the pass still returns its folders`() = runBlocking {
        LoopbackImap { tag, line ->
            when {
                line.startsWith("STATUS") -> "$tag NO not today\r\n"
                else -> standard(tag, line)
            }
        }.use { server ->
            val result = pass(server, includeInbox = false)

            assertNull(result.inboxUnread)
            assertEquals(listOf("Work"), result.loads.map { it.mailboxId })
        }
    }

    /**
     * The pass's count reaches the stored account count. MailRepository needs Room to construct, so
     * this reads its IMAP branch from source, the pattern `JmapSearchScopeTest` uses: the call must
     * sit in the IMAP branch of refreshAccountFolders, keyed on the pass's own inbox path.
     */
    @Test
    fun `the background IMAP branch stores the pass's inbox count`() {
        val source = locate("core/data/src/main/kotlin/app/gridlink/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("suspend fun refreshAccountFolders(")
        check(start >= 0) { "MailRepository no longer declares refreshAccountFolders" }
        val imapEnd = source.indexOf("val resolved = resolve(credentials)", start)
        check(imapEnd > start) { "refreshAccountFolders no longer has its JMAP branch after the IMAP one" }
        val imapBranch = source.substring(start, imapEnd)
        assertTrue(
            "the IMAP pass no longer stores the server's inbox unread",
            imapBranch.contains(
                "watched.inboxUnread?.let { accountStore.setInboxUnread(credentials.id, watched.inboxPath, it) }",
            ),
        )
        assertTrue(
            "the JMAP branch must not store an IMAP-only count",
            !source.substring(imapEnd, source.indexOf("return refreshes", imapEnd)).contains("setInboxUnread"),
        )
    }

    private fun locate(relative: String): java.io.File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            java.io.File(dir, relative).takeIf { it.isFile }?.let { return it }
            java.io.File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }

    // ---- what is stored ----

    private fun account(id: String, inboxId: String?, unread: Int) =
        StoredAccount(
            id = id,
            server = "imap.example",
            username = "$id@example",
            inboxId = inboxId,
            unread = unread,
        )

    @Test
    fun `the stored count follows the server down as well as up`() {
        val list = listOf(account("a", "INBOX", unread = 5), account("b", "INBOX", unread = 1))
        val updated = withInboxUnread(list, "a", "INBOX", 0)
        assertEquals(listOf(0, 1), updated.map { it.unread })
        assertEquals(listOf(8, 1), withInboxUnread(list, "a", "INBOX", 8).map { it.unread })
    }

    @Test
    fun `a different folder or an inbox never learned changes nothing`() {
        val list = listOf(account("a", "INBOX", unread = 5), account("b", null, unread = 0))
        assertSame(list[0], withInboxUnread(list, "a", "Archive", 9)[0])
        assertSame(list[1], withInboxUnread(list, "b", "INBOX", 9)[1])
    }

    @Test
    fun `only a refusal is swallowed`() {
        assertEquals(3, backgroundInboxUnread { 3 })
        assertNull(backgroundInboxUnread { throw ImapException("NO") })
        val dropped = runCatching { backgroundInboxUnread { throw java.io.IOException("gone") } }
        assertTrue(dropped.exceptionOrNull() is java.io.IOException)
    }

    // ---- fixtures ----

    private suspend fun pass(server: LoopbackImap, includeInbox: Boolean) =
        service().loadWatchedFolders(server.credentials, setOf("Work"), includeInbox, limit = 50)

    private fun service() = ImapMailService(
        imapClient = ImapClient(),
        smtpClient = SmtpClient(),
        tokenRefresher = OAuthTokenRefresher(
            refreshTokens = { _, _, _ -> error("no OAuth in this test") },
            readOAuth = { null },
            persistTokens = { _, _, _, _ -> },
        ),
    )

    /** INBOX plus one watched folder, both empty, and OK to everything else. */
    private fun standard(tag: String, line: String): String = when {
        line.startsWith("LIST") ->
            "* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n* LIST (\\HasNoChildren) \"/\" \"Work\"\r\n$tag OK done\r\n"
        line.startsWith("SELECT") ->
            "* 0 EXISTS\r\n* OK [UIDVALIDITY 1] ok\r\n* OK [UIDNEXT 1] ok\r\n$tag OK [READ-WRITE] selected\r\n"
        line.startsWith("SEARCH") -> "* SEARCH\r\n$tag OK done\r\n"
        else -> "$tag OK done\r\n"
    }

    /** A minimal one-connection scripted IMAP server; [commands] records each line, tag stripped. */
    private class LoopbackImap(private val responder: (tag: String, line: String) -> String) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val credentials = AccountCredentials(
            server = "imap.example",
            username = "tester",
            password = "secret",
            id = "acct",
            protocol = MailProtocol.IMAP,
            imap = MailEndpoint(server.inetAddress.hostAddress!!, server.localPort, ConnectionSecurity.NONE),
        )

        private val thread = Thread(::serve, "loopback-imap").apply {
            isDaemon = true
            start()
        }

        private fun serve() {
            runCatching {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                    val writer = OutputStreamWriter(socket.outputStream, Charsets.ISO_8859_1)
                    writer.write("* OK fake IMAP ready\r\n")
                    writer.flush()
                    var open = true
                    while (open) {
                        val raw = reader.readLine()
                        if (raw == null) {
                            open = false
                        } else {
                            val tag = raw.substringBefore(' ')
                            val line = raw.substringAfter(' ')
                            commands.add(line)
                            writer.write(responder(tag, line))
                            writer.flush()
                            open = !line.startsWith("LOGOUT")
                        }
                    }
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
            thread.interrupt()
        }
    }
}
