package app.sterna.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectGateTest {

    @Test fun `the replayed callback for an already-connected network is not a reconnect`() {
        val gate = ReconnectGate(online = true)
        assertFalse(gate.onAvailable(handle = 1L))
    }

    @Test fun `a network arriving after a cold start with no connectivity is a reconnect`() {
        val gate = ReconnectGate(online = false)
        assertTrue(gate.onAvailable(handle = 1L))
    }

    @Test fun `losing the default network then getting one back fires once`() {
        val gate = ReconnectGate(online = true)
        gate.onAvailable(1L)
        gate.onLost(1L)
        assertTrue(gate.onAvailable(2L))
        assertFalse(gate.onAvailable(2L))
    }

    @Test fun `a Wi-Fi to mobile handover is not a reconnect`() {
        val gate = ReconnectGate(online = true)
        gate.onAvailable(1L)
        // The new default is announced before the old one is reported lost.
        assertFalse(gate.onAvailable(2L))
        gate.onLost(1L)
        assertFalse(gate.onAvailable(2L))
    }

    @Test fun `losing one of two live transports is not going offline`() {
        val gate = ReconnectGate(online = true)
        gate.onAvailable(1L) // Wi-Fi
        gate.onAvailable(2L) // mobile
        gate.onLost(1L)
        // Mobile still carries traffic, so its re-announcement is not a reconnect.
        assertFalse(gate.onAvailable(2L))
    }

    @Test fun `offline only once the last transport is gone`() {
        val gate = ReconnectGate(online = true)
        gate.onAvailable(1L)
        gate.onAvailable(2L)
        gate.onLost(1L)
        gate.onLost(2L)
        assertTrue(gate.onAvailable(3L))
    }

    @Test fun `transports lost in the reverse order still end offline`() {
        val gate = ReconnectGate(online = true)
        gate.onAvailable(1L)
        gate.onAvailable(2L)
        gate.onLost(2L)
        gate.onLost(1L)
        assertTrue(gate.onAvailable(1L))
    }

    @Test fun `network property churn does not re-fire while online`() {
        val gate = ReconnectGate(online = false)
        assertTrue(gate.onAvailable(1L))
        assertFalse(gate.onAvailable(1L))
        assertFalse(gate.onAvailable(1L))
    }

    @Test fun `a loss before any network was announced still counts as going offline`() {
        val gate = ReconnectGate(online = true)
        gate.onLost(7L)
        assertTrue(gate.onAvailable(8L))
    }

    @Test fun `flapping alternates between offline and a fresh reconnect`() {
        val gate = ReconnectGate(online = false)
        assertTrue(gate.onAvailable(1L))
        gate.onLost(1L)
        assertTrue(gate.onAvailable(1L))
        gate.onLost(1L)
        assertTrue(gate.onAvailable(1L))
    }

    // --- The published `online` state that drives the offline banner (#65) ---

    @Test fun `online reflects the seed`() {
        assertTrue(ReconnectGate(online = true).online.value)
        assertFalse(ReconnectGate(online = false).online.value)
    }

    @Test fun `losing the last network flips online to false`() {
        val gate = ReconnectGate(online = true)
        gate.onAvailable(1L)
        gate.onLost(1L)
        assertFalse(gate.online.value)
    }

    @Test fun `a single loss after a seeded-online start goes offline`() {
        // WiFi off while idle: framework never replayed onAvailable, so the set is empty.
        val gate = ReconnectGate(online = true)
        gate.onLost(1L)
        assertFalse(gate.online.value)
    }

    @Test fun `a Wi-Fi to mobile handover stays online`() {
        val gate = ReconnectGate(online = true)
        gate.onAvailable(1L) // Wi-Fi
        gate.onAvailable(2L) // mobile announced before Wi-Fi is reported lost
        gate.onLost(1L)
        assertTrue(gate.online.value)
    }

    @Test fun `online returns to true when a network comes back`() {
        val gate = ReconnectGate(online = false)
        assertFalse(gate.online.value)
        gate.onAvailable(1L)
        assertTrue(gate.online.value)
    }

    // --- The reconnect refresh schedule, and which failure the banner is allowed to show ---

    @Test fun `the first attempt only waits for the settle`() {
        assertEquals(1_500L, ReconnectRefresh.delayBeforeMs(0))
    }

    @Test fun `the gaps between retries widen`() {
        val gaps = (1 until ReconnectRefresh.MAX_TRIES).map { ReconnectRefresh.delayBeforeMs(it) }
        assertEquals(gaps.sorted(), gaps)
        assertTrue(gaps.first() < gaps.last())
    }

    @Test fun `the schedule covers a tunnel that takes twenty seconds to come back up`() {
        val total = (0 until ReconnectRefresh.MAX_TRIES).sumOf { ReconnectRefresh.delayBeforeMs(it) }
        assertTrue("only $total ms of cover", total >= 20_000L)
    }

    @Test fun `the gap is capped, never unbounded`() {
        val last = ReconnectRefresh.delayBeforeMs(ReconnectRefresh.MAX_TRIES - 1)
        assertEquals(last, ReconnectRefresh.delayBeforeMs(99))
        assertTrue(last <= 12_000L)
    }

    @Test fun `every attempt waits, so the sequence can never spin`() {
        (0..99).forEach { assertTrue(ReconnectRefresh.delayBeforeMs(it) > 0L) }
    }

    @Test fun `a failure with an attempt still to come is not shown`() {
        // The network is back: the banner describes the present, not a refresh we are retrying.
        (0 until ReconnectRefresh.MAX_TRIES - 1).forEach { attempt ->
            assertTrue(ReconnectRefresh.retrying(attempt))
            assertNull(ReconnectRefresh.errorAfterAttempt(attempt, "Connection reset"))
        }
    }

    @Test fun `the last failure is reported honestly`() {
        val last = ReconnectRefresh.MAX_TRIES - 1
        assertFalse(ReconnectRefresh.retrying(last))
        assertEquals("500 Internal Server Error", ReconnectRefresh.errorAfterAttempt(last, "500 Internal Server Error"))
    }

    @Test fun `a successful last attempt leaves no error behind`() {
        assertNull(ReconnectRefresh.errorAfterAttempt(ReconnectRefresh.MAX_TRIES - 1, null))
    }

    @Test fun `more than one attempt is made`() {
        assertTrue(ReconnectRefresh.MAX_TRIES > 1)
    }
}
