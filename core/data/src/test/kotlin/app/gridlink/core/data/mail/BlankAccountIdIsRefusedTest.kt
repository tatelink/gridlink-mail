package app.gridlink.core.data.mail

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The belt behind the connect screen's braces (issue #121).
 *
 * The three add paths used to prime the cache BEFORE creating the account, with the id-less
 * credentials they had validated with. Every row `refresh` writes is tagged with `credentials.id`,
 * so each add wrote a whole inbox page under `accountId = ""`: rows the unified list drew with no
 * account chip, that nothing ever re-synced, frozen in the state the add left them in. The add
 * paths now create first — and `ConnectPrimingWiringTest` holds them there.
 *
 * This is the other half. `ConnectViewModel` is not the only caller of these two functions, and a
 * fourth add path written a year from now will not have read that lint. A precondition on the two
 * entry points that tag cached rows refuses the mistake at the layer that would commit it.
 *
 * Source-read rather than executed: `MailRepository` is not JVM-instantiable here (its constructor
 * reaches `AccountStore`, hence `getSharedPreferences`), and the module has neither Robolectric nor
 * instrumented tests. Reading the shipped body is the same procedure the DAO tests use, for the
 * same reason.
 */
class BlankAccountIdIsRefusedTest {

    @Test
    fun `refresh refuses a blank account id`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "refresh")
        assertTrue(
            "MailRepository.refresh must refuse a blank account id: every row it writes is tagged " +
                "with credentials.id, and a blank one strands them under an account that does not " +
                "exist (#121). Throw rather than skip — a silent no-op would hide the caller.\n" +
                "Body was:\n$body",
            body.contains("require(credentials.id.isNotBlank())"),
        )
    }

    @Test
    fun `syncMailbox refuses a blank local account id`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailbox")
        assertTrue(
            "MailRepository.syncMailbox must refuse a blank local account id, for the same reason " +
                "as refresh: it is the other entry point that tags cached rows with the local " +
                "account id (#121).\nBody was:\n$body",
            body.contains("require(localAccountId.isNotBlank())"),
        )
    }
}
