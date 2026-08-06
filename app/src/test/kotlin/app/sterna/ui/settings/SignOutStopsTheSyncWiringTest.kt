package app.sterna.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT. `AccountsViewModel` is an `AndroidViewModel` and this module has neither
 * Robolectric nor instrumented tests; what this pins is the ORDER inside `signOut`, and the order
 * is what makes the sign-out stop the sync at all.
 *
 * The mechanism, since it is not visible from the function itself: nothing here cancels a sync in
 * flight — it runs in the list screen's scope, not the settings screen's. What ends it is the
 * account leaving the store, because every page a walk writes is checked against that list first
 * (`MailRepository` → `checkAccountStillConfigured`, executed by
 * `SignedOutAccountStopsWritingTest`). So `removeCascading` is not merely the line that deletes the
 * account, it is the line that stops the writers — and it has to run BEFORE the cache purge and
 * before the orphan sweep, or the walk keeps writing pages after the sweep that was supposed to
 * collect them (Codeberg #121's ghosts, re-created seconds after being removed).
 */
class SignOutStopsTheSyncWiringTest {

    private fun signOutBody(): String {
        val source = ACCOUNTS_VM.readText()
        val start = source.indexOf("fun signOut(")
        assertTrue("AccountsViewModel has no signOut() — renamed?", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val open = i
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(open, i)
    }

    private fun lines(): List<String> = signOutBody().lines().map { it.trim() }.filter { !it.startsWith("//") }

    private fun indexOfLine(line: String): Int {
        val at = lines().indexOf(line)
        assertTrue(
            "AccountsViewModel.signOut no longer contains the line:\n  $line\nits body is:\n" +
                lines().joinToString("\n"),
            at >= 0,
        )
        return at
    }

    @Test fun `the account leaves the store before anything is purged, and before the coroutine`() {
        // ⛔ The removal is synchronous and the purges are not. Moving the removal into the
        // coroutine — or below the purge, which looks tidier — reopens the window this volet
        // closed: the walk goes on writing under an id that is still configured while its cache is
        // being purged, and the sweep at the end runs before the pages it should have collected.
        val removal = indexOfLine("val removed = store.removeCascading(id).ifEmpty { toRemove }")
        val launch = indexOfLine("viewModelScope.launch {")
        val purge = indexOfLine("storage.purgeAccount(it)")
        val sweep = indexOfLine("storage.purgeOrphanedAccounts { store.accounts().map { it.id } }")

        assertTrue("the account is now removed from inside the sign-out's coroutine", removal < launch)
        assertTrue("the cache is now purged before the account is removed from the store", removal < purge)
        assertTrue("the orphan sweep now runs before the account is removed", removal < sweep)
        assertTrue("the orphan sweep no longer runs after the per-account purges (#121)", purge < sweep)
    }

    @Test fun `the per-account purge and the general sweep are both still there`() {
        // Neither is replaced by this volet: the guard stops NEW writes, it does not remove the
        // rows a previous life left behind. Dropping either would trade one leak for another.
        indexOfLine("mail.disconnectImap(it)")
        indexOfLine("storage.purgeAccount(it)")
        indexOfLine("storage.purgeOrphanedAccounts { store.accounts().map { it.id } }")
    }

    @Test fun `push is torn down and the cascade resolved while the credentials still exist`() {
        // The other order this function already depended on: UnifiedPush needs the credentials to
        // unregister, and the linked sub-accounts have to be resolved before the removal takes
        // them out (#31). A "stop the sync first" edit that hoisted the removal above these would
        // leak a push subscription per sign-out.
        val cascade = indexOfLine("val toRemove = buildList {")
        val teardown = indexOfLine("store.allCredentials().filter { it.id in toRemove }.forEach {")
        val removal = indexOfLine("val removed = store.removeCascading(id).ifEmpty { toRemove }")
        assertTrue("the cascade is now resolved after the removal", cascade < removal)
        assertTrue("push is now torn down after the credentials are gone", teardown < removal)
    }

    private companion object {
        const val ACCOUNTS_VM_PATH = "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt"

        val ACCOUNTS_VM: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, ACCOUNTS_VM_PATH).isFile }
                ?.let { File(it, ACCOUNTS_VM_PATH) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }
    }
}
