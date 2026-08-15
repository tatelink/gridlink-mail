package app.gridlink.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row keys the mirror reconciles on.
 *
 * Trivial-looking string work that decides whether two accounts sharing a server path overwrite
 * each other's contacts, and whether an unchanged card is rewritten on every single sync pass.
 */
class SystemMirrorKeysTest {

    @Test
    fun `two accounts holding the same href get different rows`() {
        val a = SystemMirror.sourceId("acc-1", "/carddav/addressbooks/default/1.vcf")
        val b = SystemMirror.sourceId("acc-2", "/carddav/addressbooks/default/1.vcf")
        assertNotEquals(a, b)
    }

    @Test
    fun `a source id starts with its account's prefix, which is how a sweep scopes itself`() {
        val id = SystemMirror.sourceId("acc-1", "/cal/1.ics")
        assertTrue(id.startsWith(SystemMirror.prefix("acc-1")))
        // And is not caught by a sibling whose id is a prefix of it — the separator earns its keep.
        assertTrue(!id.startsWith(SystemMirror.prefix("acc")))
    }

    @Test
    fun `an etag is the fingerprint when the server gives one`() {
        assertEquals("\"abc\"", SystemMirror.fingerprint("\"abc\"", "BEGIN:VCARD"))
    }

    @Test
    fun `no etag falls back to the content, so unchanged cards still skip`() {
        val raw = "BEGIN:VCARD\nFN:Jonah\nEND:VCARD"
        assertEquals(SystemMirror.fingerprint(null, raw), SystemMirror.fingerprint("  ", raw))
        assertNotEquals(SystemMirror.fingerprint(null, raw), SystemMirror.fingerprint(null, raw + "\n"))
    }
}
