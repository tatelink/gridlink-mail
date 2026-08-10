package app.gridlink.core.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [DnsSrvParser] reads bytes that arrive from the network before the user has signed in to
 * anything, so the tests that matter most here are the malformed ones. Every one of them has to end
 * in an empty list rather than an exception or a hang, because the caller's fallback (guess the
 * hostnames, as before) is a perfectly good outcome and a crash on the sign-in screen is not.
 */
class DnsSrvParserTest {

    @Test
    fun `reads a record's priority, weight, port and target`() {
        val response = response(answers = listOf(srv(10, 5, 443, "jmap.example.com")))
        assertEquals(
            listOf(SrvRecord(priority = 10, weight = 5, port = 443, target = "jmap.example.com")),
            DnsSrvParser.parse(response),
        )
    }

    @Test
    fun `reads every record in the answer section`() {
        val response = response(
            answers = listOf(
                srv(10, 0, 443, "a.example.com"),
                srv(20, 0, 8443, "b.example.com"),
            ),
        )
        assertEquals(listOf(443, 8443), DnsSrvParser.parse(response).map { it.port })
    }

    @Test
    fun `follows a compression pointer in the target name`() {
        // 🔴 The target is allowed to be written as "mail" + a pointer at the question's
        // "example.com", and real servers do exactly this. A parser that walked labels forward
        // without following pointers would read the name as "mail" and probe the wrong host.
        val target = byteArrayOf(4) + "mail".toByteArray() + byteArrayOf(0xC0.toByte(), EXAMPLE_COM_OFFSET.toByte())
        val rdata = u16(10) + u16(0) + u16(443) + target
        assertEquals("mail.example.com", DnsSrvParser.parse(response(answers = listOf(record(rdata)))).single().target)
    }

    @Test
    fun `skips records that are not SRV`() {
        // An answer section carries whatever the server chose to include; only type 33 is ours.
        val cname = record(name("elsewhere.example.com"), type = 5)
        val response = response(answers = listOf(cname, srv(10, 0, 443, "jmap.example.com")))
        assertEquals(listOf("jmap.example.com"), DnsSrvParser.parse(response).map { it.target })
    }

    @Test
    fun `a root target survives parsing as an empty name`() {
        // RFC 2782's "service not available" marker. It has to make it through the parser intact so
        // that SrvSelection is the one place that decides what it means.
        assertEquals("", DnsSrvParser.parse(response(answers = listOf(srv(0, 0, 0, "")))).single().target)
    }

    @Test
    fun `an error response yields nothing`() {
        // NXDOMAIN: the ordinary answer for a domain that publishes no SRV record at all.
        val response = response(rcode = 3, answers = listOf(srv(10, 0, 443, "jmap.example.com")))
        assertTrue(DnsSrvParser.parse(response).isEmpty())
    }

    @Test
    fun `a truncated response yields nothing`() {
        val full = response(answers = listOf(srv(10, 0, 443, "jmap.example.com")))
        // Every prefix of a valid response, including the empty one and ones cut mid-name.
        for (length in 0 until full.size) {
            val cut = full.copyOf(length)
            assertTrue("prefix of $length bytes should parse to nothing", DnsSrvParser.parse(cut).isEmpty())
        }
    }

    @Test
    fun `a pointer that does not point backwards yields nothing instead of looping`() {
        // A name may only point at a name earlier in the message. A pointer at itself, or forward,
        // is the shape that spins a naive parser forever; this test hangs rather than fails if the
        // guard is ever removed, which is the loudest failure available.
        val body = ByteArrayOutputStream()
        body.write(u16(10)); body.write(u16(0)); body.write(u16(443))
        val selfOffset = HEADER_AND_QUESTION_BYTES + RR_HEADER_BYTES + body.size()
        body.write(byteArrayOf(0xC0.toByte(), selfOffset.toByte()))
        assertTrue(DnsSrvParser.parse(response(answers = listOf(record(body.toByteArray())))).isEmpty())
    }

    @Test
    fun `a record claiming more data than the packet holds yields nothing`() {
        val rdata = u16(10) + u16(0) + u16(443) + name("jmap.example.com")
        val lying = record(rdata, rdLength = rdata.size + 500)
        assertTrue(DnsSrvParser.parse(response(answers = listOf(lying))).isEmpty())
    }

    @Test
    fun `an answer count larger than the records present yields what is there`() {
        // A count the packet does not back up must not read off the end; the records that did parse
        // are still usable, and a candidate host is a candidate host.
        val response = response(answerCount = 9, answers = listOf(srv(10, 0, 443, "jmap.example.com")))
        assertEquals(listOf("jmap.example.com"), DnsSrvParser.parse(response).map { it.target })
    }

    // ---- fixtures -------------------------------------------------------------------------

    private companion object {
        const val QUESTION = "_jmap._tcp.example.com"

        /** Offset of the "example.com" labels inside [QUESTION] as written at offset 12. */
        const val EXAMPLE_COM_OFFSET = 12 + 6 + 5

        /** 12-byte header plus the question: name, QTYPE, QCLASS. */
        const val HEADER_AND_QUESTION_BYTES = 12 + (QUESTION.length + 2) + 4

        /** A record's owner pointer, type, class, TTL and RDLENGTH. */
        const val RR_HEADER_BYTES = 2 + 2 + 2 + 4 + 2
    }

    private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    /** A DNS name in wire form. An empty [value] is the root name, a lone zero byte. */
    private fun name(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        if (value.isNotEmpty()) {
            value.split('.').forEach { label ->
                out.write(label.length)
                out.write(label.toByteArray(Charsets.US_ASCII))
            }
        }
        out.write(0)
        return out.toByteArray()
    }

    /** A resource record whose owner is a pointer at the question name, as servers write it. */
    private fun record(rdata: ByteArray, type: Int = DnsSrvParser.TYPE_SRV, rdLength: Int = rdata.size): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xC0.toByte(), 12))
        out.write(u16(type))
        out.write(u16(1))
        out.write(byteArrayOf(0, 0, 1, 44)) // TTL 300
        out.write(u16(rdLength))
        out.write(rdata)
        return out.toByteArray()
    }

    private fun srv(priority: Int, weight: Int, port: Int, target: String): ByteArray =
        record(u16(priority) + u16(weight) + u16(port) + name(target))

    private fun response(
        rcode: Int = 0,
        answers: List<ByteArray> = emptyList(),
        answerCount: Int = answers.size,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u16(0x1234))
        out.write(u16(0x8180 or rcode))
        out.write(u16(1))
        out.write(u16(answerCount))
        out.write(u16(0))
        out.write(u16(0))
        out.write(name(QUESTION))
        out.write(u16(DnsSrvParser.TYPE_SRV))
        out.write(u16(1))
        answers.forEach { out.write(it) }
        return out.toByteArray()
    }
}
