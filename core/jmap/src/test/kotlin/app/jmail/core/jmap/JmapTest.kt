package app.jmail.core.jmap

import org.junit.Assert.assertEquals
import org.junit.Test

class JmapTest {
    @Test
    fun capabilityUrisAreStandard() {
        assertEquals("urn:ietf:params:jmap:core", Jmap.CORE_CAPABILITY)
        assertEquals("urn:ietf:params:jmap:mail", Jmap.MAIL_CAPABILITY)
    }
}
