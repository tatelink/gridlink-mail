package app.gridlink.core.data.net

/**
 * Reads SRV records out of a raw DNS response (RFC 1035 §4, RFC 2782 §3).
 *
 * Android hands back the response as bytes and nothing else: `DnsResolver.rawQuery` does the asking,
 * and understanding the answer is the caller's job. So this is a wire-format parser, and it is
 * deliberately the only part of SRV discovery that is pure Kotlin — every rule it implements is
 * exercised by unit tests against byte arrays, with no device and no network.
 *
 * 🔴 It parses bytes that arrive from the network before the user has signed in to anything, which
 * makes malformed input the normal case to design for rather than an edge case. Every failure path
 * ends in an empty list: a truncated packet, a compression loop, a length that runs off the end, a
 * non-zero RCODE. Discovery then falls back to the conventional hostname guesses it always used.
 */
object DnsSrvParser {

    /** SRV, from the DNS RR type registry. */
    const val TYPE_SRV: Int = 33

    /** Fixed header: id, flags, and the four section counts. */
    private const val HEADER_BYTES = 12

    /**
     * How many compression pointers one name may follow. A name is at most 255 bytes, so a legal
     * one never needs this many; the cap is there because a response can point a label at itself
     * and a parser without a cap would spin forever on it.
     */
    private const val MAX_POINTER_JUMPS = 32

    /** Priority, weight, port: the fixed part of SRV RDATA before the target name. */
    private const val SRV_FIXED_BYTES = 6

    /** Type, class, TTL, RDLENGTH: the fixed part of a resource record after its name. */
    private const val RR_FIXED_BYTES = 10

    /**
     * The SRV records in [response], in the order the server listed them (see [SrvSelection.order]
     * for the order they should be TRIED in). Empty for anything that does not parse cleanly,
     * including an NXDOMAIN or SERVFAIL answer.
     */
    fun parse(response: ByteArray): List<SrvRecord> = runCatching { read(response) }.getOrDefault(emptyList())

    private fun read(response: ByteArray): List<SrvRecord> {
        if (response.size < HEADER_BYTES) return emptyList()
        // Low nibble of the second flags byte is RCODE. Anything but 0 (NOERROR) has no answers
        // worth reading, and NXDOMAIN — "this domain publishes no _jmap._tcp" — is the common one.
        if (response[3].toInt() and 0x0F != 0) return emptyList()

        val questions = u16(response, 4)
        val answers = u16(response, 6)
        var at = HEADER_BYTES
        // Questions are echoed back before the answers; each is a name plus QTYPE and QCLASS.
        repeat(questions) { at = skipName(response, at) + 4 }

        // ⚠️ Not sized from the header's count: that number is attacker-controlled and goes up to
        // 65535, so trusting it would size an allocation off a two-byte field.
        val records = ArrayList<SrvRecord>()
        try {
            repeat(answers) {
                at = skipName(response, at)
                if (at + RR_FIXED_BYTES > response.size) return records
                val type = u16(response, at)
                val rdLength = u16(response, at + 8)
                val rdStart = at + RR_FIXED_BYTES
                if (rdStart + rdLength > response.size) return records
                // An answer section can carry CNAMEs and anything else the server felt like
                // including, so the type is checked rather than assumed.
                if (type == TYPE_SRV && rdLength > SRV_FIXED_BYTES) {
                    records += SrvRecord(
                        priority = u16(response, rdStart),
                        weight = u16(response, rdStart + 2),
                        port = u16(response, rdStart + 4),
                        target = readName(response, rdStart + SRV_FIXED_BYTES),
                    )
                }
                at = rdStart + rdLength
            }
        } catch (malformed: RuntimeException) {
            // The walk stops at the first record that will not read (a header that claims more
            // answers than it carries is the usual cause). Records already read are still the
            // domain's own answer, and discarding them would turn a sloppy packet into no
            // discovery at all.
        }
        return records
    }

    /** Big-endian 16-bit value at [at]. */
    private fun u16(bytes: ByteArray, at: Int): Int {
        require(at + 1 < bytes.size) { "read past end of response" }
        return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    }

    /**
     * The dotted name at [at], following compression pointers.
     *
     * ⚠️ A SRV target is allowed to be compressed against an earlier name in the same packet, which
     * is why this cannot just walk labels forward. The root name (a single zero byte) yields "",
     * which is how RFC 2782's "service not available" target `.` reaches [SrvSelection.order].
     */
    private fun readName(bytes: ByteArray, at: Int): String {
        val labels = mutableListOf<String>()
        walk(bytes, at) { start, length -> labels += String(bytes, start, length, Charsets.US_ASCII) }
        return labels.joinToString(".")
    }

    /** Where the record continues after the name at [at]: past the terminator, or past a pointer. */
    private fun skipName(bytes: ByteArray, at: Int): Int = walk(bytes, at) { _, _ -> }

    /**
     * Walks the label chain at [at], reporting each label, and returns the offset just past the name
     * **as it was written here** — which for a compressed name is two bytes on, not wherever the
     * pointer led. Getting that wrong would resume parsing in the middle of an earlier record.
     */
    private inline fun walk(bytes: ByteArray, at: Int, onLabel: (start: Int, length: Int) -> Unit): Int {
        var cursor = at
        var resume = -1
        var jumps = 0
        while (true) {
            require(cursor in bytes.indices) { "name runs past end of response" }
            val length = bytes[cursor].toInt() and 0xFF
            when {
                length == 0 -> {
                    cursor++
                    return if (resume < 0) cursor else resume
                }
                length and 0xC0 == 0xC0 -> {
                    // Two-byte pointer: the low 14 bits are an offset from the start of the message.
                    require(cursor + 1 < bytes.size) { "truncated compression pointer" }
                    val pointer = ((length and 0x3F) shl 8) or (bytes[cursor + 1].toInt() and 0xFF)
                    require(pointer < cursor) { "compression pointer does not point backwards" }
                    if (resume < 0) resume = cursor + 2
                    require(++jumps <= MAX_POINTER_JUMPS) { "too many compression pointers" }
                    cursor = pointer
                }
                else -> {
                    require(cursor + 1 + length <= bytes.size) { "label runs past end of response" }
                    onLabel(cursor + 1, length)
                    cursor += 1 + length
                }
            }
        }
    }
}
