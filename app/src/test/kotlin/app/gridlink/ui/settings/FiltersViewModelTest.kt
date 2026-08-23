package app.gridlink.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.gridlink.TestGridlinkApplication
import app.gridlink.core.data.filter.FilterRule
import app.gridlink.core.data.filter.RuleCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The filters view model's bookkeeping, on a fresh install with no account: the state it hands the
 * screen, and the rules of editing that need no server. Dirty means "differs from what the server
 * last confirmed" and nothing else; an untouched rule committed from the editor is dropped rather
 * than kept as a ghost row; and Save with no account to save to is a no-op rather than a crash.
 * Runs against the real [app.gridlink.AppContainer] through [TestGridlinkApplication].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGridlinkApplication::class)
class FiltersViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun noAccount_isReportedAsSuch_notAsALoadFailure() {
        val vm = FiltersViewModel(app)
        val s = vm.state.value
        assertFalse(s.loading)
        assertTrue(s.noAccount)
        assertNull(s.errorKind)
        assertFalse(s.dirty)
    }

    @Test
    fun addRule_appendsABlankRule_andThatAloneIsDirty() {
        val vm = FiltersViewModel(app)
        vm.addRule()
        assertEquals(listOf(FilterRule()), vm.state.value.rules)
        assertTrue(vm.state.value.dirty)
    }

    @Test
    fun committingAnUntouchedRule_dropsIt_andTheListIsCleanAgain() {
        val vm = FiltersViewModel(app)
        vm.addRule()
        vm.updateRule(0, FilterRule())
        assertEquals(emptyList<FilterRule>(), vm.state.value.rules)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun committingAnEditedRule_replacesItInPlace() {
        val vm = FiltersViewModel(app)
        vm.addRule()
        vm.addRule()
        val edited = FilterRule(
            name = "Receipts",
            conditions = listOf(RuleCondition(value = "invoice")),
            markRead = true,
        )
        vm.updateRule(1, edited)
        assertEquals(listOf(FilterRule(), edited), vm.state.value.rules)
    }

    @Test
    fun removeAndToggle_editTheOneRowNamed() {
        val vm = FiltersViewModel(app)
        vm.addRule()
        vm.updateRule(0, FilterRule(name = "A"))
        vm.addRule()
        vm.updateRule(1, FilterRule(name = "B"))
        vm.setRuleEnabled(0, false)
        assertEquals(listOf(FilterRule(name = "A", enabled = false), FilterRule(name = "B")), vm.state.value.rules)
        vm.removeRule(0)
        assertEquals(listOf(FilterRule(name = "B")), vm.state.value.rules)
    }

    @Test
    fun save_withNoAccount_isANoOp() {
        val vm = FiltersViewModel(app)
        vm.addRule()
        vm.updateRule(0, FilterRule(name = "A"))
        vm.save()
        val s = vm.state.value
        assertFalse(s.saving)
        assertEquals(0, s.savedTick)
        assertNull(s.errorKind)
        assertTrue("nothing was pushed, so the edit is still unsaved", s.dirty)
    }
}
