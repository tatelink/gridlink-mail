package app.gridlink.core.jmap

/**
 * Something a message asked us to load is bigger than we are willing to hold in memory.
 * Carries the sizes so the caller can say which limit was hit; the user-facing wording is the
 * caller's business (translated strings live in the app module).
 */
class ContentTooLargeException(
    message: String,
    /** Announced size in bytes, or -1 when the server didn't say. */
    val bytes: Long = -1,
    val maxBytes: Long = -1,
) : Exception(message)

/**
 * Ceilings for what a single message may pull into memory over JMAP.
 *
 * JMAP downloads have no framing of their own: `downloadBlob` buffers the whole response, so
 * without a ceiling one message dictates the app's memory. The IMAP side has had this shape for
 * a while (`ImapParser.MAX_LITERAL`); these are its JMAP counterpart.
 *
 * The two limits differ because the two paths differ in consent: an inline image is fetched
 * automatically the moment a message is opened, and then base64-encoded and cached (roughly four
 * times its own weight), so it gets the tighter ceiling. An attachment is only fetched when the
 * user taps it.
 */
object DownloadLimits {

    /** Inline (`cid:`) image fetched without the user asking: 10 MB. */
    const val INLINE_IMAGE_MAX_BYTES = 10L * 1024 * 1024

    /** Attachment the user explicitly opened: 50 MB. */
    const val ATTACHMENT_MAX_BYTES = 50L * 1024 * 1024

    /**
     * Calendar invite fetched on open to draw the event card: 2 MB. Real invitations are a few
     * kilobytes and the reader refuses to parse one over 1 MiB anyway, so downloading more than
     * this only buys the buffer that would run the app out of memory.
     */
    const val CALENDAR_MAX_BYTES = 2L * 1024 * 1024

    /**
     * Whether a part announcing [size] bytes may be downloaded under [maxBytes]. A size of 0 (or
     * negative) means the server didn't say — those pass here and are caught while reading, by
     * the same ceiling.
     */
    fun allows(size: Long, maxBytes: Long): Boolean = size <= 0L || size <= maxBytes

    /**
     * Refuse a part announcing [size] bytes when it is over [maxBytes], throwing the
     * [ContentTooLargeException] the caller already surfaces. The single choke point for the cap,
     * so it can't be applied on one protocol and skipped on another: it was skipped on IMAP (whose
     * BODYSTRUCTURE reports the same size) because the check sat behind the IMAP early return.
     */
    fun enforce(size: Long, maxBytes: Long) {
        if (!allows(size, maxBytes)) {
            throw ContentTooLargeException(
                "Part is $size bytes, over the $maxBytes limit.",
                bytes = size,
                maxBytes = maxBytes,
            )
        }
    }
}

/**
 * The ceiling on a file the user picks to SEND, which [DownloadLimits] never covered.
 *
 * 🔴 Its own object rather than another constant in [DownloadLimits], because it is not a download
 * and the two are enforced in opposite directions: a download is bounded by what a server hands us,
 * this is bounded by what a file picker hands us. Sharing [ContentTooLargeException] is deliberate,
 * though: to the reader it is the same sentence either way.
 *
 * ⚠️ An outgoing file is the more expensive of the two despite the smaller number. It is read whole
 * into a ByteArray, written to a cache file, read back to build the MIME entity, then base64'd at
 * about 4/3 its own weight, and with PGP on it is signed or encrypted in memory as well. The inbound
 * 50 MB ceiling buys one copy; this one is paid for several times over.
 */
object OutgoingLimits {

    /**
     * A picked attachment: 25 MB.
     *
     * Chosen against what will actually be delivered rather than against what the phone can hold.
     * 25 MB is the practical ceiling most mail servers enforce on a message, so a larger file is
     * one the recipient's server would bounce after the upload had already been paid for. Refusing
     * at the picker says so in the one moment the user can still do something about it.
     */
    const val ATTACHMENT_MAX_BYTES = 25L * 1024 * 1024

    /**
     * Read at most [maxBytes] from [stream], throwing [ContentTooLargeException] the moment it is
     * exceeded rather than after buffering the rest.
     *
     * 🔴 This is the check that actually holds. A content provider's declared SIZE is a hint: it is
     * absent for many providers, and a hostile or simply wrong one can under-report. Enforcing only
     * on the declared size means the one file crafted to lie is the one file that gets to allocate
     * without limit, which is the whole failure mode being closed here.
     */
    fun readAtMost(stream: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw ContentTooLargeException(
                    "File exceeds the $maxBytes limit.",
                    bytes = -1,
                    maxBytes = maxBytes,
                )
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
