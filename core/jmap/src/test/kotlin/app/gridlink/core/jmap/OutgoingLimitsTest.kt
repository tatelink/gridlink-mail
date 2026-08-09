package app.gridlink.core.jmap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The cap on a file picked to SEND, which is the half [DownloadLimitsTest] does not cover.
 *
 * The interesting case here is not the big file that says it is big — [DownloadLimits.enforce]
 * already refuses that from the declared size, before a byte is read. It is the stream that does
 * NOT say, or says the wrong thing: a content provider is free to omit SIZE or report it wrongly,
 * so the read itself has to be the thing that holds.
 */
class OutgoingLimitsTest {

    @Test fun aFileUnderTheCapIsReadWhole() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val read = OutgoingLimits.readAtMost(ByteArrayInputStream(bytes), OutgoingLimits.ATTACHMENT_MAX_BYTES)
        assertArrayEquals(bytes, read)
    }

    @Test fun exactlyTheCapIsStillRead() {
        // The boundary belongs to the user, matching allows(): at the limit is within it.
        val bytes = ByteArray(1024)
        val read = OutgoingLimits.readAtMost(ByteArrayInputStream(bytes), 1024)
        assertEquals(1024, read.size)
    }

    @Test fun onePastTheCapThrows() {
        val ex = assertThrows(ContentTooLargeException::class.java) {
            OutgoingLimits.readAtMost(ByteArrayInputStream(ByteArray(1025)), 1024)
        }
        assertEquals(1024L, ex.maxBytes)
        // The true size is unknown by construction: the whole point is that it stopped early
        // rather than measuring the thing first.
        assertEquals(-1L, ex.bytes)
    }

    @Test fun anEmptyFileIsNotAnError() {
        assertEquals(0, OutgoingLimits.readAtMost(ByteArrayInputStream(ByteArray(0)), 1024).size)
    }

    @Test fun aStreamThatLiesAboutItsSizeIsStillStoppedEarly() {
        // The failure mode this exists for: SIZE said 10 bytes so the pre-read gate passed it, and
        // the stream then hands over far more. Counted here rather than asserted on the exception,
        // because what matters is that the reader stopped instead of allocating to exhaustion.
        val cap = 64L * 1024
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                served++
                return 0
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        assertThrows(ContentTooLargeException::class.java) {
            OutgoingLimits.readAtMost(endless, cap)
        }
        // One buffer's overshoot at most, not an unbounded read of an endless stream.
        assertTrue("served $served bytes for a $cap cap", served <= cap + 64 * 1024)
    }

    @Test fun theOutgoingCapIsTighterThanTheInboundOne() {
        // Deliberate, and the reason OutgoingLimits is its own object: an outgoing file is held in
        // memory, written to cache, read back, base64'd and possibly encrypted, so the smaller
        // number is the more expensive path.
        assertTrue(OutgoingLimits.ATTACHMENT_MAX_BYTES < DownloadLimits.ATTACHMENT_MAX_BYTES)
    }
}
