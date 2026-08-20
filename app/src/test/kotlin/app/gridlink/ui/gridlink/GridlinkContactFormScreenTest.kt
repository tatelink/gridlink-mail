package app.gridlink.ui.gridlink

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.jmap.model.ContactCardCustomField
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The address book's contact form, driven the way a user drives it.
 *
 * The contract under test (see [GridlinkContactFormScreen.onSave]): the form hands over its exact
 * words, untrimmed, spare rows and all, because normalising is the recipient's job and doing it
 * twice hides diffs; a card needs a name or a company before it can be filed; and an email that
 * the composer would refuse to send to is refused here too, with the same matcher, so the address
 * book can never accept what the composer will not.
 *
 * Runs on the JVM under Robolectric (see `src/test/resources/robolectric.properties`), no device.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkContactFormScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val saved = mutableListOf<ContactEdit>()
    private var closed = 0

    private fun show(
        initial: ContactEdit? = null,
        saving: Boolean = false,
        failure: String? = null,
    ) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkContactFormScreen(
                    title = if (initial == null) "New contact" else "Edit contact",
                    initial = initial,
                    onSave = { saved += it },
                    onClose = { closed++ },
                    saving = saving,
                    failure = failure,
                )
            }
        }
    }

    /**
     * The typed rows in screen order. On a blank form: first name, last name, company, title, one
     * email, one phone, one custom label, one custom value, note. A row that gets text grows a
     * spare after it, so indices past that point shift; the tests type top-down and re-index.
     */
    private fun fields() = rule.onAllNodes(hasSetTextAction())
    private fun field(index: Int) = fields()[index].performScrollTo()

    private fun save(label: String = "Save") = rule.onNode(hasText(label) and hasClickAction())

    @Test
    fun blankForm_cannotSave_untilThereIsSomethingToFileUnder() {
        show()
        save().assertIsNotEnabled()
        rule.onNodeWithText(HINT_FILE).assertExists()

        field(COMPANY).performTextInput("Acme")
        save().assertIsEnabled()
        rule.onNodeWithText(HINT_FILE).assertDoesNotExist()

        field(COMPANY).performTextClearance()
        save().assertIsNotEnabled()
        field(GIVEN).performTextInput("Ada")
        save().assertIsEnabled()
    }

    @Test
    fun anEmailTheComposerWouldRefuse_blocksTheSave_untilFixed() {
        show()
        field(GIVEN).performTextInput("Ada")
        field(EMAIL).performTextInput("ada-at-gridlink")
        save().assertIsNotEnabled()
        rule.onNodeWithText(HINT_EMAIL).assertExists()

        field(EMAIL).performTextClearance()
        field(EMAIL).performTextInput("ada@gridlink.me")
        save().assertIsEnabled()
        rule.onNodeWithText(HINT_EMAIL).assertDoesNotExist()
    }

    @Test
    fun save_handsOverTheExactWords_spareRowsIncluded() {
        show()
        field(GIVEN).performTextInput("  Ada ")
        field(FAMILY).performTextInput("Lovelace")
        field(TITLE).performTextInput("Analyst")
        field(EMAIL).performTextInput("ada@gridlink.me")
        // Typing in the last email row grows a spare under it, which is the "add another"
        // affordance, and pushes every later row down by one.
        rule.onNodeWithText("Add email").assertExists()
        field(PHONE + 1).performTextInput("+1 555 0100")
        rule.onNodeWithText("Add phone").assertExists()
        // Phone spare pushed the rest down one more: custom label is now at +2.
        field(CUSTOM_LABEL + 2).performTextInput("Birthday")
        field(CUSTOM_VALUE + 2).performTextInput("10 Dec")
        field(NOTE + 4).performTextInput("Met at the conference. ")
        save().performClick()

        assertEquals(
            ContactEdit(
                given = "  Ada ",
                family = "Lovelace",
                company = "",
                title = "Analyst",
                emails = listOf("ada@gridlink.me", ""),
                phones = listOf("+1 555 0100", ""),
                note = "Met at the conference. ",
                photo = null,
                customFields = listOf(
                    ContactCardCustomField("Birthday", "10 Dec"),
                    ContactCardCustomField("", ""),
                ),
            ),
            saved.single(),
        )
    }

    @Test
    fun edit_seedsTheCard_andASaveKeepsWhatWasNotTouched() {
        val initial = ContactEdit(
            given = "Grace",
            family = "Hopper",
            company = "Navy",
            title = "Rear Admiral",
            emails = listOf("grace@navy.example"),
            phones = listOf("555-0199"),
            note = "COBOL",
            customFields = listOf(ContactCardCustomField("Ship", "Harvard Mark I")),
        )
        show(initial = initial)
        rule.onNodeWithText("Edit contact").assertExists()
        listOf(
            "Grace", "Hopper", "Navy", "Rear Admiral", "grace@navy.example",
            "555-0199", "COBOL", "Ship", "Harvard Mark I",
        ).forEach { rule.onNodeWithText(it).performScrollTo().assertExists() }
        save().assertIsEnabled()

        // A seeded field opens with its caret at the start, so retype rather than append.
        field(COMPANY).performTextClearance()
        field(COMPANY).performTextInput("Navy (ret.)")
        save().performClick()

        val edited = saved.single()
        assertEquals("Grace", edited.given)
        assertEquals("Hopper", edited.family)
        assertEquals("Navy (ret.)", edited.company)
        assertEquals("Rear Admiral", edited.title)
        // Each list carries its one seeded row plus the blank spare the form keeps under it.
        assertEquals(listOf("grace@navy.example", ""), edited.emails)
        assertEquals(listOf("555-0199", ""), edited.phones)
        assertEquals("COBOL", edited.note)
        assertEquals(
            listOf(ContactCardCustomField("Ship", "Harvard Mark I"), ContactCardCustomField("", "")),
            edited.customFields,
        )
    }

    @Test
    fun saving_locksSaveAndRemovesTheWayOut() {
        show(initial = ContactEdit(given = "Ada"), saving = true)
        save("Saving").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Discard").assertDoesNotExist()
    }

    @Test
    fun failure_outranksTheAdviceHints() {
        show(failure = "The server refused the card.")
        rule.onNodeWithText("The server refused the card.").assertExists()
        rule.onNodeWithText(HINT_FILE).assertDoesNotExist()
    }

    @Test
    fun discard_closesOnce() {
        show()
        rule.onNodeWithContentDescription("Discard").performClick()
        assertEquals(1, closed)
        assertTrue(saved.isEmpty())
    }

    private companion object {
        const val HINT_FILE = "A contact needs a name or a company to file under."
        const val HINT_EMAIL = "Check the email addresses: one of them doesn't look like name@company.com."
        const val GIVEN = 0
        const val FAMILY = 1
        const val COMPANY = 2
        const val TITLE = 3
        const val EMAIL = 4
        const val PHONE = 5
        const val CUSTOM_LABEL = 6
        const val CUSTOM_VALUE = 7
        const val NOTE = 8
    }
}
