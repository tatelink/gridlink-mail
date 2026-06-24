package app.jmail.core.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapTest {
    @Test
    fun capabilityUrisAreStandard() {
        assertEquals("urn:ietf:params:jmap:core", Jmap.CORE_CAPABILITY)
        assertEquals("urn:ietf:params:jmap:mail", Jmap.MAIL_CAPABILITY)
    }

    @Test
    fun sessionUrlNormalisesInput() {
        val expected = "https://mail.example.com/.well-known/jmap"
        assertEquals(expected, Jmap.sessionUrlFor("mail.example.com"))
        assertEquals(expected, Jmap.sessionUrlFor("https://mail.example.com"))
        assertEquals(expected, Jmap.sessionUrlFor("https://mail.example.com/"))
        assertEquals(expected, Jmap.sessionUrlFor("mail.example.com/.well-known/jmap"))
    }

    @Test
    fun autodiscoverTriesDomainThenSubdomains() {
        assertEquals(
            listOf("example.com", "mail.example.com", "jmap.example.com"),
            Jmap.autodiscoverHosts("alice@example.com"),
        )
        // Case and a trailing dot in the domain are normalised away.
        assertEquals(
            listOf("example.com", "mail.example.com", "jmap.example.com"),
            Jmap.autodiscoverHosts("Alice@Example.com."),
        )
    }

    @Test
    fun autodiscoverRejectsMalformedAddresses() {
        assertTrue(Jmap.autodiscoverHosts("").isEmpty())
        assertTrue(Jmap.autodiscoverHosts("no-at-sign").isEmpty())
        assertTrue(Jmap.autodiscoverHosts("alice@").isEmpty())
        // A bare hostname with no dot is not a usable mail domain.
        assertTrue(Jmap.autodiscoverHosts("alice@localhost").isEmpty())
    }
}
