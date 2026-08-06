package app.sterna.core.data.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⛔ `SyncWindow` is `@Serializable` and stored in every account record BY CONSTANT NAME
 * ([StoredAccount.syncWindow]) — so the names are a persisted format, not an implementation detail.
 * kotlinx throws on a name it does not know, and `AccountStore` decodes the whole account list in
 * one go: rename one entry and every account carrying it stops decoding, which takes the accounts
 * that did NOT carry it down with the same exception.
 *
 * ⛔ And the exception never reaches anyone. `AccountStore.accounts()` decodes inside
 * `runCatching { … }.getOrDefault(emptyList())`, so the observable result of a renamed constant is
 * not a crash but **an empty account list** — after which the first setter to run writes that empty
 * list back over the real one (`saveAccounts`). Silent, and then permanent. That shape is executed
 * below rather than described, because it is the whole reason this file exists.
 *
 * ⭐ This volet WITHDRAWS four members from the settings picker (50 / 200 / 500 / "Everything") and
 * offers three new ones. Withdrawing them from the PICKER is all it does: every one of the four is
 * still in the enum, still decodes, and still carries a number, because the alternative — deleting
 * or renaming names that are written in the account records of practically every install — is the
 * silent, permanent account loss described above. One record per retired member is decoded below,
 * not one sample: a member can only be lost one at a time.
 *
 * ⚠ Whole values, never a `contains`: `"ALL" in json` would still be true of `"ALL_MAIL"`, and of
 * an account whose display name happens to contain the word.
 */
class SyncWindowStoredNameTest {

    /** Not `Json` with defaults tweaked: this is the parser [AccountStore] uses. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `the ten stored names are these, in this order`() {
        // Order is not itself persisted (names are); what this pins is the SET, whole, so a renamed
        // or dropped entry cannot pass as a reordering. Nothing here may ever be removed: the
        // picker's contents are decided by `syncWindowChoices()` and not by this list.
        assertEquals(
            listOf(
                "DAYS_30", "DAYS_90", "YEAR_1",
                "COUNT_100", "COUNT_1000", "COUNT_10000",
                "COUNT_50", "COUNT_200", "COUNT_500", "ALL",
            ),
            SyncWindow.entries.map { it.name },
        )
    }

    @Test fun `All is written to disk as the string ALL`() {
        assertEquals("\"ALL\"", json.encodeToString(SyncWindow.serializer(), SyncWindow.ALL))
        assertEquals(SyncWindow.ALL, json.decodeFromString(SyncWindow.serializer(), "\"ALL\""))
    }

    /**
     * ⭐ THE TEST OF THIS VOLET. Seven account records, exactly as an install written BEFORE the
     * scale changed has them on disk — one per member that is no longer offered, plus the three age
     * windows that are. Each must decode, and each must still hand back the number its window
     * always meant (only `ALL` changes, and only downwards, to the largest window now offered).
     *
     * The expected numbers are written out here rather than read off the enum: reading them off the
     * enum would agree with any value the enum happened to hold.
     */
    @Test fun `every window an older install may have stored still decodes, one record each`() {
        listOf(
            Triple("DAYS_30", 200, 30),
            Triple("DAYS_90", 200, 90),
            Triple("YEAR_1", 500, 365),
            Triple("COUNT_50", 50, null),
            Triple("COUNT_200", 200, null),
            Triple("COUNT_500", 500, null),
            Triple("ALL", 10_000, null),
        ).forEach { (name, limit, age) ->
            val stored = """{"id":"a1","server":"https://mail.example.org","username":"u","syncWindow":"$name"}"""
            val account = runCatching { json.decodeFromString(StoredAccount.serializer(), stored) }
            assertTrue(
                "an account record carrying \"$name\" no longer decodes. That member was removed " +
                    "or renamed, and the observable consequence on a device is NOT this failure: " +
                    "it is an empty account list, followed by the first write making it permanent.",
                account.isSuccess,
            )
            val window = account.getOrThrow().syncWindow
            assertEquals("the record decoded into the wrong window", name, window.name)
            assertEquals("$name no longer caches the number it has always meant", limit, window.limit)
            assertEquals("$name changed the kind of window it is", age, window.maxAgeDays)
        }
    }

    @Test fun `an account record written before this change still decodes, and gets the new value`() {
        // Literally what an existing install has on disk for an account set to "Everything".
        val stored = """{"id":"a1","server":"https://mail.example.org","username":"u","syncWindow":"ALL"}"""
        val account = json.decodeFromString(StoredAccount.serializer(), stored)
        assertEquals(SyncWindow.ALL, account.syncWindow)
        assertEquals(
            "an account that already asked for everything must get the largest window still " +
                "offered, without being touched and without a number of its own",
            10_000, account.syncWindow.limit,
        )
    }

    @Test fun `and it is written back unchanged`() {
        // Defaults are not encoded, so this is the whole record — a value compare, not a search.
        assertEquals(
            """{"id":"a1","server":"https://mail.example.org","username":"u","syncWindow":"ALL"}""",
            json.encodeToString(
                StoredAccount.serializer(),
                StoredAccount(id = "a1", server = "https://mail.example.org", username = "u", syncWindow = SyncWindow.ALL),
            ),
        )
    }

    @Test fun `a name this build does not know reads back as NO ACCOUNTS, not as an error`() {
        // The consequence of a rename, executed on the shipped expression. `SyncWindow` has no
        // entry called PAST_YEAR_2, exactly as a renamed ALL would have none called ALL — and the
        // record next to it is a perfectly good account that goes down with it.
        val stored = """[{"id":"a1","server":"s","username":"u","syncWindow":"PAST_YEAR_2"},""" +
            """{"id":"a2","server":"s","username":"v"}]"""

        val decoded = runCatching { json.decodeFromString<List<StoredAccount>>(stored) }
        assertTrue("kotlinx now accepts an unknown constant — this whole file's premise is gone", decoded.isFailure)

        // `AccountStore.accounts()`, verbatim in shape: the failure is swallowed and answered with
        // an empty list. Nothing above it can tell that apart from a device with no accounts, and
        // the next `saveAccounts` makes it true.
        assertEquals(emptyList<StoredAccount>(), decoded.getOrDefault(emptyList()))
        assertEquals(
            "AccountStore.accounts() no longer swallows the decode failure — if it now surfaces it, " +
                "say so in SyncWindow's KDoc, which is written against this line",
            "return runCatching { json.decodeFromString<List<StoredAccount>>(raw) }.getOrDefault(emptyList())",
            accountStoreLine("return runCatching { json.decodeFromString<List<StoredAccount>>(raw) }"),
        )
    }

    /** The one line of `AccountStore.kt` starting with [prefix], trimmed — read from the shipped
     *  source, because nothing in this module can build an `AccountStore` (it needs a `Context`). */
    private fun accountStoreLine(prefix: String): String {
        val path = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val hits = java.io.File(root, path).readLines().map { it.trim() }.filter { it.startsWith(prefix) }
        return hits.singleOrNull() ?: "«${hits.size} lines start with `$prefix`»"
    }

    @Test fun `every window round-trips under its own name`() {
        SyncWindow.entries.forEach {
            val written = json.encodeToString(SyncWindow.serializer(), it)
            assertEquals("\"${it.name}\"", written)
            assertEquals(it, json.decodeFromString(SyncWindow.serializer(), written))
        }
    }
}
