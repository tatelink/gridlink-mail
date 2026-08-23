package app.gridlink.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SieveCodecTest {

    @Test fun roundTripsRulesThroughJsonComment() {
        val rules = listOf(
            FilterRule(
                name = "Boss",
                conditions = listOf(RuleCondition(RuleField.FROM, RuleMatch.CONTAINS, "boss@example.com")),
                moveTo = "Work", flag = true,
            ),
            FilterRule(
                name = "News",
                mode = RuleMatchMode.ANY,
                conditions = listOf(
                    RuleCondition(RuleField.SUBJECT, RuleMatch.IS, "Weekly digest"),
                    RuleCondition(RuleField.LIST_ID, RuleMatch.CONTAINS, "news.example.com"),
                ),
                markRead = true, addTag = "reading", stop = true,
            ),
        )
        val parsed = SieveCodec.parseRules(SieveCodec.generate(rules))
        assertEquals(rules, parsed)
    }

    @Test fun emitsRequiresAndTests() {
        val script = SieveCodec.generate(
            listOf(
                FilterRule(
                    conditions = listOf(RuleCondition(RuleField.FROM, RuleMatch.CONTAINS, "a@b.com")),
                    moveTo = "Work", markRead = true,
                ),
            ),
        )
        assertTrue(script.contains("require [\"fileinto\", \"imap4flags\"];"))
        assertTrue(script.contains("address :contains \"from\" \"a@b.com\""))
        assertTrue(script.contains("fileinto \"Work\";"))
        assertTrue(script.contains("addflag \"\\\\Seen\";"))
    }

    @Test fun subjectUsesHeaderTest() {
        val script = SieveCodec.generate(
            listOf(
                FilterRule(
                    conditions = listOf(RuleCondition(RuleField.SUBJECT, RuleMatch.IS, "Hi")),
                    flag = true,
                ),
            ),
        )
        assertTrue(script.contains("header :is \"subject\" \"Hi\""))
        assertTrue(script.contains("addflag \"\\\\Flagged\";"))
        // No fileinto requirement when nothing is moved.
        assertFalse(script.contains("fileinto"))
        assertTrue(script.contains("require [\"imap4flags\"];"))
    }

    @Test fun disabledAndBlankRulesAreNotEmittedButKeptInJson() {
        val rules = listOf(
            FilterRule(
                name = "off", enabled = false,
                conditions = listOf(RuleCondition(value = "x@y.com")), moveTo = "Work",
            ),
            FilterRule(name = "blank", conditions = listOf(RuleCondition(value = "   "))),
        )
        val script = SieveCodec.generate(rules)
        assertFalse(script.contains("if ")) // neither rule produces a test
        // ...but both survive the round-trip via the JSON comment.
        assertEquals(rules, SieveCodec.parseRules(script))
    }

    @Test fun escapesQuotesAndBackslashesInValues() {
        val script = SieveCodec.generate(
            listOf(FilterRule(conditions = listOf(RuleCondition(RuleField.SUBJECT, value = "a\"b\\c")), flag = true)),
        )
        assertTrue(script.contains("\"a\\\"b\\\\c\""))
    }

    @Test fun noMarkerReturnsEmpty() {
        assertTrue(SieveCodec.parseRules("require [\"fileinto\"];\nif true { keep; }").isEmpty())
    }

    // -- v2: several conditions ------------------------------------------------------------------

    @Test fun oneConditionIsBare_andSeveralAreJoinedByTheRulesMode() {
        fun script(mode: RuleMatchMode, vararg conditions: RuleCondition) = SieveCodec.generate(
            listOf(FilterRule(mode = mode, conditions = conditions.toList(), flag = true)),
        )
        val from = RuleCondition(RuleField.FROM, RuleMatch.CONTAINS, "a@b.com")
        val subject = RuleCondition(RuleField.SUBJECT, RuleMatch.CONTAINS, "invoice")

        // One test needs no wrapper at all.
        assertTrue(script(RuleMatchMode.ALL, from).contains("if address :contains \"from\" \"a@b.com\" {"))
        assertTrue(
            script(RuleMatchMode.ALL, from, subject).contains(
                "if allof(address :contains \"from\" \"a@b.com\", header :contains \"subject\" \"invoice\") {",
            ),
        )
        assertTrue(script(RuleMatchMode.ANY, from, subject).contains("if anyof(address :contains"))
    }

    @Test fun blankConditionsAreSkipped_soAHalfTypedRowCannotWidenTheRule() {
        val script = SieveCodec.generate(
            listOf(
                FilterRule(
                    conditions = listOf(
                        RuleCondition(RuleField.FROM, RuleMatch.CONTAINS, "a@b.com"),
                        RuleCondition(RuleField.SUBJECT, RuleMatch.CONTAINS, "   "),
                    ),
                    flag = true,
                ),
            ),
        )
        // One usable test left, so no allof() and no empty second operand.
        assertFalse(script.contains("allof("))
        assertFalse(script.contains("\"subject\""))
    }

    // -- v2: the wider field and match sets ------------------------------------------------------

    @Test fun toOrCcTestsBothHeadersInOneAddressTest() {
        val script = generateOne(RuleCondition(RuleField.TO_OR_CC, RuleMatch.CONTAINS, "me@gridlink.me"))
        assertTrue(script.contains("address :contains [\"to\", \"cc\"] \"me@gridlink.me\""))
    }

    @Test fun bodyPullsInTheBodyExtension() {
        val script = generateOne(RuleCondition(RuleField.BODY, RuleMatch.CONTAINS, "unsubscribe"))
        assertTrue(script.contains("require [\"imap4flags\", \"body\"];"))
        assertTrue(script.contains("body :text :contains \"unsubscribe\""))
    }

    @Test fun listIdIsAPlainHeaderTest() {
        val script = generateOne(RuleCondition(RuleField.LIST_ID, RuleMatch.CONTAINS, "kernel.vger"))
        assertTrue(script.contains("header :contains \"list-id\" \"kernel.vger\""))
    }

    @Test fun negatedMatchesAreWrappedInNot() {
        assertTrue(
            generateOne(RuleCondition(RuleField.SUBJECT, RuleMatch.NOT_CONTAINS, "invoice"))
                .contains("if not header :contains \"subject\" \"invoice\" {"),
        )
        assertTrue(
            generateOne(RuleCondition(RuleField.FROM, RuleMatch.NOT_IS, "a@b.com"))
                .contains("if not address :is \"from\" \"a@b.com\" {"),
        )
    }

    @Test fun startsAndEndsWithAreAnchoredMatchesPatterns() {
        assertTrue(
            generateOne(RuleCondition(RuleField.SUBJECT, RuleMatch.STARTS_WITH, "Re:"))
                .contains("header :matches \"subject\" \"Re:*\""),
        )
        assertTrue(
            generateOne(RuleCondition(RuleField.FROM, RuleMatch.ENDS_WITH, "@gridlink.me"))
                .contains("address :matches \"from\" \"*@gridlink.me\""),
        )
    }

    @Test fun aTypedStarIsNotAWildcard() {
        // 🔴 The whole point of glob-escaping: this rule is about a literal "50% off?", not about
        // "50% off" followed by any one character.
        val script = generateOne(RuleCondition(RuleField.SUBJECT, RuleMatch.STARTS_WITH, "50% off?"))
        assertTrue(script.contains("header :matches \"subject\" \"50% off\\\\?*\""))
        // The anchoring star survives; the typed one does not.
        val star = generateOne(RuleCondition(RuleField.SUBJECT, RuleMatch.ENDS_WITH, "a*b"))
        assertTrue(star.contains("header :matches \"subject\" \"*a\\\\*b\""))
    }

    @Test fun sizeComparesTheWholeMessageInKilobytes() {
        assertTrue(
            generateOne(RuleCondition(RuleField.SIZE, RuleMatch.OVER, "5000")).contains("size :over 5000K"),
        )
        assertTrue(
            generateOne(RuleCondition(RuleField.SIZE, RuleMatch.UNDER, "2")).contains("size :under 2K"),
        )
    }

    @Test fun attachmentIsAContentTypeHeuristic_andItsAbsenceIsTheSameTestNegated() {
        val present = generateOne(RuleCondition(RuleField.HAS_ATTACHMENT, RuleMatch.PRESENT))
        assertTrue(present.contains("if header :contains \"content-type\" \"multipart/mixed\" {"))
        val absent = generateOne(RuleCondition(RuleField.HAS_ATTACHMENT, RuleMatch.ABSENT))
        assertTrue(absent.contains("if not header :contains \"content-type\" \"multipart/mixed\" {"))
    }

    // -- v2: the two new actions -----------------------------------------------------------------

    @Test fun aTagIsAPlainKeywordFlag_andStopComesLast() {
        val script = SieveCodec.generate(
            listOf(
                FilterRule(
                    conditions = listOf(RuleCondition(value = "a@b.com")),
                    moveTo = "Work", markRead = true, addTag = "receipts", stop = true,
                ),
            ),
        )
        assertTrue(script.contains("addflag \"receipts\";"))
        assertTrue(script.contains("require [\"fileinto\", \"imap4flags\"];"))
        // Flags, then the file, then the stop: everything the rule was asked to do has happened.
        val body = script.substringAfter("{\n").substringBefore("}")
        assertEquals(
            listOf("addflag \"\\\\Seen\";", "addflag \"receipts\";", "fileinto \"Work\";", "stop;"),
            body.trim().lines().map { it.trim() },
        )
    }

    @Test fun aTagAloneStillPullsInImap4flags() {
        val script = SieveCodec.generate(
            listOf(FilterRule(conditions = listOf(RuleCondition(value = "a@b.com")), addTag = "work")),
        )
        assertTrue(script.contains("require [\"imap4flags\"];"))
    }

    // -- v1 scripts written before conditions became a list --------------------------------------

    @Test fun aV1ScriptIsLiftedIntoOneConditionRules() {
        // 🔴 The exact shape a shipped build wrote. If this ever comes back with empty conditions,
        // the migration has broken and every existing rule silently stops filtering.
        val v1 = "# GRIDLINK-RULES-V1: [" +
            """{"name":"Boss","enabled":true,"field":"FROM","match":"CONTAINS",""" +
            """"value":"boss@example.com","moveTo":"Work","markRead":false,"flag":true},""" +
            """{"name":"Off","enabled":false,"field":"SUBJECT","match":"IS",""" +
            """"value":"Weekly","moveTo":null,"markRead":true,"flag":false}]""" +
            "\nrequire [\"fileinto\", \"imap4flags\"];\n"
        assertEquals(
            listOf(
                FilterRule(
                    name = "Boss",
                    conditions = listOf(RuleCondition(RuleField.FROM, RuleMatch.CONTAINS, "boss@example.com")),
                    moveTo = "Work", flag = true,
                ),
                FilterRule(
                    name = "Off", enabled = false,
                    conditions = listOf(RuleCondition(RuleField.SUBJECT, RuleMatch.IS, "Weekly")),
                    markRead = true,
                ),
            ),
            SieveCodec.parseRules(v1),
        )
    }

    @Test fun onlyV2IsWritten_soAReSaveUpgradesTheScriptInPlace() {
        val migrated = SieveCodec.parseRules(
            """# GRIDLINK-RULES-V1: [{"name":"Boss","field":"FROM","match":"IS","value":"b@c.d"}]""",
        )
        val rewritten = SieveCodec.generate(migrated)
        assertTrue(rewritten.startsWith("# GRIDLINK-RULES-V2:"))
        assertFalse(rewritten.contains("GRIDLINK-RULES-V1"))
        assertEquals(migrated, SieveCodec.parseRules(rewritten))
    }

    @Test fun anUnreadableMarkerIsEmptyRatherThanAThrow() {
        assertTrue(SieveCodec.parseRules("# GRIDLINK-RULES-V2: not json at all").isEmpty())
        assertTrue(SieveCodec.parseRules("# GRIDLINK-RULES-V1: {").isEmpty())
    }

    private fun generateOne(condition: RuleCondition): String =
        SieveCodec.generate(listOf(FilterRule(conditions = listOf(condition), flag = true)))
}
