package app.gridlink.core.dav

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Stops reading at [limit] bytes by failing, not by reporting end-of-stream.
 *
 * 🔴 The distinction is the entire point. Returning `-1` at the cap would hand the XML reader a
 * truncated document, and a truncated 207 parses cleanly right up to the cut: the app would store a
 * "successful" sync that quietly dropped every item past the limit, and a stored sync-token would
 * then make the missing ones look already-seen forever. Throwing turns that into a failed sync,
 * which is recoverable, and leaves the token unwritten.
 *
 * [Content-Length][okhttp3.ResponseBody.contentLength] is checked separately, but it is advisory
 * and a chunked response has none, so the true enforcement is here.
 */
internal class LimitedInputStream(
    source: InputStream,
    private val limit: Long,
) : FilterInputStream(source) {

    private var read = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count(skipped)
        return skipped
    }

    private fun count(n: Long) {
        read += n
        if (read > limit) throw DavException("DAV response exceeded $limit bytes")
    }
}
