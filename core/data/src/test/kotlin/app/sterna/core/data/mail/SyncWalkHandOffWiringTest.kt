package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT for the ONE line of each paginated walk that carries a page to its writer, and the
 * only thing standing between a refused write and a walk that keeps going anyway.
 *
 * The mutation this exists to kill, found by a counter-expertise and NOT caught by anything else in
 * the repository:
 *
 * ```
 * -            if (fresh.isNotEmpty()) onPage(fresh)
 * +            if (fresh.isNotEmpty()) runCatching { onPage(fresh) }
 * ```
 *
 * With it, the sign-out guard still throws and still writes nothing — every executed test stays
 * green — but the refusal never reaches the walk: it reads the folder to the end for an account
 * that no longer exists, holding a single IMAP connection for minutes, and then RECONCILES and
 * stores a cursor under the removed id. That is half of the reported symptom back.
 *
 * ⛔ Nothing executable can see it: the tests that exercise these walks feed them their own page
 * hand-off, and `SignedOutAccountStopsWritingTest` runs its own `Walk` class. The two lines below
 * are shipped code that no JVM test traverses, in two modules whose sources nothing else reads.
 *
 * Two facts per walk, because whole-line equality alone is not enough: the hand-off is EXACTLY this
 * line, and it sits directly in the loop — a `try {` opened anywhere above it inside the loop
 * changes nothing on the line itself but swallows the same refusal one level out.
 */
class SyncWalkHandOffWiringTest {

    private fun sourceOf(path: String): List<String> {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?.let { File(it, path) }
            ?: error(
                "cannot find $path from ${File("").absolutePath} — this test reads the shipped " +
                    "sources of the jmap/imap modules as text",
            )
        return file.readText().lines()
    }

    /** Brace depth just before [index], counting code lines only (a comment may hold a stray brace). */
    private fun depthAt(lines: List<String>, index: Int): Int =
        lines.take(index).map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .sumOf { line -> line.count { it == '{' } - line.count { it == '}' } }

    private fun assertHandOff(path: String, walk: String) {
        val lines = sourceOf(path)
        val calls = lines.map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { "onPage(" in it }
        assertEquals(
            "$walk no longer hands its pages over with exactly this line. A `runCatching` or a " +
                "`try` around it lets the walk carry on after the page writer refused — after a " +
                "sign-out that means the folder is read to the end, then reconciled, under an " +
                "account that no longer exists (#121).",
            listOf("if (fresh.isNotEmpty()) onPage(fresh)"),
            calls,
        )

        val handOff = lines.indexOfFirst { it.trim() == "if (fresh.isNotEmpty()) onPage(fresh)" }
        val loop = lines.take(handOff).indexOfLast { it.trim().startsWith("while (") }
        assertTrue("$walk no longer walks in a `while` loop — re-read this test before changing it", loop >= 0)
        assertEquals(
            "the page hand-off of $walk is no longer directly inside the walk's loop: something " +
                "was opened around it. A `try { … } catch` wrapping part of the loop swallows the " +
                "page writer's refusal exactly as a `runCatching` on the line itself would, and " +
                "leaves that line untouched.",
            depthAt(lines, loop) + 1,
            depthAt(lines, handOff),
        )
    }

    @Test fun `the JMAP window walk hands each page over, unguarded, straight from its loop`() {
        assertHandOff(JMAP_CLIENT, "JmapClient.queryEmailsWindow")
    }

    @Test fun `the IMAP folder walk hands each page over, unguarded, straight from its loop`() {
        assertHandOff(IMAP_CLIENT, "ImapClient.walkFolder")
    }

    private companion object {
        const val JMAP_CLIENT = "core/jmap/src/main/kotlin/app/sterna/core/jmap/JmapClient.kt"
        const val IMAP_CLIENT = "core/imap/src/main/kotlin/app/sterna/core/imap/ImapClient.kt"
    }
}
