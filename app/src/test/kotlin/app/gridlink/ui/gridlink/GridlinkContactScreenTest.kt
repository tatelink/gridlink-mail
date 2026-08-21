package app.gridlink.ui.gridlink

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.gridlink.core.jmap.model.ContactCardCustomField
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The contact card, driven by tap, with every callback the host wires recorded.
 *
 * The contract under test is the one between this screen and its caller. The hero names the person
 * (the organisation's name for a company card), their role and their primary address, and Back
 * closes once. The pill holds Copy (spoken as "Copy address", and it puts the primary address on the
 * clipboard) and Write; the accent circle is Edit; Write and Edit both hand back the SAME contact.
 * Details lists every address, then every phone, then every postal address, then the company (only
 * when it says something the role line has not) and the user's own fields, in that order; tapping an
 * address row writes to THAT address, a copy of the card with the tapped address as its email, not
 * the primary; a phone row opens the dialler with the number typed in; a postal row asks the map.
 * The Details heading is absent when there is nothing to list, the Note heading when there is no
 * note. Recent mail is the sample's for a sample contact, matched by who they are rather than by
 * address, and a tapped row hands back that message; a card nobody has written from says so plainly.
 *
 * Share left the pill by design (see the screen), so there is nothing to test there. The dialler
 * and the map are intents, read off the shadow application; each goes through `leaveOnce`, which
 * lets one hand-off out per resume, so each gets its own composition here. JVM-hosted under
 * Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkContactScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var backs = 0
    private val openedMessages = mutableListOf<GridlinkMessage>()
    private val written = mutableListOf<GridlinkContact>()
    private val edited = mutableListOf<GridlinkContact>()

    /** The context the screen composed under, so the clipboard read back is the one it wrote to. */
    private var screenContext: Context? = null

    private fun contact(
        id: String = "c1",
        given: String = "Dara",
        family: String = "Loxwell",
        role: String = "Area Manager",
        email: String = "dara@ridgeline-foods.test",
        emails: List<String> = emptyList(),
        phones: List<String> = emptyList(),
        addresses: List<String> = emptyList(),
        company: String = "",
        note: String = "",
        customFields: List<ContactCardCustomField> = emptyList(),
    ) = GridlinkContact(
        id = id,
        given = given,
        family = family,
        role = role,
        email = email,
        emails = emails,
        phones = phones,
        addresses = addresses,
        company = company,
        note = note,
        customFields = customFields,
    )

    private fun show(contact: GridlinkContact = contact(), embedded: Boolean = false) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                screenContext = LocalContext.current
                GridlinkContactScreen(
                    contact = contact,
                    onBack = { backs++ },
                    onOpenMessage = { openedMessages += it },
                    onWrite = { written += it },
                    onEdit = { edited += it },
                    embedded = embedded,
                )
            }
        }
    }

    private fun nextStartedIntent(): Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity

    // ---- the hero and the frame ----------------------------------------------------------------

    @Test
    fun hero_namesThePersonTheirRoleAndTheirAddress_andBackClosesOnce() {
        show()
        // Once in the frame's title, once across the hero: the standing screen keeps both.
        rule.onAllNodesWithText("Dara Loxwell").assertCountEquals(2)
        rule.onNodeWithText("Area Manager").assertExists()
        // The primary address is in the hero and again as the one Details row.
        rule.onAllNodesWithText("dara@ridgeline-foods.test").assertCountEquals(2)

        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
        assertTrue(written.isEmpty() && edited.isEmpty())
    }

    @Test
    fun embedded_dropsTheDuplicateTitle_andTheHeroStillNamesThem() {
        show(embedded = true)
        rule.onAllNodesWithText("Dara Loxwell").assertCountEquals(1)
    }

    @Test
    fun organisationCard_isNamedByTheOrganisation_withNoCompanyRow() {
        // The fixtures' spelling of an organisation: the whole name in family, given and company blank.
        show(
            contact = contact(
                given = "", family = "Verdant", role = "Pest control", company = "", email = "service@verdant.test",
            ),
        )
        rule.onAllNodesWithText("Verdant").assertCountEquals(2)
        rule.onNodeWithText("Pest control").assertExists()
        rule.onAllNodesWithText("Company").assertCountEquals(0)
    }

    // ---- the pill and the accent button --------------------------------------------------------

    @Test
    fun copyWriteAndEdit_copyIsSpokenInFull_andWriteAndEditHandBackTheSameContact() {
        val card = contact()
        show(contact = card)
        rule.onNodeWithContentDescription("Copy address").assertExists()
        rule.onNodeWithText("Copy").performClick()
        val clipboard = screenContext!!.getSystemService(ClipboardManager::class.java)
        assertEquals("dara@ridgeline-foods.test", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        rule.onNodeWithText("Write").performClick()
        assertEquals(listOf(card), written)
        rule.onNodeWithText("Edit").performClick()
        assertEquals(listOf(card), edited)
        assertEquals(0, backs)
    }

    // ---- details -------------------------------------------------------------------------------

    @Test
    fun details_listEveryFieldInOrder_andTheCompanyOnlyWhenItAddsSomething() {
        show(
            contact = contact(
                emails = listOf("dara@ridgeline-foods.test", "dara.loxwell@personal.test"),
                phones = listOf("+1 704 555 0142"),
                addresses = listOf("2043 Hillcrest Rd, Charlotte NC"),
                company = "Ridgeline Foods",
                customFields = listOf(ContactCardCustomField("Birthday", "June 3")),
            ),
        )
        // The section heading is uppercased content.
        rule.onNodeWithText("DETAILS").assertExists()
        rule.onAllNodesWithText("Email").assertCountEquals(2)
        rule.onNodeWithText("dara.loxwell@personal.test").assertExists()
        rule.onNodeWithText("Phone").assertExists()
        rule.onNodeWithText("+1 704 555 0142").assertExists()
        rule.onNodeWithText("Address").assertExists()
        rule.onNodeWithText("2043 Hillcrest Rd, Charlotte NC").assertExists()
        rule.onNodeWithText("Company").assertExists()
        rule.onNodeWithText("Ridgeline Foods").assertExists().assertHasNoClickAction()
        rule.onNodeWithText("Birthday").assertExists()
        rule.onNodeWithText("June 3").assertExists().assertHasNoClickAction()

        val tops = listOf(
            "dara.loxwell@personal.test", "+1 704 555 0142", "2043 Hillcrest Rd, Charlotte NC", "Ridgeline Foods",
            "June 3",
        )
            .map { rule.onNodeWithText(it).getUnclippedBoundsInRoot().top }
        assertEquals("addresses, phones, postal, company, custom, in that order: $tops", tops.sorted(), tops)
    }

    @Test
    fun details_areAbsentWithNothingToList_theCompanyCollapsesIntoTheRole_andTheNoteIsDrawn() {
        show(
            contact = contact(
                email = "", role = "Ridgeline Foods", company = "Ridgeline Foods", note = "Prefers a text first.",
            ),
        )
        rule.onAllNodesWithText("DETAILS").assertCountEquals(0)
        rule.onAllNodesWithText("Company").assertCountEquals(0)
        rule.onNodeWithText("NOTE").assertExists()
        rule.onNodeWithText("Prefers a text first.").assertExists()
        // Nobody has written from a card built by hand, and that is stated, not flagged.
        rule.onNodeWithText("RECENT MAIL").assertExists()
        rule.onNodeWithText("Nothing from this address yet.").assertExists()
    }

    @Test
    fun emailRow_writesToThatAddress_notThePrimary() {
        val card = contact(emails = listOf("dara@ridgeline-foods.test", "dara.loxwell@personal.test"))
        show(contact = card)
        rule.onNodeWithText("dara.loxwell@personal.test").performClick()
        assertEquals(listOf(card.copy(email = "dara.loxwell@personal.test")), written)
        // The same card otherwise: the caller gets the person, with the tapped address in front.
        assertEquals(card.id, written.single().id)
    }

    @Test
    fun phoneRow_opensTheDiallerWithTheNumberTypedIn() {
        show(contact = contact(phones = listOf("+1 704 555 0142")))
        rule.onNodeWithText("+1 704 555 0142").performClick()
        val intent = nextStartedIntent()
        assertNotNull("the dialler was asked", intent)
        assertEquals(Intent.ACTION_DIAL, intent!!.action)
        assertEquals("tel:%2B1%20704%20555%200142", intent.dataString)
        assertTrue("dialling is not writing", written.isEmpty())
    }

    @Test
    fun addressRow_asksTheMapForThePlace() {
        show(contact = contact(addresses = listOf("2043 Hillcrest Rd")))
        rule.onNodeWithText("2043 Hillcrest Rd").performClick()
        val intent = nextStartedIntent()
        assertNotNull("the map was asked", intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("geo:0,0?q=2043%20Hillcrest%20Rd", intent.dataString)
    }

    // ---- recent mail ---------------------------------------------------------------------------

    @Test
    fun recentMail_isTheSamplesForASampleContact_andOpensTheMessageTapped() {
        val writer = GridlinkSampleContacts.all.first { GridlinkSample.messagesFrom(it).isNotEmpty() }
        val expected = GridlinkSample.messagesFrom(writer)
        show(contact = writer)
        rule.onNodeWithText("RECENT MAIL").assertExists()
        rule.onAllNodesWithText("Nothing from this address yet.").assertCountEquals(0)
        expected.forEach { rule.onAllNodesWithText(it.subject)[0].assertExists() }
        rule.onAllNodesWithContentDescription("Has attachment")
            .assertCountEquals(expected.count { it.hasAttachment })

        val first = expected.first()
        rule.onAllNodesWithText(first.subject)[0].performClick()
        assertEquals(listOf(first), openedMessages)
        assertTrue(written.isEmpty() && edited.isEmpty())
    }
}
