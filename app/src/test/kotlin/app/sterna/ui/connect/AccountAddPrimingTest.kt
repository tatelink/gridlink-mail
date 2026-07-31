package app.sterna.ui.connect

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adding an account primed its cache BEFORE the account existed (#121).
 *
 * All three add paths built `AccountCredentials` without an id — the field defaults to `""` — and
 * handed them to the repository to "validate and prime the inbox". That priming write is
 * account-scoped: `replaceMailbox(credentials.id, …)`, `replaceAll(credentials.id, …)`. So every
 * account ever added first wrote a full page of inbox rows under `accountId = ""`. No account owns
 * that id, so nothing labels those rows and nothing ever refreshes them: they sat frozen in the
 * state they had at sign-up (unread, flagged), twinned with the real rows they share a mailbox id
 * with. That is the duplicate the reporter saw in "All inboxes" — and what Storage was already
 * calling a "removed account".
 *
 * [addAccountThenPrime] is the order that makes it impossible: prove, create, then prime with
 * credentials it stamps itself. These drive it with recording stubs — no Android, no network.
 *
 * The rollback path (a priming failure undoing a fresh account) is covered here as a decision, not
 * as a device behaviour: what the account store does under a real failure is not observed by these.
 */
class AccountAddPrimingTest {

    /** What a run of [addAccountThenPrime] did, in order. */
    private class Recorder {
        val steps = mutableListOf<String>()
        var validated: AccountCredentials? = null
        var primed: AccountCredentials? = null
        var removed: String? = null
    }

    private suspend fun attemptAdd(
        probe: AccountCredentials,
        rec: Recorder,
        validateFails: Throwable? = null,
        primeFails: Throwable? = null,
        persist: suspend () -> AddedAccount,
    ): Result<String> = runCatching {
        addAccountThenPrime(
            probe = probe,
            validate = {
                rec.steps += "validate"
                rec.validated = it
                validateFails?.let { e -> throw e }
            },
            persist = {
                rec.steps += "persist"
                persist()
            },
            prime = {
                rec.steps += "prime"
                rec.primed = it
                primeFails?.let { e -> throw e }
            },
            remove = {
                rec.steps += "remove"
                rec.removed = it
            },
        )
    }

    private fun newAccount(id: String) = suspend { AddedAccount(id, created = true) }

    // The three probes, built exactly as the three screens build them: no id anywhere.
    private val jmapPassword = AccountCredentials("https://jmap.example/", "alex@example.org", "pw")
    private val jmapToken = AccountCredentials(
        "https://api.fastmail.com/jmap/session", "alex@fastmail.com", "tok", authType = AuthType.API_TOKEN,
    )
    private val imap = AccountCredentials(
        server = "",
        username = "alex@mail.ru",
        password = "pw",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint("imap.mail.ru", 993, ConnectionSecurity.TLS),
        smtp = MailEndpoint("smtp.mail.ru", 465, ConnectionSecurity.TLS),
    )

    // -- the core rule ---------------------------------------------------------------------------

    @Test fun `no add path can prime the cache under an empty account id`() = runTest {
        for ((name, probe) in listOf("JMAP password" to jmapPassword, "JMAP token" to jmapToken, "IMAP" to imap)) {
            assertEquals(
                "$name: the probe is expected to carry no id — that is the whole trap being closed",
                "", probe.id,
            )
            val rec = Recorder()
            val result = attemptAdd(probe, rec, persist = newAccount("acct-$name"))
            assertEquals("$name: the add should have succeeded", "acct-$name", result.getOrNull())
            val primed = rec.primed
            assertNotNull("$name: the cache was never primed at all", primed)
            assertEquals(
                "$name: the cache was primed under the id `${primed!!.id}` — every row written by " +
                    "this add lands under that account id, and an empty one belongs to no account",
                "acct-$name", primed.id,
            )
        }
    }

    @Test fun `a persist that yields no id stops the add instead of priming`() = runTest {
        val rec = Recorder()
        val result = attemptAdd(jmapPassword, rec) { AddedAccount("", created = true) }
        assertTrue("a blank id must not be primed with, it can only be a bug", result.isFailure)
        assertNull("nothing may be written under a blank account id", rec.primed)
        assertEquals(listOf("validate", "persist"), rec.steps)
    }

    // -- the witness: the fix must still prime, and under the right account ----------------------

    @Test fun `a successful add proves the credentials first, then creates, then primes`() = runTest {
        val rec = Recorder()
        val result = attemptAdd(imap, rec, persist = newAccount("acct-imap"))
        assertEquals("acct-imap", result.getOrNull())
        assertEquals(
            "the order is the fix: validating after persisting would leave an account behind on a " +
                "typo, priming before persisting is the bug itself",
            listOf("validate", "persist", "prime"), rec.steps,
        )
        val primed = rec.primed!!
        assertEquals("acct-imap", primed.id)
        assertEquals("only the id changes — the connection details must survive", imap.copy(id = "acct-imap"), primed)
        assertEquals("the credentials proven are the ones typed", imap, rec.validated)
    }

    // -- a rejected sign-in still leaves nothing behind -------------------------------------------

    @Test fun `credentials that fail validation create no account and write nothing`() = runTest {
        val rec = Recorder()
        val boom = IllegalStateException("LOGIN failed")
        val result = attemptAdd(jmapPassword, rec, validateFails = boom, persist = newAccount("acct-never"))
        assertEquals("the rejection must reach the screen unchanged", boom, result.exceptionOrNull())
        assertEquals(
            "a rejected sign-in must not reach the account store at all — `Only persist once we " +
                "know they work` is the rule this fix had to keep",
            listOf("validate"), rec.steps,
        )
        assertNull(rec.primed)
    }

    @Test fun `an account created by this add is taken back out when priming fails`() = runTest {
        val rec = Recorder()
        val result = attemptAdd(
            jmapPassword, rec,
            primeFails = IllegalStateException("no mailboxes"),
            persist = newAccount("acct-half"),
        )
        assertTrue(result.isFailure)
        assertEquals(
            "a half-added account must not survive the failure that stopped it",
            "acct-half", rec.removed,
        )
        assertEquals(listOf("validate", "persist", "prime", "remove"), rec.steps)
    }

    // -- re-adding an existing account (token path) -----------------------------------------------

    @Test fun `re-adding a token account primes the account it found, without creating another`() = runTest {
        val rec = Recorder()
        val result = attemptAdd(jmapToken, rec) { AddedAccount("acct-existing", created = false) }
        assertEquals("the re-add must resolve to the account already there", "acct-existing", result.getOrNull())
        assertEquals("acct-existing", rec.primed?.id)
    }

    @Test fun `a failed priming never removes an account this add did not create`() = runTest {
        val rec = Recorder()
        attemptAdd(jmapToken, rec, primeFails = IllegalStateException("server hiccup")) {
            AddedAccount("acct-existing", created = false)
        }
        assertNull(
            "the account existed before this attempt — a transient failure while re-authenticating " +
                "it must not delete the user's account",
            rec.removed,
        )
    }
}
