package app.gridlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two halves of one sentence — the "Use OpenPGP" subtitle, which with a single provider installed
 * is the only place on the account screen that names the app signing and decrypting the mail (the
 * picker row appears from two on).
 *
 * The decisions ARE executed: [pgpProviderDisplayName] says what an OpenPGP app is called when it
 * declares no name of its own — the fallback that really reaches the screen — and
 * [pgpSubtitleProviderName] says what the sentence gets. Both are run here on values written by
 * hand, never derived from the code under test.
 *
 * The second half cannot be: the call site is a `@Composable` inside `PgpAccountSection`, which
 * needs a `Context`, a `PackageManager` and an `AccountsViewModel` (Application + Keystore-backed
 * store + DataStore), and there is no Robolectric here. So it is pinned by reading the shipped
 * source and comparing whole trimmed lines — a last resort, and it pins the ARGUMENTS, because the
 * defect it guards is precisely the wrong one being passed: handing the raw package name would put
 * `org.sufficientlysecure.keychain` on the screen, and handing nothing would leave the sentence
 * naming no app at all.
 */
class PgpSubtitleNameTest {

    @Test fun `an app that declares a name is called by it`() {
        assertEquals(
            "the screen no longer calls an OpenPGP app what it calls itself.",
            "PGPony",
            pgpProviderDisplayName("PGPony", "com.pgpony.android"),
        )
    }

    /**
     * ⛔ THE FALLBACK THE READER ACTUALLY MEETS, and the one mutation that used to pass unseen:
     * `?: "OpenKeychain"` in place of `?: packageName` reads like a tidy-up and puts one particular
     * app back on the screen of someone signing with another — #151, restored, in every language at
     * once, without touching a single string resource.
     */
    @Test fun `an app that declares no name is called by its package, never by another app's name`() {
        assertEquals(
            "a provider that declares no launcher label is named something other than itself. " +
                "If that is a hard-coded app name, the subtitle and the provider row now claim an " +
                "app that is not the one Gridlink binds.",
            "com.pgpony.android",
            pgpProviderDisplayName(null, "com.pgpony.android"),
        )
    }

    @Test fun `the fallback is the package it was given, not one it prefers`() {
        assertEquals(
            "the fallback ignores the package it was handed.",
            "org.sufficientlysecure.keychain",
            pgpProviderDisplayName(null, "org.sufficientlysecure.keychain"),
        )
    }

    @Test fun `the label decided above is what the sentence gets`() {
        assertEquals(
            "the subtitle no longer says which app is in use; the reader cannot tell what signs " +
                "her mail.",
            "PGPony",
            pgpSubtitleProviderName("PGPony"),
        )
    }

    /**
     * ⚠ A STATE PRODUCTION DOES NOT PRODUCE, and this test says so rather than pretending to guard
     * the screen: `pgpProviderLabel` never returns null, and the branch that calls this is drawn
     * only when `pgpAvailable` is true, which the view model publishes with the provider in use.
     * The nullable type still owes an answer, and it may not be an app name.
     */
    @Test fun `with nothing resolved at all the sentence names the standard, never an app`() {
        assertEquals(
            "the unreachable fallback names an app; if it ever becomes reachable the subtitle " +
                "claims a provider Gridlink may not be binding.",
            "OpenPGP",
            pgpSubtitleProviderName(null),
        )
    }

    @Test fun `the readable name is remembered on the package, not read on every frame`() {
        // Reading a provider's own name is a package-manager query plus the loading of a third
        // party APK's resources. Bare in the subtitle it runs on the main thread on every
        // recomposition — the very thing PgpProviderChoiceWiringTest keeps off it in the view model.
        assertEquals(
            "the provider's name is no longer remembered on the package in use: a package-manager " +
                "query and a third-party resource load are back on the main thread, once per " +
                "recomposition. Keyed on anything else (or on nothing) it is either stale or free-" +
                "running.",
            listOf("val pgpInUseLabel = remember(pgpInUse) { pgpInUse?.let { pgpProviderLabel(context, it) } }"),
            block("val pgpInUseLabel =", 1),
        )
    }

    @Test fun `the label helper delegates its fallback instead of re-deciding it`() {
        // Without this, the executed tests above guard a function the screen could stop calling:
        // re-inlining `?: "OpenKeychain"` inside pgpProviderLabel leaves them all green.
        assertEquals(
            "pgpProviderLabel stopped delegating to pgpProviderDisplayName, so the fallback tested " +
                "next door is no longer the fallback the screen uses.",
            listOf(
                "private fun pgpProviderLabel(context: Context, packageName: String): String =",
                "pgpProviderDisplayName(",
                "OpenPgpProviderUtil.getOpenPgpProviderName(context.packageManager, packageName),",
                "packageName,",
                ")",
            ),
            block("private fun pgpProviderLabel(", 5),
        )
    }

    @Test fun `the subtitle is handed the provider's label, not its package name`() {
        assertEquals(
            "the \"Use OpenPGP\" subtitle changed shape. Either it stopped being given an " +
                "argument (the sentence then names no app, and with one provider installed " +
                "nothing else on the screen does), or it is given the package name instead of " +
                "pgpProviderLabel (the screen reads `org.sufficientlysecure.keychain`), or the " +
                "no-provider branch stopped using settings_pgp_provider_required.",
            listOf(
                "subtitle = if (pgpAvailable) {",
                "stringResource(",
                "R.string.settings_pgp_enable_subtitle,",
                "pgpSubtitleProviderName(pgpInUseLabel),",
                ")",
                "} else {",
                "stringResource(R.string.settings_pgp_provider_required)",
                "},",
            ),
            block("subtitle = if (pgpAvailable) {", 8),
        )
    }

    /** [count] consecutive CODE lines of `SettingsScreen.kt` from the single one starting with
     *  [prefix], trimmed, comments and blanks dropped. A prefix matching anything other than
     *  exactly one line returns a message, so an assertion can never pass by matching nothing. */
    private fun block(prefix: String, count: Int): List<String> {
        val path = "app/src/main/kotlin/app/gridlink/ui/settings/SettingsScreen.kt"
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
