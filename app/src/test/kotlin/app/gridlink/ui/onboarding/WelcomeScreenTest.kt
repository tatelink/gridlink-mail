package app.gridlink.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.gridlink.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The privacy welcome that precedes the setup form. Two pages, fully skippable, and [WelcomeScreen]'s
 * contract is that [onDone] fires exactly once on either exit. Runs on the JVM under Robolectric
 * (see `src/test/resources/robolectric.properties`); the screen reads string resources, which is
 * why `isIncludeAndroidResources` is on for this module.
 */
@RunWith(RobolectricTestRunner::class)
class WelcomeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var done = 0

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun show() {
        rule.setContent { WelcomeScreen(onDone = { done++ }) }
    }

    @Test
    fun next_turnsThePage_andGetStartedFinishes() {
        show()
        rule.onNodeWithText(string(R.string.connect_welcome_title)).assertExists()
        rule.onNodeWithText(string(R.string.welcome_get_started)).assertDoesNotExist()

        rule.onNodeWithText(string(R.string.welcome_next)).performClick()
        assertEquals("Next must not finish the flow", 0, done)
        rule.onNodeWithText(string(R.string.welcome_screen2_title)).assertExists()
        rule.onNodeWithText(string(R.string.welcome_next)).assertDoesNotExist()

        rule.onNodeWithText(string(R.string.welcome_get_started)).performClick()
        assertEquals(1, done)
    }

    @Test
    fun skip_finishesFromTheFirstPage() {
        show()
        rule.onNodeWithText(string(R.string.welcome_skip)).performClick()
        assertEquals(1, done)
    }
}
