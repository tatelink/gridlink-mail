package app.gridlink.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterRuleTest {

    @Test fun freshRuleIsEmpty() {
        assertTrue(FilterRule().isEmpty)
        assertTrue(FilterRule(name = "  ").isEmpty)
        // Picking a field or a match keeps the defaults' meaning: still nothing entered.
        assertTrue(FilterRule(conditions = listOf(RuleCondition(field = RuleField.SUBJECT))).isEmpty)
        assertTrue(FilterRule(enabled = false).isEmpty)
        // A rule whose conditions were all cleared is empty too, however many rows are left.
        assertTrue(FilterRule(conditions = listOf(RuleCondition(), RuleCondition())).isEmpty)
    }

    @Test fun anyFilledConditionOrActionMakesItNonEmpty() {
        assertFalse(FilterRule(name = "Boss").isEmpty)
        assertFalse(FilterRule(conditions = listOf(RuleCondition(value = "boss@example.com"))).isEmpty)
        assertFalse(FilterRule(moveTo = "Work").isEmpty)
        assertFalse(FilterRule(markRead = true).isEmpty)
        assertFalse(FilterRule(flag = true).isEmpty)
        assertFalse(FilterRule(addTag = "work").isEmpty)
        assertFalse(FilterRule(stop = true).isEmpty)
    }

    @Test fun aPresenceConditionIsNeverBlank_becauseThereIsNothingLeftToType() {
        val attachment = RuleCondition(field = RuleField.HAS_ATTACHMENT, match = RuleMatch.PRESENT)
        assertFalse(attachment.isBlank)
        // ...so a rule holding only that one still has something to compile.
        assertFalse(FilterRule(conditions = listOf(attachment)).isEmpty)
        assertEquals(listOf(attachment), FilterRule(conditions = listOf(attachment)).activeConditions)
    }

    @Test fun aSizeConditionNeedsAPositiveWholeNumber() {
        fun size(value: String) = RuleCondition(RuleField.SIZE, RuleMatch.OVER, value)
        assertEquals(500, size("500").sizeKb)
        assertEquals(500, size("  500  ").sizeKb)
        assertNull(size("").sizeKb)
        assertNull(size("0").sizeKb)
        assertNull(size("-5").sizeKb)
        assertNull(size("5.5").sizeKb)
        assertNull(size("big").sizeKb)
        assertTrue(size("big").isBlank)
        assertFalse(size("500").isBlank)
    }

    @Test fun changingFieldWithinAKindKeepsWhatWasTyped() {
        val typed = RuleCondition(RuleField.FROM, RuleMatch.STARTS_WITH, "boss@")
        assertEquals(
            RuleCondition(RuleField.TO, RuleMatch.STARTS_WITH, "boss@"),
            typed.withField(RuleField.TO),
        )
        // Same field is a no-op rather than a reset.
        assertEquals(typed, typed.withField(RuleField.FROM))
    }

    @Test fun changingFieldAcrossKindsResetsTheMatchAndTheValue() {
        val typed = RuleCondition(RuleField.SUBJECT, RuleMatch.NOT_CONTAINS, "invoice")
        // "Subject doesn't contain invoice" cannot become "Size doesn't contain invoice": the
        // codec has no such test and the summary has no way to read it.
        assertEquals(RuleCondition(RuleField.SIZE, RuleMatch.OVER, ""), typed.withField(RuleField.SIZE))
        assertEquals(
            RuleCondition(RuleField.HAS_ATTACHMENT, RuleMatch.PRESENT, ""),
            typed.withField(RuleField.HAS_ATTACHMENT),
        )
        // ...and back out again, on the kind's first match rather than the one left behind.
        val size = typed.withField(RuleField.SIZE)
        assertEquals(RuleCondition(RuleField.BODY, RuleMatch.CONTAINS, ""), size.withField(RuleField.BODY))
    }

    @Test fun everyFieldOffersAtLeastOneMatch_andOnlyOfItsOwnKind() {
        for (field in RuleField.entries) {
            assertTrue(field.name, field.matches.isNotEmpty())
            assertTrue(field.name, field.matches.all { it.kind == field.kind })
        }
    }

    @Test fun editableConditionsAlwaysGivesTheEditorARowToTypeIn() {
        assertEquals(1, FilterRule(conditions = emptyList()).editableConditions.size)
        assertEquals(2, FilterRule(conditions = listOf(RuleCondition(), RuleCondition())).editableConditions.size)
    }

    @Test fun emptinessSurvivesTheJsonRoundTrip() {
        val filled = FilterRule(name = "Boss", conditions = listOf(RuleCondition(value = "b@c.d")))
        val script = SieveCodec.generate(listOf(FilterRule(), filled))
        val parsed = SieveCodec.parseRules(script)
        assertTrue(parsed[0].isEmpty)
        assertFalse(parsed[1].isEmpty)
    }
}
