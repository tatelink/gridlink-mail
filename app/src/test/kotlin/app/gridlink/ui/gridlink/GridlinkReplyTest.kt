package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reply and reply-all addressing and threading, as pure functions of the original: who goes in TO
 * and CC, that the sending account's own addresses never do, and the In-Reply-To / References a
 * reply carries (RFC 5322 section 3.6.4).
 */
class GridlinkReplyTest {

    private fun a(email: String, name: String = "") = GridlinkAddress(name, email)

    private val own = setOf("avery@gridlink.me", "alias@gridlink.me")

    private fun original(
        from: List<GridlinkAddress> = listOf(a("sam@example.com", "Sam")),
        to: List<GridlinkAddress> = listOf(a("Avery@gridlink.me"), a("kim@example.com")),
        cc: List<GridlinkAddress> = listOf(a("lee@example.com"), a("alias@gridlink.me"), a("kim@example.com")),
    ) = GridlinkReplyOriginal(
        messageId = listOf("m3@example.com"),
        references = listOf("m1@example.com", "m2@example.com"),
        from = from,
        to = to,
        cc = cc,
        ownAddresses = own,
    )

    private fun emails(list: List<GridlinkAddress>) = list.map { it.email }

    @Test
    fun threading_inReplyToIsTheOriginalsId_andReferencesEndWithIt() {
        val (inReplyTo, references) = gridlinkReplyThreading(original())
        assertEquals(listOf("m3@example.com"), inReplyTo)
        assertEquals(listOf("m1@example.com", "m2@example.com", "m3@example.com"), references)
    }

    @Test
    fun threading_anOriginalWithNoReferencesStartsThemWithItsOwnId() {
        val (inReplyTo, references) = gridlinkReplyThreading(original().copy(references = emptyList()))
        assertEquals(listOf("m3@example.com"), inReplyTo)
        assertEquals(listOf("m3@example.com"), references)
    }

    @Test
    fun reply_goesToTheSenderOnly() {
        val r = gridlinkReplyAddressing(original())
        assertEquals(listOf("sam@example.com"), emails(r.to))
        assertEquals(emptyList<String>(), emails(r.cc))
    }

    @Test
    fun reply_toYourOwnMessage_goesToThePeopleYouWroteTo() {
        val r = gridlinkReplyAddressing(original(from = listOf(a("avery@gridlink.me"))))
        assertEquals(listOf("kim@example.com"), emails(r.to))
    }

    @Test
    fun replyAll_senderPlusTo_ccStaysCc_ownAddressesAndDuplicatesDropped() {
        val r = gridlinkReplyAllAddressing(original())
        assertEquals(listOf("sam@example.com", "kim@example.com"), emails(r.to))
        // kim is already in TO, alias@ is one of the account's own identities.
        assertEquals(listOf("lee@example.com"), emails(r.cc))
    }

    @Test
    fun replyAll_whenYouWereTheOnlyTo_ccMovesUpRatherThanLeavingToEmpty() {
        val r = gridlinkReplyAllAddressing(
            original(
                from = listOf(a("avery@gridlink.me")),
                to = listOf(a("avery@gridlink.me")),
                cc = listOf(a("lee@example.com")),
            ),
        )
        assertEquals(listOf("lee@example.com"), emails(r.to))
        assertEquals(emptyList<String>(), emails(r.cc))
    }

    @Test
    fun replyAll_aNoteToYourself_fallsBackToThePlainReply() {
        val r = gridlinkReplyAllAddressing(
            original(from = listOf(a("avery@gridlink.me")), to = listOf(a("avery@gridlink.me")), cc = emptyList()),
        )
        assertEquals(listOf("avery@gridlink.me"), emails(r.to))
    }

    @Test
    fun addressedReply_fillsToCcThreading_andAnswersFromTheAliasItWasSentTo() {
        val rowBuilt = GridlinkComposeRequest(
            GridlinkComposeDraft.Fresh.copy(title = "Reply all", from = GridlinkFrom("acct")),
        )
        val draft = gridlinkAddressedReply(
            rowBuilt,
            original(to = listOf(a("alias@gridlink.me"), a("kim@example.com")), cc = emptyList()),
            all = true,
        ).draft
        assertEquals(listOf("sam@example.com", "kim@example.com"), draft.recipients.map { it.email })
        assertEquals("Sam", draft.recipients.first().displayName)
        assertEquals(listOf("m3@example.com"), draft.inReplyTo)
        assertEquals(GridlinkFrom("acct", "alias@gridlink.me"), draft.from)
    }

    @Test
    fun addressedReply_leavesAnExplicitFromAlone() {
        val from = GridlinkFrom("acct", "avery@gridlink.me")
        val rowBuilt = GridlinkComposeRequest(GridlinkComposeDraft.Fresh.copy(from = from))
        assertEquals(from, gridlinkAddressedReply(rowBuilt, original(), all = false).draft.from)
    }
}
