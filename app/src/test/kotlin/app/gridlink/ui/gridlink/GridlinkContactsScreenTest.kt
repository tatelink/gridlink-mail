package app.gridlink.ui.gridlink

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The address book list: one alphabet, grouped by letter, in phonebook order.
 *
 * The contract under test, as the caller sees it. The screen reads its cards from
 * [LocalGridlinkBook], never from a parameter: a book with a [GridlinkContactContent] is the
 * account's address book, no book at all is the sample. So every test here provides its own book of
 * invented people and one organization, and one test pins the sample fallback. The list is grouped
 * by the letter each card is filed under (the surname, or the company for an organization) with a
 * section label per letter and the rows of a letter in order; the header counts "people and teams".
 * The name order pill flips between "Last, First" and "First Last", which regroups the alphabet by
 * given name and is remembered in the "gridlink_contacts" preferences. `initialScrubLetter` opens the
 * list jumped to that letter, and a letter nobody is filed under lands on the nearest populated one
 * before it. A row's second line is the role, or the email when there is no role, or the first phone
 * when there is neither. Tapping a row reports THAT card through `onOpenContact`; "New contact"
 * reports `onCompose`; the nav pill reports `onSelectDestination`. An empty book shows the "No
 * contacts yet" state, whose tap also means "New contact", and a book still loading says so in the
 * header and shows no empty state. JVM-hosted under Robolectric, no device.
 *
 * Not covered here: the alphabet rail itself (it clears its semantics and is driven by pointer
 * drags, so the seed parameter is the only JVM-reachable road to the same code), the swipe between
 * destinations, and `sidePane`/`currentId` (the scaffold decides the two-pane layout).
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkContactsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<GridlinkContact>()
    private var composed = 0
    private val destinations = mutableListOf<GridlinkDestination>()

    private fun show(
        initialScrubLetter: Char? = null,
        book: GridlinkBook? = GridlinkBook(addressBook = GridlinkContactContent(CONTACTS)),
    ) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                val screen: @Composable () -> Unit = {
                    GridlinkContactsScreen(
                        destination = GridlinkDestination.CONTACTS,
                        onSelectDestination = { destinations += it },
                        initialScrubLetter = initialScrubLetter,
                        onOpenContact = { opened += it },
                        onCompose = { composed++ },
                    )
                }
                // No book provided means the screen falls back to the sample, which is the default
                // of the CompositionLocal itself; a book provided is the account's address book.
                if (book == null) {
                    screen()
                } else {
                    CompositionLocalProvider(LocalGridlinkBook provides book) { screen() }
                }
            }
        }
    }

    /** The one lazy list on screen: the alphabet. */
    private fun list() = rule.onNode(hasScrollToNodeAction())

    private fun top(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun sortPref(): String? =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("gridlink_contacts", Context.MODE_PRIVATE)
            .getString("sort", null)

    // ---- the list ---------------------------------------------------------------------------------

    @Test
    fun opensGroupedByFiledLetter_inPhonebookOrder_withTheHeaderCountingEveryCard() {
        show()
        rule.onNodeWithText("${CONTACTS.size} people and teams").assertExists()
        rule.onNodeWithText("Last, First").assertExists()

        // Section labels are the letter alone, and a person reads given then surname.
        rule.onNodeWithText("A").assertExists()
        rule.onNodeWithText("Nora Avery").assertExists()
        rule.onNodeWithText("B").assertExists()
        rule.onNodeWithText("Theo Bramwell").assertExists()
        // An organization is filed under its name, so it sits in C with the people.
        rule.onNodeWithText("C").assertExists()
        rule.onNodeWithText("Coastal Linen").assertExists()

        // Phonebook order on the page, whatever order the book handed the cards over in.
        assertTrue(top("A") < top("Nora Avery"))
        assertTrue(top("Nora Avery") < top("B"))
        assertTrue(top("B") < top("Theo Bramwell"))
        assertTrue(top("Theo Bramwell") < top("C"))
        assertTrue(top("C") < top("Coastal Linen"))
        assertTrue(top("Coastal Linen") < top("D"))
        // No letter is drawn for a gap in the alphabet: nobody is filed under E.
        rule.onNodeWithText("E").assertDoesNotExist()
        rule.onNodeWithText("No contacts yet").assertDoesNotExist()
    }

    @Test
    fun secondLine_isTheRole_orTheEmailWithoutOne_orThePhoneWithoutEither() {
        show()
        rule.onNodeWithText("District Manager").assertExists()
        list().performScrollToNode(hasText("i.duarte@sitecare.example"))
        rule.onNodeWithText("i.duarte@sitecare.example").assertExists()
        list().performScrollToNode(hasText("555-0142"))
        rule.onNodeWithText("555-0142").assertExists()
    }

    @Test
    fun noBookProvided_drawsTheSampleAddressBook() {
        show(book = null)
        rule.onNodeWithText("${GridlinkSampleContacts.all.size} people and teams").assertExists()
        rule.onNodeWithText("Paloma Ashby").assertExists()
        rule.onNodeWithText("Nora Avery").assertDoesNotExist()
    }

    // ---- the name order pill ----------------------------------------------------------------------

    @Test
    fun nameOrderPill_regroupsTheAlphabetByGivenName_andRemembersTheChoice() {
        show()
        assertEquals(null, sortPref())
        rule.onNodeWithContentDescription("Change name order").performClick()

        rule.onNodeWithText("First Last").assertExists()
        rule.onNodeWithText("Last, First").assertDoesNotExist()
        // The organization has no given name, so it keeps its own letter and now opens the list;
        // the first person by given name is Finn Tully, filed under F instead of T.
        rule.onNodeWithText("C").assertExists()
        rule.onNodeWithText("Coastal Linen").assertExists()
        rule.onNodeWithText("F").assertExists()
        rule.onNodeWithText("Finn Tully").assertExists()
        assertTrue(top("C") < top("Coastal Linen"))
        assertTrue(top("Coastal Linen") < top("F"))
        assertTrue(top("F") < top("Finn Tully"))
        // Nobody's given name starts with A or B any more.
        rule.onNodeWithText("A").assertDoesNotExist()
        rule.onNodeWithText("B").assertDoesNotExist()
        assertEquals("FIRST_LAST", sortPref())

        // And back again: the alphabet is by surname once more and the choice is saved once more.
        rule.onNodeWithContentDescription("Change name order").performClick()
        rule.onNodeWithText("Last, First").assertExists()
        assertEquals("LAST_FIRST", sortPref())
        // On screen WITHOUT scrolling: the toggle lands the regrouped list on its top. It used not
        // to (the scroll ran before the new sections were composed and the list kept the old first
        // item by key, so this sat at "C" with A and B scrolled off above); the screen now scrolls
        // from an effect keyed on the flip, and this line is what keeps it that way.
        rule.onNodeWithText("A").assertIsDisplayed()
        rule.onNodeWithText("Nora Avery").assertIsDisplayed()
        rule.onNodeWithText("F").assertDoesNotExist()
    }

    // ---- the scrub seed ---------------------------------------------------------------------------

    @Test
    fun initialScrubLetter_opensTheListJumpedToThatLetter() {
        show(initialScrubLetter = 'N')
        rule.onNodeWithText("N").assertIsDisplayed()
        rule.onNodeWithText("Grace Nash").assertIsDisplayed()
        // Rows far above the jump are not even composed: the list opened at N, it was not scrolled
        // past A.
        rule.onAllNodesWithText("Nora Avery").assertCountEquals(0)
        assertTrue(top("N") < top("Grace Nash"))
    }

    @Test
    fun initialScrubLetter_nobodyIsFiledUnder_landsOnTheNearestPopulatedLetterBefore() {
        show(initialScrubLetter = 'X')
        // X is empty; W is the closest letter before it with a card.
        rule.onNodeWithText("W").assertIsDisplayed()
        rule.onNodeWithText("Milo Ward").assertIsDisplayed()
        rule.onAllNodesWithText("Nora Avery").assertCountEquals(0)
    }

    // ---- what the screen reports ------------------------------------------------------------------

    @Test
    fun tappingARow_opensThatCard_andNothingElseFires() {
        show()
        rule.onNodeWithText("Theo Bramwell").performClick()
        assertEquals(listOf("bramwell"), opened.map { it.id })
        assertEquals(0, composed)
        assertTrue(destinations.isEmpty())

        list().performScrollToNode(hasText("Petra Zimmer"))
        rule.onNodeWithText("Petra Zimmer").performClick()
        assertEquals(listOf("bramwell", "zimmer"), opened.map { it.id })
    }

    @Test
    fun newContact_reportsCompose_andTheNavPillReportsTheDestination() {
        show()
        rule.onNodeWithContentDescription("New contact").performClick()
        assertEquals(1, composed)
        rule.onNodeWithContentDescription("Calendar").performClick()
        assertEquals(listOf(GridlinkDestination.CALENDAR), destinations)
        assertTrue(opened.isEmpty())
    }

    // ---- empty and loading ------------------------------------------------------------------------

    @Test
    fun emptyBook_showsTheEmptyState_whoseTapMeansNewContact() {
        show(book = GridlinkBook(addressBook = GridlinkContactContent(emptyList())))
        rule.onNodeWithText("0 people and teams").assertExists()
        rule.onNodeWithText("No contacts yet").assertExists()
        rule.onNodeWithText("Nothing in this address book", substring = true).assertExists()
        rule.onNodeWithText("Last, First").assertDoesNotExist()
        rule.onNode(hasText("No contacts yet") and hasClickAction()).performClick()
        assertEquals(1, composed)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun loadingBook_saysLoading_andShowsNoEmptyState() {
        show(book = GridlinkBook(addressBook = GridlinkContactContent(emptyList(), loading = true)))
        rule.onNodeWithText("Loading").assertExists()
        rule.onNodeWithText("0 people and teams").assertDoesNotExist()
        rule.onNodeWithText("No contacts yet").assertDoesNotExist()
        rule.onNode(hasTextExactly("Last, First") and hasClickAction()).assertExists()
    }

    private companion object {
        /**
         * Fifteen cards in deliberately scrambled order: fourteen people and one organization,
         * spread over enough letters that a jump to N leaves A out of the viewport, one card with
         * no role (email on the second line) and one with neither role nor email (phone).
         */
        val CONTACTS: List<GridlinkContact> = listOf(
            GridlinkContact("zimmer", "Petra", "Zimmer", "Insurance Adjuster", "p.zimmer@mardenmma.example"),
            GridlinkContact("nash", "Grace", "Nash", "Regional Trainer", "g.nash@hrbenefits.example"),
            GridlinkContact("avery", "Nora", "Avery", "District Manager", "n.avery@gridlink.me"),
            GridlinkContact("coastal", "", "Coastal Linen", "Linen service", "service@coastallinen.example"),
            GridlinkContact("bramwell", "Theo", "Bramwell", "General Manager", "t.bramwell@gridlink.me"),
            GridlinkContact("duarte", "Ines", "Duarte", "", "i.duarte@sitecare.example"),
            GridlinkContact("keller", "Max", "Keller", "", "", phones = listOf("555-0142")),
            GridlinkContact("osei", "Kwame", "Osei", "Beverage Rep", "k.osei@brightmar.example"),
            GridlinkContact("park", "June", "Park", "Shift Lead", "j.park@gridlink.me"),
            GridlinkContact("quinn", "Rory", "Quinn", "Payroll Specialist", "r.quinn@hrbenefits.example"),
            GridlinkContact("reyes", "Tomas", "Reyes", "Kitchen Manager", "t.reyes@gridlink.me"),
            GridlinkContact("sato", "Hana", "Sato", "Food Safety Auditor", "h.sato@verdantfs.example"),
            GridlinkContact("tully", "Finn", "Tully", "POS Support Tech", "f.tully@tallyman.example"),
            GridlinkContact("vance", "Lena", "Vance", "Store Accountant", "l.vance@gridlink.me"),
            GridlinkContact("ward", "Milo", "Ward", "Maintenance Tech", "m.ward@sitecare.example"),
        )
    }
}
