package app.sterna.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SieveCodecTest {

    @Test fun roundTripsRulesThroughJsonComment() {
        val rules = listOf(
            FilterRule(
                name = "Boss", field = RuleField.FROM, match = RuleMatch.CONTAINS,
                value = "boss@example.com", moveTo = "Work", flag = true,
            ),
            FilterRule(
                name = "News", field = RuleField.SUBJECT, match = RuleMatch.IS,
                value = "Weekly digest", markRead = true,
            ),
        )
        val parsed = SieveCodec.parseRules(SieveCodec.generate(rules))
        assertEquals(rules, parsed)
    }

    @Test fun emitsRequiresAndTests() {
        val script = SieveCodec.generate(
            listOf(FilterRule(field = RuleField.FROM, value = "a@b.com", moveTo = "Work", markRead = true)),
        )
        assertTrue(script.contains("require [\"fileinto\", \"imap4flags\"];"))
        assertTrue(script.contains("address :contains \"from\" \"a@b.com\""))
        assertTrue(script.contains("fileinto \"Work\";"))
        assertTrue(script.contains("addflag \"\\\\Seen\";"))
    }

    @Test fun subjectUsesHeaderTest() {
        val script = SieveCodec.generate(
            listOf(FilterRule(field = RuleField.SUBJECT, match = RuleMatch.IS, value = "Hi", flag = true)),
        )
        assertTrue(script.contains("header :is \"subject\" \"Hi\""))
        assertTrue(script.contains("addflag \"\\\\Flagged\";"))
        // No fileinto requirement when nothing is moved.
        assertFalse(script.contains("fileinto"))
        assertTrue(script.contains("require [\"imap4flags\"];"))
    }

    @Test fun disabledAndBlankRulesAreNotEmittedButKeptInJson() {
        val rules = listOf(
            FilterRule(name = "off", enabled = false, value = "x@y.com", moveTo = "Work"),
            FilterRule(name = "blank", enabled = true, value = "   "),
        )
        val script = SieveCodec.generate(rules)
        assertFalse(script.contains("if ")) // neither rule produces a test
        // ...but both survive the round-trip via the JSON comment.
        assertEquals(rules, SieveCodec.parseRules(script))
    }

    @Test fun escapesQuotesAndBackslashesInValues() {
        val script = SieveCodec.generate(
            listOf(FilterRule(field = RuleField.SUBJECT, value = "a\"b\\c", flag = true)),
        )
        assertTrue(script.contains("\"a\\\"b\\\\c\""))
    }

    @Test fun noMarkerReturnsEmpty() {
        assertTrue(SieveCodec.parseRules("require [\"fileinto\"];\nif true { keep; }").isEmpty())
    }
}
