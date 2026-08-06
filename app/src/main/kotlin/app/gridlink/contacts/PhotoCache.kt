package app.gridlink.contacts

/**
 * A tiny least-recently-used cache bounded twice over: by number of entries AND by the total weight
 * of what it holds. Contact thumbnails are decoded bitmaps, so counting entries alone would not
 * bound the memory used (a 512 px photo costs sixteen times a 128 px one); counting bytes alone
 * would let a crowd of misses pile up.
 *
 * Deliberately free of Android types: the suggestion menu reloads on every keystroke, so this is
 * the piece that has to be right, and it is covered by plain JVM tests.
 *
 * Thread-safe: filled from a background thread, read during composition.
 */
class PhotoCache<T>(private val maxEntries: Int, private val maxBytes: Long) {
    private class Entry<T>(val value: T, val bytes: Long)

    // accessOrder = true, so iteration starts at the least recently used entry.
    private val map = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)
    private var bytes = 0L

    /** The cached value for [key], or null when nothing is cached (see [contains]). */
    @Synchronized
    fun get(key: String): T? = map[key]?.value

    /** Whether [key] has an entry — tells "not looked up yet" from "looked up, no photo". */
    @Synchronized
    fun contains(key: String): Boolean = map.containsKey(key)

    /** Caches [value] under [key], weighing [bytes] (a miss still costs one unit). */
    @Synchronized
    fun put(key: String, value: T, bytes: Long) {
        map.remove(key)?.let { this.bytes -= it.bytes }
        val weight = if (bytes > 0) bytes else 1L
        map[key] = Entry(value, weight)
        this.bytes += weight
        trim()
    }

    /** Number of entries held (tests, diagnostics). */
    @Synchronized
    fun size(): Int = map.size

    /** Total weight held (tests, diagnostics). */
    @Synchronized
    fun byteSize(): Long = bytes

    private fun trim() {
        val it = map.entries.iterator()
        // A single entry heavier than the whole budget is kept rather than evicted on the spot:
        // dropping it would mean decoding it again at the very next keystroke.
        while (it.hasNext() && (map.size > maxEntries || (bytes > maxBytes && map.size > 1))) {
            val eldest = it.next()
            bytes -= eldest.value.bytes
            it.remove()
        }
    }
}
