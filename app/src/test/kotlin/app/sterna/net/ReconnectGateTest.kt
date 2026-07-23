package app.sterna.net

import org.junit.Assert.assertFalse
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
}
