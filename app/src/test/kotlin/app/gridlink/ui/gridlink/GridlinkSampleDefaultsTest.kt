package app.gridlink.ui.gridlink

import app.gridlink.ui.FORCE_ONBOARDING_PREVIEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing may be sample data by DEFAULT.
 *
 * ## Why this is a test and not a comment
 * Every one of these started life as a convenient default that made the debug gallery work with no
 * arguments, and every one of them was therefore a value a real build inherited by saying nothing.
 * That failure mode is silent by construction: the app renders perfectly, it just renders somebody
 * else's identity. Three of the four had already grown a hand-written workaround at each call site
 * (`GridlinkHomeHost` overriding the account, the `mailto:` conversion clearing the recipient query,
 * `gridlinkWriteTo` rebuilding a draft from scratch), which is what a wrong default looks like from
 * the inside: several workarounds, no bug report, and one path nobody thought to check.
 *
 * The rule these all express: **a default must be the honest answer for a caller who knows nothing,
 * and sample data must be asked for by name.** The names are [gridlinkSampleChromeConfig],
 * [gridlinkSampleLastSyncedAt] and [GridlinkComposeDraft.FreshSuggesting].
 */
class GridlinkSampleDefaultsTest {

    /**
     * 🔴 The one that would have shipped Brandon's address in a stranger's menu. It defaulted to
     * [GRIDLINK_SAMPLE_ACCOUNT] so the gallery needed no arguments.
     */
    @Test fun `the chrome config states no account by default`() {
        assertEquals("", GridlinkChromeConfig().account)
        assertNotEquals(GRIDLINK_SAMPLE_ACCOUNT, GridlinkChromeConfig().account)
    }

    /**
     * An absent count is not a claim; a wrong count is. This defaulted to the sample's, so a build
     * that had not counted anything advertised the sample's number of drafts.
     */
    @Test fun `the chrome config claims no mailbox counts by default`() {
        assertTrue(GridlinkChromeConfig().menuCounts.isEmpty())
    }

    /** The sample identity still exists, it just has to be asked for. */
    @Test fun `the sample chrome config is the sample identity`() {
        val sample = gridlinkSampleChromeConfig()
        assertEquals(GRIDLINK_SAMPLE_ACCOUNT, sample.account)
        assertEquals(GRIDLINK_SAMPLE_MENU_COUNTS, sample.menuCounts)
    }

    /** In the past, since it is "when we last synced" and not a promise about the future. */
    @Test fun `the sample sync time is a few minutes ago`() {
        val ago = System.currentTimeMillis() - gridlinkSampleLastSyncedAt()
        assertTrue("expected a positive age, got ${ago}ms", ago > 0L)
        assertTrue("expected under an hour, got ${ago}ms", ago < 60L * 60L * 1000L)
    }

    /**
     * 🔴 The compose button opens [GridlinkComposeDraft.Fresh], so a demo query here is a demo query
     * in the TO field of every new message in the shipping app. It was "ma" for months.
     */
    @Test fun `a fresh draft has nothing typed in it`() {
        val fresh = GridlinkComposeDraft.Fresh
        assertEquals("", fresh.recipientQuery)
        assertEquals("", fresh.subject)
        assertEquals("", fresh.body)
        assertTrue(fresh.recipients.isEmpty())
        assertTrue(fresh.attachments.isEmpty())
        assertEquals(null, fresh.quoted)
        assertEquals(null, fresh.draftEmailId)
    }

    /** The suggestion frame the gallery needs, unchanged except for the query. */
    @Test fun `the suggesting draft is a fresh one with a query`() {
        val suggesting = GridlinkComposeDraft.FreshSuggesting
        assertTrue(suggesting.recipientQuery.isNotBlank())
        assertEquals(GridlinkComposeDraft.Fresh, suggesting.copy(recipientQuery = ""))
    }

    /**
     * 🔴 Its own KDoc used to say "set back to false before integrating", which is a landmine with a
     * note taped to it. It is `&& BuildConfig.DEBUG` now, so the worst a forgotten flip can do is
     * annoy a debug build. Unit tests run against the debug variant, so this asserting false also
     * proves the opt-in is genuinely off rather than being masked by the build type.
     */
    @Test fun `the onboarding preview override is off`() {
        assertFalse(FORCE_ONBOARDING_PREVIEW)
    }

    /**
     * The id counter was the list size, which reuses the id of a row still on screen as soon as
     * anything can be deleted: three contacts, delete the first, add one, and the new card takes
     * `new:contact:3` from a card the user is looking at. Two rows with one id is a Compose key
     * collision, so the tap opens the wrong card. One past the highest ISSUED cannot do that.
     */
    @Test fun `a new id never collides with one still in the list`() {
        val afterADeletion = listOf("new:contact:2", "new:contact:3")
        assertEquals("new:contact:4", gridlinkNewId("contact", afterADeletion))
    }

    /** Nothing issued yet, and ids from anywhere else are not part of this sequence. */
    @Test fun `a new id starts at one and ignores other prefixes`() {
        assertEquals("new:contact:1", gridlinkNewId("contact", emptyList()))
        assertEquals(
            "new:contact:1",
            gridlinkNewId("contact", listOf("rivera", "typed:a@b.com", "dav:9", "new:event:7")),
        )
    }

    /** Per kind, so events and contacts do not push each other's numbers along. */
    @Test fun `the sequences for events and contacts are separate`() {
        val mixed = listOf("new:contact:1", "new:contact:2", "new:event:9")
        assertEquals("new:contact:3", gridlinkNewId("contact", mixed))
        assertEquals("new:event:10", gridlinkNewId("event", mixed))
    }
}
