package app.gridlink.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PERMANENTFLAGS read off SELECT, which is how IMAP answers "what tags exist here" without the
 * client reading a single message (RFC 9051 §7.3.2).
 *
 * ## Why this is parsed by token position and tested on the wire
 * The other SELECT fields are pulled out of the response flattened to a string, and that works for
 * them because `[UIDVALIDITY 12]` is atoms all the way down. PERMANENTFLAGS is not: the parenthesised
 * flag list becomes a real nested list, so flattening it yields `[\Seen, holiday]` and a regex over
 * that would be matching the punctuation `toString` happens to print rather than anything IMAP sent.
 * These tests exist to hold that distinction still, because the flat-string approach LOOKS right
 * beside its neighbours and passes a hand-written eyeball test.
 *
 * ## The null-versus-empty rule
 * Null means the server said nothing, empty means the server listed a vocabulary of system flags
 * alone. Collapsing them tells a reader with a tagged mailbox that they have no tags, which is the
 * failure this whole feature was built to remove.
 */
class PermanentFlagsTest {

    private fun serverAnswering(permanentFlags: String?): FakeImapServer = FakeImapServer { tag, line ->
        when {
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1\r\n$tag OK done\r\n"
            line.startsWith("SELECT") -> buildString {
                append("* 3 EXISTS\r\n* OK [UIDVALIDITY 1] ok\r\n")
                if (permanentFlags != null) append("* OK [PERMANENTFLAGS ($permanentFlags)] limited\r\n")
                append("$tag OK [READ-WRITE] selected\r\n")
            }
            else -> ok(tag)
        }
    }

    private fun keywordsFrom(permanentFlags: String?): List<String>? =
        serverAnswering(permanentFlags).use { server ->
            server.session().use { it.select("INBOX").permanentKeywords }
        }

    @Test fun `keywords come back and system flags do not`() {
        assertEquals(
            listOf("holiday", "receipts"),
            keywordsFrom("\\Answered \\Flagged \\Deleted \\Seen \\Draft holiday receipts"),
        )
    }

    /** `\*` is permission to invent keywords, not a keyword. It is the likeliest thing to leak. */
    @Test fun `the wildcard marker is not a tag`() {
        assertEquals(emptyList<String>(), keywordsFrom("\\Seen \\*"))
    }

    /** A folder nobody has tagged: the server DID answer, and the answer is none. */
    @Test fun `a system-only vocabulary is empty rather than absent`() {
        assertEquals(emptyList<String>(), keywordsFrom("\\Answered \\Seen"))
    }

    /** 🔴 The distinction the caller's `complete` flag is built on. */
    @Test fun `a server that never mentions permanentflags answers null`() {
        assertNull(keywordsFrom(null))
    }

    /** The rest of SELECT still parses with the extra untagged line in the way. */
    @Test fun `the other select fields survive`() {
        serverAnswering("\\Seen holiday").use { server ->
            server.session().use { session ->
                val status = session.select("INBOX")
                assertEquals(3, status.exists)
                assertEquals(1L, status.uidValidity)
            }
        }
    }
}
