package app.gridlink.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠ WHAT THIS FILE IS WORTH, same caveat as [AccountStoreGateWiringTest]: it proves the SHAPE of
 * one method of `AccountStore.kt`, not its behaviour, by reading the shipped source and comparing
 * whole trimmed lines. Nothing in this module can build an `AccountStore` (it needs a `Context` and
 * the Keystore, no Robolectric here). The behaviour is executed next door by [PgpSignKeyResetTest].
 *
 * Neither is enough alone, and the gap is real: `withoutPgpSignKeys` can be perfect while the store
 * calls it on `listOf(accounts().first())`, or saves the list it was handed rather than the mapped
 * one. Both leave every behavioural test green and every account still showing a key id belonging
 * to the OpenPGP app the user just left.
 *
 * ⛔ Whole lines, as a CONTIGUOUS block: a `contains` check is blind to anything appended to a line,
 * and a per-line check is blind to a statement slipped between two.
 */
class AccountStorePgpResetWiringTest {

    @Test fun `clearPgpSignKeys saves the mapped list, over the whole account list, under the lock`() {
        // ⛔ The block starts ONE LINE ABOVE the signature, on `@Synchronized`. Every mutator of
        // this store carries it, and this one is a read-modify-write over the whole account list:
        // without it, an edit made from another thread between `accounts()` and `saveAccounts()`
        // is silently overwritten. An annotation is invisible to a block anchored at `fun`, so it
        // is the one mutation that would otherwise pass unseen.
        assertEquals(
            "clearPgpSignKeys changed shape: it no longer stores withoutPgpSignKeys(accounts()) " +
                "as a whole, or it lost @Synchronized. First case: an account keeps a key id from " +
                "the previous provider's keyring. Second: a concurrent account edit is lost, " +
                "because this reads the whole list and writes the whole list back.",
            listOf(
                "@Synchronized",
                "fun clearPgpSignKeys() {",
                "saveAccounts(withoutPgpSignKeys(accounts()))",
                "}",
            ),
            accountStoreBlock("fun clearPgpSignKeys() {", count = 4, startingLinesEarlier = 1),
        )
    }

    /** Copy of [AccountStoreGateWiringTest]'s helper, plus [startingLinesEarlier] so a block can
     *  begin above the line it is anchored on — annotations have no unique prefix of their own.
     *  See its doc for why a prefix matching anything other than exactly one line returns a
     *  message rather than nothing. */
    private fun accountStoreBlock(
        prefix: String,
        count: Int,
        startingLinesEarlier: Int = 0,
    ): List<String> {
        val path = "core/data/src/main/kotlin/app/gridlink/core/data/account/AccountStore.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val code = java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val anchor = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        val start = maxOf(anchor - startingLinesEarlier, 0)
        return code.subList(start, minOf(start + count, code.size))
    }
}
