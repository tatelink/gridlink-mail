package app.gridlink.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gridlink.billing.TipAvailability
import app.gridlink.billing.TipJarState
import app.gridlink.billing.TipNotice
import app.gridlink.billing.TipOption
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Google Play build's tip jar, every state drawn from a fixed [TipJarState] so no Play Store is
 * needed: the prices are Play's strings shown verbatim, a tap names the product, a busy card cannot
 * be tapped twice, and a card with no Play (or no products yet) says so quietly with no pills. Also
 * that the Play build carries no Ko-fi link at all. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class PlaySupportCardTest {

    @get:Rule
    val rule = createComposeRule()

    private val tapped = mutableListOf<String>()

    private val prices = listOf(
        TipOption("tip_small", "$1.99", 1_990_000),
        TipOption("tip_medium", "$4.99", 4_990_000),
        TipOption("tip_large", "$9.99", 9_990_000),
    )

    private fun show(state: TipJarState) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                TipJarCard(state = state, onTip = { tapped += it })
            }
        }
    }

    @Test
    fun readyShowsPlaysPricesAndSaysTipsUnlockNothing() {
        show(TipJarState(availability = TipAvailability.Ready, options = prices))
        rule.onNodeWithText("Support Gridlink").assertExists()
        rule.onNodeWithText("Tips are voluntary and unlock nothing", substring = true).assertExists()
        prices.forEach { rule.onNode(hasText(it.price) and hasClickAction()).assertIsEnabled() }
        rule.onNodeWithContentDescription("Tip $4.99").assertExists()
        rule.onNodeWithText("Ko-fi", substring = true).assertDoesNotExist()
    }

    @Test
    fun tappingAPriceAsksForThatProduct() {
        show(TipJarState(availability = TipAvailability.Ready, options = prices))
        rule.onNodeWithText("$9.99").performClick()
        assertEquals(listOf("tip_large"), tapped)
    }

    @Test
    fun busyPillsCannotBeTappedAgain() {
        show(TipJarState(availability = TipAvailability.Ready, options = prices, busy = true))
        rule.onNode(hasText("$1.99") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun unavailableSaysSoQuietlyWithNoPills() {
        show(TipJarState(availability = TipAvailability.Unavailable))
        rule.onNodeWithText("Tips through Google Play are not available right now.").assertExists()
        rule.onNodeWithText("$1.99").assertDoesNotExist()
    }

    @Test
    fun loadingSaysItIsChecking() {
        show(TipJarState())
        rule.onNodeWithText("Checking Google Play for tips.").assertExists()
    }

    @Test
    fun afterATipSaysThankYouAndTheBodyThanksToo() {
        show(
            TipJarState(
                availability = TipAvailability.Ready,
                options = prices,
                notice = TipNotice.Thanks,
                tippedBefore = true,
            ),
        )
        rule.onNodeWithText("Thank you! Your tip went through.").assertExists()
        rule.onNodeWithText("Thank you for supporting Gridlink.", substring = true).assertExists()
    }

    @Test
    fun pendingSaysItIsWaitingOnPlay() {
        show(TipJarState(availability = TipAvailability.Ready, options = prices, notice = TipNotice.Pending))
        rule.onNodeWithText("Your tip is waiting on Google Play", substring = true).assertExists()
    }

    @Test
    fun failedSaysTryAgain() {
        show(TipJarState(availability = TipAvailability.Ready, options = prices, notice = TipNotice.Failed))
        rule.onNodeWithText("The tip did not go through. Please try again in a moment.").assertExists()
    }
}
