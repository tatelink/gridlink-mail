package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The nine "Everything" labels, and the fact that they are still SHIPPED.
 *
 * ⛔ Read this before "cleaning up" the strings below. Nothing renders them any more: "Everything" is
 * off the picker (`syncWindowChoices()`) AND an account still carrying `SyncWindow.ALL` is now shown
 * the 10 000 label, because that is what it caches (`SyncWindowPickerScaleTest`). They are kept
 * anyway, deliberately: `SyncWindow.ALL` can never be deleted (its name is on disk in account
 * records — `SyncWindowStoredNameTest`), and a string dropped today cannot be put back by a later
 * volet without another round through nine translators. Unused is cheap; missing is not.
 *
 * ⚠ Nine verbatim values. The string is ONE word per language and it is the promise that was
 * withdrawn; editing or deleting it must cost a deliberate edit here, where the reason is written
 * down. What that window now caches — 10 000, the largest offered — is pinned in `core:data`
 * (`SyncWindowScaleTest`), not here.
 */
class SyncEverythingLabelTest {

    @Test fun `the nine labels are unchanged, and none of them hedges`() {
        assertEquals(
            "settings_sync_everything changed or was deleted. It is not on the picker any more, " +
                "but an account can still CARRY that window and the row has to name it — see " +
                "SyncWindow.ALL for why the member itself may never be removed.",
            mapOf(
                "values" to "Everything",
                "values-de" to "Alles",
                "values-es" to "Todo",
                "values-fr" to "Tout",
                "values-it" to "Tutto",
                "values-nl" to "Alles",
                "values-pl" to "Wszystko",
                "values-pt" to "Tudo",
                "values-ru" to "Всё",
            ),
            labels(),
        )
    }

    @Test fun `the labels of the retired windows are still shipped too`() {
        // Same rule as above, for the same reason: 50 / 200 / 500 are off the picker and still on
        // accounts. The three ages are on the picker. None of these six may be dropped.
        val keys = listOf(
            "settings_sync_last_30_days", "settings_sync_last_90_days", "settings_sync_last_year",
            "settings_sync_50_messages", "settings_sync_200_messages", "settings_sync_500_messages",
        )
        val missing = keys.filter { key -> stringFiles().any { value(it, key) == null } }
        assertEquals("a sync-window label is missing from at least one locale", emptyList<String>(), missing)
    }

    /** locale directory name -> the `settings_sync_everything` it ships. */
    private fun labels(): Map<String, String> = stringFiles()
        .mapNotNull { file -> value(file, "settings_sync_everything")?.let { file.parentFile.name to it } }
        .toMap()

    private fun value(file: File, key: String): String? =
        Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1)

    private fun stringFiles(): List<File> = (File(root, RES).listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private companion object {
        const val RES = "app/src/main/res"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "$RES/values/strings.xml").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "resources as text and needs a working directory inside the checkout",
                )
        }
    }
}
