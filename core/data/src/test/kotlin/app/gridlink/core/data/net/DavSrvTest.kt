package app.gridlink.core.data.net

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * The CalDAV/CardDAV half of SRV (RFC 6764): which names are asked for, and what the answer does to
 * the list of servers discovery tries. The rule is the one mail discovery lives by: a published
 * record goes FIRST and the guess is never dropped.
 */
class DavSrvTest {

    @Test
    fun `asks for the TLS names only`() {
        assertEquals("_caldavs._tcp.gmx.net", DavSrv.caldavs("gmx.net"))
        assertEquals("_carddavs._tcp.gmx.net", DavSrv.carddavs("gmx.net"))
    }

    @Test
    fun `a published record is tried before the domain, and the domain stays`() {
        val published = listOf(SrvRecord(priority = 0, weight = 0, port = 443, target = "caldav.gmx.net"))
        assertEquals(
            listOf("caldav.gmx.net", "gmx.net"),
            DavSrv.candidates(published, fallback = "gmx.net"),
        )
    }

    @Test
    fun `no record means the guess alone, exactly as before SRV existed`() {
        assertEquals(listOf("gmx.it"), DavSrv.candidates(emptyList(), fallback = "gmx.it"))
    }

    @Test
    fun `a non-standard port is kept on the candidate`() {
        val published = listOf(SrvRecord(priority = 0, weight = 0, port = 8443, target = "dav.example.com"))
        assertEquals(
            listOf("dav.example.com:8443", "example.com"),
            DavSrv.candidates(published, fallback = "example.com"),
        )
    }

    @Test
    fun `a record naming the domain itself does not duplicate it`() {
        val published = listOf(SrvRecord(priority = 0, weight = 0, port = 443, target = "example.com"))
        assertEquals(listOf("example.com"), DavSrv.candidates(published, fallback = "example.com", random = Random(1)))
    }
}
