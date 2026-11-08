package app.gridlink.ui.gridlink

import app.gridlink.core.data.account.StoredAccount
import app.gridlink.core.data.account.StoredIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's From line (Mike's request): which account and address a message goes out as, and
 * the rules every fallback follows so a stale choice degrades to the default instead of failing.
 */
class GridlinkFromTest {

    private val work = GridlinkFromAccount(
        accountId = "work",
        label = "Work",
        identities = listOf(
            GridlinkFromIdentity("Tate", "tate@work.test", "Tate, Work"),
            GridlinkFromIdentity("Support", "help@work.test"),
        ),
        defaultEmail = "help@work.test",
    )
    private val home = GridlinkFromAccount(
        accountId = "home",
        label = "home@mail.test",
        identities = listOf(GridlinkFromIdentity("", "home@mail.test", "T.")),
        defaultEmail = "home@mail.test",
    )
    private val choices = listOf(work, home)

    @Test fun `no choice sends as the first account's default identity`() {
        val sending = gridlinkResolveFrom(null, choices)!!
        assertEquals("work", sending.account.accountId)
        assertEquals("help@work.test", sending.identity.email)
        assertEquals(GridlinkFrom("work", "help@work.test"), sending.from)
    }

    @Test fun `an account with no address named uses that account's default`() {
        val sending = gridlinkResolveFrom(GridlinkFrom("home"), choices)!!
        assertEquals("home", sending.account.accountId)
        assertEquals("home@mail.test", sending.identity.email)
    }

    @Test fun `an exact address wins, matched without regard to case`() {
        val sending = gridlinkResolveFrom(GridlinkFrom("work", "TATE@work.test"), choices)!!
        assertEquals("tate@work.test", sending.identity.email)
    }

    @Test fun `an address the account no longer has falls back to its default`() {
        val sending = gridlinkResolveFrom(GridlinkFrom("work", "gone@work.test"), choices)!!
        assertEquals("help@work.test", sending.identity.email)
    }

    @Test fun `a signed-out account falls back to the active one, never to its stale address`() {
        val sending = gridlinkResolveFrom(GridlinkFrom("removed", "tate@work.test"), choices)!!
        assertEquals("work", sending.account.accountId)
        assertEquals("help@work.test", sending.identity.email)
    }

    @Test fun `nothing to choose from resolves to nothing`() {
        assertNull(gridlinkResolveFrom(GridlinkFrom("work"), emptyList()))
    }

    @Test fun `the line appears only when there is more than one address in total`() {
        assertFalse(gridlinkFromHasChoice(emptyList()))
        assertFalse(gridlinkFromHasChoice(listOf(home)))
        assertTrue(gridlinkFromHasChoice(listOf(work)))
        assertTrue(gridlinkFromHasChoice(listOf(home, home.copy(accountId = "other"))))
    }

    @Test fun `a reply from the unified inbox goes out from the account the message arrived at`() {
        val request = GridlinkComposeRequest(draft = GridlinkComposeDraft.Fresh.copy(subject = "Re: hi"))
        val answered = gridlinkSentFromOwner(request, GridlinkRowKey.encode("home", "m1"))
        assertEquals(GridlinkFrom("home"), answered.draft.from)
        assertEquals("Re: hi", answered.draft.subject)
    }

    @Test fun `a reply from a single-account list keeps the default`() {
        val request = GridlinkComposeRequest(draft = GridlinkComposeDraft.Fresh)
        assertSame(request, gridlinkSentFromOwner(request, "m1"))
    }

    @Test fun `display is name and address, or the bare address`() {
        assertEquals("Tate <tate@work.test>", gridlinkFromDisplay(work.identities[0]))
        assertEquals("home@mail.test", gridlinkFromDisplay(home.identities[0]))
    }

    // ---- the sender's half: what actually reaches the engine ----

    private val stored = StoredAccount(
        id = "work",
        server = "https://jmap.work.test",
        username = "tate@work.test",
        identities = listOf(
            StoredIdentity(id = "a", name = "Tate", email = "tate@work.test"),
            StoredIdentity(id = "b", name = "Support", email = "help@work.test"),
        ),
        defaultIdentityId = "b",
    )

    @Test fun `the sender uses the identity the composer chose`() {
        assertEquals("a", gridlinkSendingIdentity(stored, "Tate@Work.test")?.id)
    }

    @Test fun `the sender always names an identity, the user's default when the choice is gone`() {
        // Left blank, JMAP would pick the server's FIRST identity, not the default picked in Settings.
        assertEquals("b", gridlinkSendingIdentity(stored, null)?.id)
        assertEquals("b", gridlinkSendingIdentity(stored, "gone@work.test")?.id)
        assertNull(gridlinkSendingIdentity(null, "tate@work.test"))
    }

    @Test fun `a draft on the sending account is replaced in place`() {
        assertEquals("d1" to null, gridlinkDraftHome("d1", "work", "work"))
        assertEquals("d1" to null, gridlinkDraftHome("d1", null, "work"))
    }

    @Test fun `a draft moved to another account is never replaced by id on the new one`() {
        assertEquals(null to ("home" to "d1"), gridlinkDraftHome("d1", "home", "work"))
    }

    @Test fun `no stored draft means nothing to replace or retire`() {
        assertEquals(null to null, gridlinkDraftHome(null, "home", "work"))
    }
}
