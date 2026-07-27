package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure "is this account shared?" rule and the labels it feeds (issue #31). Delegation is already
 * persisted by discovery — a sub-account carries its login's id in [StoredAccount.loginId] and the
 * name the JMAP session advertises for it — so the UI can say so without a single extra request.
 */
class SharedAccountTest {

    private fun login() = StoredAccount(
        id = "login-uuid",
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        jmapAccountId = "s",
    )

    private fun delegated(id: String = "jordan-uuid", name: String = "jordan.lee@example.org") =
        StoredAccount(
            id = id,
            server = "https://mail.example.org",
            // A sub-account carries the LOGIN's address as username; its own is the account name.
            username = "alex.rivera@example.org",
            accountName = name,
            loginId = "login-uuid",
            jmapAccountId = "u",
        )

    @Test fun aFullAccountIsNotShared() {
        assertFalse(login().isShared)
    }

    @Test fun aDelegatedSubAccountIsShared() {
        assertTrue(delegated().isShared)
    }

    @Test fun aBlankLoginIdNamesNoLoginSoItIsNotShared() {
        // Neither an absent nor an empty/whitespace loginId points at a login to borrow from.
        assertFalse(login().copy(loginId = null).isShared)
        assertFalse(login().copy(loginId = "").isShared)
        assertFalse(login().copy(loginId = "   ").isShared)
    }

    @Test fun sharedLabelsAreListedUnderTheirLogin() {
        val accounts = listOf(login(), delegated(), delegated("team-uuid", "team@example.org"))

        assertEquals(
            listOf("jordan.lee@example.org", "team@example.org"),
            StoredAccount.sharedLabelsUnder(login(), accounts),
        )
    }

    @Test fun anotherLoginsSubAccountsAreNotListed() {
        val other = login().copy(id = "other-login-uuid", username = "sam@example.org")

        assertEquals(emptyList<String>(), StoredAccount.sharedLabelsUnder(other, listOf(login(), delegated())))
    }

    @Test fun aLoginWithoutDelegationHasNothingToMention() {
        assertEquals(emptyList<String>(), StoredAccount.sharedLabelsUnder(login(), listOf(login())))
    }

    @Test fun aLoginsSettingsShortcutOpensItself() {
        assertEquals("login-uuid", StoredAccount.settingsTargetId(login(), listOf(login(), delegated())))
    }

    @Test fun aSharedAccountsSettingsShortcutOpensItsLogin() {
        assertEquals("login-uuid", StoredAccount.settingsTargetId(delegated(), listOf(login(), delegated())))
    }

    @Test fun aBlankOrDanglingLoginFallsBackToTheAccountItself() {
        // Never resolve to nothing: a dead shortcut is worse than one that opens the account.
        val blank = delegated().copy(loginId = "  ")
        val dangling = delegated().copy(loginId = "gone-uuid")

        assertEquals("jordan-uuid", StoredAccount.settingsTargetId(blank, listOf(login(), blank)))
        assertEquals("jordan-uuid", StoredAccount.settingsTargetId(dangling, listOf(dangling)))
    }

    @Test fun aSharedAccountNeverNestsFurther() {
        // Delegated accounts get no row of their own in the accounts screen, so they list nothing.
        assertEquals(emptyList<String>(), StoredAccount.sharedLabelsUnder(delegated(), listOf(login(), delegated())))
    }
}
