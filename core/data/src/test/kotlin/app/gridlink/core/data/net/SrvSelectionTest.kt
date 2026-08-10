package app.gridlink.core.data.net

import app.gridlink.core.jmap.Jmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The half of SRV that decides what to do with the records once they parse: which order to try
 * them in, and which ones to ignore. The rule underneath all of it is that this path can only ADD
 * candidates to discovery, so anything doubtful is dropped rather than obeyed.
 */
class SrvSelectionTest {

    @Test
    fun `lower priority wins, whatever order the server listed them in`() {
        val records = listOf(
            SrvRecord(priority = 30, weight = 0, port = 443, target = "c.example.com"),
            SrvRecord(priority = 10, weight = 0, port = 443, target = "a.example.com"),
            SrvRecord(priority = 20, weight = 0, port = 443, target = "b.example.com"),
        )
        assertEquals(
            listOf("a.example.com", "b.example.com", "c.example.com"),
            SrvSelection.order(records).map { it.target },
        )
    }

    @Test
    fun `a root target is dropped rather than treated as a veto`() {
        // 🔴 RFC 2782 lets "." mean "no service here". Honouring that as a veto would let one stray
        // record disable a domain whose well-known probe works today, so it only ever removes
        // itself from the candidate list.
        val records = listOf(
            SrvRecord(priority = 0, weight = 0, port = 0, target = ""),
            SrvRecord(priority = 10, weight = 0, port = 443, target = "jmap.example.com"),
        )
        assertEquals(listOf("jmap.example.com"), SrvSelection.order(records).map { it.target })
    }

    @Test
    fun `an impossible port is dropped`() {
        val records = listOf(
            SrvRecord(priority = 0, weight = 0, port = 0, target = "zero.example.com"),
            SrvRecord(priority = 10, weight = 0, port = 443, target = "ok.example.com"),
        )
        assertEquals(listOf("ok.example.com"), SrvSelection.order(records).map { it.target })
    }

    @Test
    fun `every usable record is returned exactly once`() {
        // The weighted walk removes as it picks; a bug there loses or repeats a server, and the
        // repeat is the one that would go unnoticed because discovery would still work.
        val records = (1..8).map { SrvRecord(priority = it % 2, weight = it, port = 443, target = "h$it") }
        repeat(50) { seed ->
            val ordered = SrvSelection.order(records, Random(seed))
            assertEquals(records.size, ordered.size)
            assertEquals(records.toSet(), ordered.toSet())
        }
    }

    @Test
    fun `weight never reorders across a priority boundary`() {
        // A heavy record in a worse band must still lose to a weightless one in a better band.
        val records = listOf(
            SrvRecord(priority = 20, weight = 1000, port = 443, target = "heavy.example.com"),
            SrvRecord(priority = 10, weight = 0, port = 443, target = "light.example.com"),
        )
        repeat(50) { seed ->
            assertEquals("light.example.com", SrvSelection.order(records, Random(seed)).first().target)
        }
    }

    @Test
    fun `weight decides how often a record goes first within its band`() {
        val heavy = SrvRecord(priority = 10, weight = 100, port = 443, target = "heavy.example.com")
        val light = SrvRecord(priority = 10, weight = 1, port = 443, target = "light.example.com")
        val heavyFirst = (0 until 200).count {
            SrvSelection.order(listOf(light, heavy), Random(it)).first() == heavy
        }
        // Not an exact ratio: the point is that the weighting is live and favours the heavy record,
        // not that this implementation's arithmetic matches some particular expected count.
        assertTrue("heavy went first $heavyFirst/200 times", heavyFirst > 140)
    }

    @Test
    fun `equal zero weights do not fix the order`() {
        // ⚠️ The degenerate case the uniform branch exists for. A running-sum walk over all-zero
        // weights stops at the first record every time, which is the fixed order that would send
        // every account on the domain to the same server.
        val records = (1..4).map { SrvRecord(priority = 10, weight = 0, port = 443, target = "h$it") }
        val firsts = (0 until 100).map { SrvSelection.order(records, Random(it)).first().target }.toSet()
        assertTrue("all-zero weights collapsed to $firsts", firsts.size > 1)
    }

    @Test
    fun `the default port is left out of the authority and any other port is kept`() {
        // 🔴 RFC 8620 §2.2 fetches https://hostname[:port]/.well-known/jmap. A domain on 8443 is
        // only reachable if the port survives into the URL.
        val record = SrvRecord(priority = 0, weight = 0, port = 8443, target = "jmap.example.com")
        assertEquals("jmap.example.com:8443", record.authority(defaultPort = 443))
        assertEquals("jmap.example.com", record.copy(port = 443).authority(defaultPort = 443))
    }

    @Test
    fun `the service names are the registered ones`() {
        assertEquals("_jmap._tcp.example.com", MailSrv.jmap("example.com"))
        assertEquals("_imaps._tcp.example.com", MailSrv.imaps("example.com"))
        assertEquals("_submission._tcp.example.com", MailSrv.submission("example.com"))
    }

    @Test
    fun `published hosts go in front of the guesses without replacing them`() {
        // 🔴 The stale-record case. A domain publishes SRV pointing somewhere that no longer
        // answers; the conventional guess that works today must still be tried behind it, or this
        // feature would make discovery worse for that domain than not having it at all.
        val published = listOf(SrvRecord(priority = 10, weight = 0, port = 8443, target = "jmap.example.com"))
        val guesses = listOf("example.com", "mail.example.com", "jmap.example.com")
        assertEquals(
            listOf("jmap.example.com:8443", "example.com", "mail.example.com", "jmap.example.com"),
            MailSrv.jmapCandidates(published, guesses),
        )
    }

    @Test
    fun `a published host that repeats a guess is probed once`() {
        val published = listOf(SrvRecord(priority = 10, weight = 0, port = 443, target = "mail.example.com"))
        val guesses = listOf("example.com", "mail.example.com")
        assertEquals(listOf("mail.example.com", "example.com"), MailSrv.jmapCandidates(published, guesses))
    }

    @Test
    fun `no records leaves the guesses exactly as they were`() {
        val guesses = listOf("example.com", "mail.example.com")
        assertEquals(guesses, MailSrv.jmapCandidates(emptyList(), guesses))
    }

    @Test
    fun `submission on 465 is implicit TLS and anything else is STARTTLS`() {
        val imaps = listOf(SrvRecord(priority = 0, weight = 0, port = 993, target = "imap.example.com"))
        val implicit = listOf(SrvRecord(priority = 0, weight = 0, port = 465, target = "smtp.example.com"))
        val starttls = listOf(SrvRecord(priority = 0, weight = 0, port = 587, target = "smtp.example.com"))
        assertTrue(MailSrv.imapEndpoints(imaps, implicit)!!.smtpImplicitTls)
        assertEquals(false, MailSrv.imapEndpoints(imaps, starttls)!!.smtpImplicitTls)
    }

    @Test
    fun `one half published still fills that half`() {
        val imaps = listOf(SrvRecord(priority = 0, weight = 0, port = 993, target = "imap.example.com"))
        val endpoints = MailSrv.imapEndpoints(imaps, emptyList())!!
        assertEquals("imap.example.com", endpoints.imapHost)
        assertEquals(993, endpoints.imapPort)
        assertNull(endpoints.smtpHost)
        assertNull(endpoints.smtpPort)
    }

    @Test
    fun `nothing published is null, not a blank set of endpoints`() {
        // The form has to be able to tell "the domain said nothing" from "the domain said empty",
        // because the first leaves the user's own typing alone and the second would wipe it.
        assertNull(MailSrv.imapEndpoints(emptyList(), emptyList()))
        val unusable = listOf(SrvRecord(priority = 0, weight = 0, port = 0, target = ""))
        assertNull(MailSrv.imapEndpoints(unusable, unusable))
    }

    @Test
    fun `the domain rule is the same one the hostname guesses use`() {
        // 🔴 SRV results are prepended to Jmap.autodiscoverHosts' guesses. If these two ever
        // disagreed about what an address' domain is, half of discovery would look somewhere else.
        listOf("alice@example.com", "Alice@Example.com.", "a.b@mail.example.co.uk").forEach { address ->
            assertEquals(Jmap.autodiscoverHosts(address).first(), MailSrv.domainOf(address))
        }
        listOf("", "no-at-sign", "alice@", "alice@localhost").forEach { address ->
            assertTrue(Jmap.autodiscoverHosts(address).isEmpty())
            assertNull(MailSrv.domainOf(address))
        }
    }
}
