package app.gridlink.pgp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠ WHAT THIS FILE IS WORTH. It proves the SHAPE of `OpenKeychainPgpEngine.kt` and of the one
 * Android-touching line of `PgpProviders.kt`, not their behaviour: it reads the shipped sources and
 * compares whole trimmed lines. It exists because nothing here can build either of them (they need a
 * `Context`, a live binder and a real package manager, and there is no Robolectric in this repo).
 * The behaviour is proved next door by [PgpProvidersTest], which EXECUTES the decision. Neither file
 * is enough alone: the decision can be perfect and fed a hard-coded list, and the wiring can be
 * perfect around a discovery that only ever looks for OpenKeychain.
 *
 * Some of it is assertions of ABSENCE, on purpose: they survive any mutation that LENGTHENS a line,
 * which a `contains`-style check does not (bitten twice on 2026-08-04). ⛔ But absence alone is not
 * enough, and a counter-expertise proved it here: a line that names NONE of the forbidden words
 * (`if (PgpProviders.installed(appContext).none { it == PgpProviders.OPENKEYCHAIN }) return null`)
 * rebuilds the whole defect. Hence the CONTIGUOUS block assertions below: whole lines, consecutive,
 * never one line in isolation — a per-line check is blind to a statement slipped between two.
 */
class OpenKeychainPgpEngineWiringTest {

    @Test fun `no OpenKeychain package name is pinned in the engine`() {
        assertEquals(
            "the hard-coded pinning is back: a third-party OpenPGP provider becomes invisible " +
                "again, and PGP stays dead for anyone without OpenKeychain. Package names belong " +
                "to PgpProviders, which the JVM tests can execute.",
            emptyList<String>(),
            engineCode().filter { it.contains("org.sufficientlysecure.keychain") },
        )
    }

    @Test fun `the two package-manager probes are gone, so there is one discovery path`() {
        // They were TWO probes over one literal list, and isAvailable() called one while service()
        // bound through the other. Fixing one alone leaves isAvailable() answering false while the
        // bind would have succeeded — the defect reappears as "PGP is off" on a working provider.
        assertEquals(
            "providerInstalled/installedProvider are back in the engine: a second discovery path " +
                "that can disagree with the one service() binds through.",
            emptyList<String>(),
            engineCode().filter {
                it.contains("providerInstalled") || it.contains("installedProvider")
            },
        )
    }

    @Test fun `the head of service() picks a package and binds it, with nothing in between`() {
        // ⛔ A CONTIGUOUS block, and it starts at the signature: the defect's second discovery path
        // came back as a single new statement carrying none of the words the absence tests look for
        // — `if (PgpProviders.installed(appContext).none { it == PgpProviders.OPENKEYCHAIN })
        // return null`. Anywhere between the cached-connection shortcut and the bind, that line
        // makes Gridlink refuse every provider that is not OpenKeychain, exactly as before.
        //
        // Since the provider became selectable, this block also carries the ORDER the whole
        // selector depends on. The stored choice must be read and resolved BEFORE the cached
        // connection is consulted, and the cache dropped when it belongs to another package:
        // put the `connection?.takeIf { it.isBound }` shortcut back on the first line and the
        // binding is reused whatever the user picked, so switching provider does nothing at all
        // until the app is killed — the state on screen and the app called disagree, silently.
        assertEquals(
            "the head of service() changed shape. Either it no longer resolves the package from " +
                "PgpProviders + the stored choice, or the cached connection is consulted before " +
                "(or without) checking which package it is bound to, or a statement was slipped " +
                "in between. First case: a third-party provider is refused again. Second: the " +
                "provider chosen in Settings is never reached until the app restarts.",
            listOf(
                "private suspend fun service(): IOpenPgpService2? = bindMutex.withLock {",
                "val chosen = settings.pgpProvider.first()",
                "val pkg = PgpProviders.resolve(PgpProviders.installed(appContext), chosen) ?: return null",
                "if (!PgpProviders.bindingReusable(boundPackage, pkg)) {",
                "connection?.unbindFromService()",
                "connection = null",
                "boundPackage = null",
                "}",
                "connection?.takeIf { it.isBound }?.let { return it.service }",
                "val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) {",
            ),
            block(ENGINE, "private suspend fun service()", 10),
        )
    }

    @Test fun `the bound package is recorded at the moment the connection is`() {
        // The bookkeeping half of the guard above. Lose this line and `boundPackage` stays null
        // for ever: bindingReusable then answers false on every call, and the engine unbinds and
        // rebinds the provider for each single operation.
        assertEquals(
            "the package a connection is bound to is no longer recorded beside the connection " +
                "itself, so the reuse guard is deciding on stale (or absent) information.",
            listOf(
                "connection = conn",
                "boundPackage = pkg",
                "conn.bindToService()",
            ),
            block(ENGINE, "connection = conn", 3),
        )
    }

    @Test fun `isAvailable is the bind attempt itself, with no second test in front of it`() {
        assertEquals(
            "isAvailable() grew a test of its own again. Whatever it tests can disagree with what " +
                "service() binds, and the disagreement always reads as 'no PGP here'.",
            listOf(
                "override suspend fun isAvailable(): Boolean =",
                "withContext(Dispatchers.IO) { service() != null }",
            ),
            block(ENGINE, "override suspend fun isAvailable()", 2),
        )
    }

    @Test fun `discovery asks the package manager which apps answer the service intent`() {
        // The single line the whole fix rests on, and the only one no other test reads: this file's
        // engine assertions stop at `PgpProviders.installed(...)`, and PgpProvidersTest only ever
        // hands `eligible`/`preferredDefault` literal lists. A counter-expertise rewrote this body
        // as `listOf(OPENKEYCHAIN, OPENKEYCHAIN_DEBUG).filter { getPackageInfo… }` and all thirteen
        // tests stayed green while the PGPony user was back to "OpenKeychain is not installed".
        // Dropping just the `eligible(` wrapper is the smaller version of the same: APG becomes
        // selectable again — preferredDefault has no APG guard of its own — and Gridlink binds an
        // incompatible implementation.
        assertEquals(
            "discovery no longer goes through the api's service query filtered by eligible(). If " +
                "it went back to a list of package names, every third-party OpenPGP provider is " +
                "invisible again and PGP is dead for its users; if it lost eligible(), APG becomes " +
                "bindable and PGP breaks instead of working.",
            listOf(
                "fun installed(context: Context): List<String> =",
                "eligible(OpenPgpProviderUtil.getOpenPgpProviderPackages(context))",
            ),
            block(PROVIDERS, "fun installed(context: Context)", 2),
        )
    }

    /** Code lines of [path], trimmed; comments and blanks dropped (a mutation cannot hide in a comment). */
    private fun code(path: String): List<String> {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        return java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*")
            }
    }

    private fun engineCode(): List<String> = code(ENGINE)

    /**
     * [count] CONSECUTIVE code lines of [path] from the single one starting with [prefix]. A prefix
     * matching anything other than exactly one line returns a message instead, so an assertion can
     * never pass by matching nothing.
     */
    private fun block(path: String, prefix: String, count: Int): List<String> {
        val lines = code(path)
        val starts = lines.indices.filter { lines[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines of $path start with `$prefix`»")
        return lines.subList(start, minOf(start + count, lines.size))
    }

    private companion object {
        const val ENGINE = "app/src/main/kotlin/app/gridlink/pgp/OpenKeychainPgpEngine.kt"
        const val PROVIDERS = "app/src/main/kotlin/app/gridlink/pgp/PgpProviders.kt"
    }
}
