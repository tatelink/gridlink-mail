package app.gridlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠ WHAT THIS FILE IS WORTH. It proves the SHAPE of the two `AccountsViewModel` methods behind the
 * OpenPGP-app row, by reading the shipped source and comparing whole trimmed lines. It is not a
 * behaviour test: an `AndroidViewModel` needs an `Application`, a real `AccountStore` (Context +
 * Keystore) and a DataStore, and there is no Robolectric here.
 *
 * The decisions themselves ARE executed, next door in `app.gridlink.pgp.PgpProviderChoiceTest`:
 * `PgpProviders.resolve` says which provider is in use, `PgpProviders.switchErasesKeys` says
 * whether picking one destroys the accounts' signing keys. This file only pins that the view model
 * calls them, with those arguments, in that order — which is the half no execution here can see,
 * and where three mutations used to pass unnoticed:
 *
 *  1. dropping `settings.setPgpProvider(packageName)`: the choice is never persisted (it holds
 *     until the screen is left) and the keys are cleared anyway.
 *  2. `_pgpProviders.value = installed.take(1)`: the row never appears on a phone with two
 *     providers, so the whole point of the change is gone.
 *  3. reading what is in use from `_pgpProviderInUse.value` (screen state) instead of from
 *     storage: a tap landing before the screen resolved anything reads "nothing in use" and wipes
 *     every account's signing key for a choice that changed nothing.
 *
 * ⛔ Whole lines, as CONTIGUOUS blocks: a `contains` check is blind to anything appended to a line,
 * and a per-line check is blind to a statement slipped between two — which is all it takes to
 * publish the provider list before the resolution that must accompany it.
 */
class PgpProviderChoiceWiringTest {

    @Test fun `discovery runs off the main thread and publishes both values together`() {
        // ⛔ The two assignments are consecutive and come after every suspension in the method.
        // Move `_pgpProviders.value = installed` above the resolution and the row is drawn, with
        // two providers to choose from, while nothing is in use yet.
        // The last line pins the provider in use when nothing is stored yet, and it is written
        // through `settings.` on purpose: the view model's OWN setPgpProvider would run the
        // switch-of-keyring path and clear every account's signing key for a provider that has
        // not changed. It also comes AFTER the two assignments, so pinning cannot reorder them.
        assertEquals(
            "refreshPgpAvailable changed shape. Either the package-manager query left " +
                "Dispatchers.IO (it is a binder round trip, and viewModelScope is the main " +
                "thread), or the provider list is published without the provider in use beside " +
                "it — a row drawn over a state that is not resolved yet — or the pin no longer " +
                "goes through PgpProviders.providerToPin with the STORED value and the RESOLVED " +
                "one, which is what keeps a single-provider device from switching silently, and " +
                "an explicit choice from being overwritten by the fallback.",
            listOf(
                "fun refreshPgpAvailable() {",
                "viewModelScope.launch {",
                "val installed = withContext(Dispatchers.IO) { PgpProviders.installed(getApplication()) }",
                "val stored = settings.pgpProvider.first()",
                "val inUse = PgpProviders.resolve(installed, stored)",
                "_pgpProviders.value = installed",
                "_pgpProviderInUse.value = inUse",
                "_pgpAvailable.value = pgp.isAvailable()",
                "PgpProviders.providerToPin(stored, inUse)?.let { settings.setPgpProvider(it) }",
                "}",
                "}",
            ),
            block("fun refreshPgpAvailable() {", 11),
        )
    }

    @Test fun `picking a provider persists it first, then erases keys only on a real switch`() {
        // ⛔ The order is an arbitration (WYSIWYG): between the two writes, what is displayed must
        // already be what is used. And what is in use is re-read from storage inside the coroutine
        // — never `_pgpProviderInUse.value`, which is screen state and can still be null.
        assertEquals(
            "setPgpProvider changed shape. Either the choice is no longer persisted, or what is " +
                "in use is no longer re-read from storage (reading screen state destroys every " +
                "account's signing key on a tap that changed nothing), or the erasure no longer " +
                "goes through PgpProviders.switchErasesKeys, or the two writes swapped order.",
            listOf(
                "fun setPgpProvider(packageName: String) {",
                "viewModelScope.launch {",
                "val installed = withContext(Dispatchers.IO) { PgpProviders.installed(getApplication()) }",
                "val inUse = PgpProviders.resolve(installed, settings.pgpProvider.first())",
                "settings.setPgpProvider(packageName)",
                "_pgpProviderInUse.value = packageName",
                "if (PgpProviders.switchErasesKeys(inUse, packageName)) {",
                "store.clearPgpSignKeys()",
                "refresh()",
                "}",
                "_pgpAvailable.value = pgp.isAvailable()",
                "}",
                "}",
            ),
            block("fun setPgpProvider(packageName: String) {", 13),
        )
    }

    /** [count] consecutive CODE lines of `AccountsViewModel.kt` from the single one starting with
     *  [prefix], trimmed, comments and blanks dropped. A prefix matching anything other than
     *  exactly one line returns a message, so an assertion can never pass by matching nothing. */
    private fun block(prefix: String, count: Int): List<String> {
        val path = "app/src/main/kotlin/app/gridlink/ui/settings/AccountsViewModel.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val code = java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(start, minOf(start + count, code.size))
    }
}
