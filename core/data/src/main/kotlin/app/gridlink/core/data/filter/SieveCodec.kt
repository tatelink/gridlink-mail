package app.gridlink.core.data.filter

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Compiles structured [FilterRule]s to a Sieve script and reads them back.
 *
 * The round-trip trick: the full rule list is serialized to JSON and stored on a single
 * `# GRIDLINK-RULES-V2:` comment line at the top of the script. Generation is one-way
 * (rules -> Sieve); reading just parses that JSON comment, so we never parse arbitrary Sieve.
 * Disabled rules are kept in the JSON but not emitted as Sieve, so they survive a save without
 * filtering mail.
 *
 * ## 🔴 Why the marker is versioned, and why V1 is still read
 * v1 stored one flat condition per rule (`field`, `match`, `value`); v2 stores a list under
 * `conditions`. The JSON parser ignores unknown keys, so a V1 payload read as v2 would decode
 * CLEANLY and silently: every rule would keep its name and its actions while losing the condition
 * that made it fire. The next Save would push that back to the server, and the reader's filters
 * would be gone with nothing having reported an error. So V1 is read by its own serializer and
 * lifted into a one-condition rule, and only V2 is ever written.
 */
object SieveCodec {
    const val SCRIPT_NAME = "gridlink"
    private const val MARKER = "# GRIDLINK-RULES-V2:"
    private const val LEGACY_MARKER = "# GRIDLINK-RULES-V1:"

    /**
     * The stand-in for a real attachment test, which Sieve does not have.
     *
     * ⚠️ A heuristic: `multipart/mixed` is how a message with a file attached is nearly always
     * assembled, but a sender is free to use another container and a message can be
     * `multipart/mixed` for other reasons. The editor tells the reader this rather than letting
     * the condition look exact.
     */
    private const val ATTACHMENT_TEST = "header :contains \"content-type\" \"multipart/mixed\""

    /** The matches that mean the opposite of their test, wrapped in Sieve's `not`. */
    private val NEGATED = setOf(RuleMatch.NOT_CONTAINS, RuleMatch.NOT_IS, RuleMatch.ABSENT)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FilterRule.serializer())
    private val legacySerializer = ListSerializer(LegacyRule.serializer())

    /** Build the Sieve script (with embedded JSON metadata) for [rules]. */
    fun generate(rules: List<FilterRule>): String {
        val sb = StringBuilder()
        sb.append(MARKER).append(' ').append(json.encodeToString(serializer, rules)).append('\n')

        val active = rules.filter { it.enabled && it.activeConditions.isNotEmpty() }
        val requires = buildList {
            if (active.any { it.moveTo != null }) add("fileinto")
            if (active.any { it.markRead || it.flag || it.addTag != null }) add("imap4flags")
            if (active.any { rule -> rule.activeConditions.any { it.field == RuleField.BODY } }) {
                add("body")
            }
        }
        if (requires.isNotEmpty()) {
            sb.append("require [").append(requires.joinToString(", ") { "\"$it\"" }).append("];\n")
        }
        sb.append('\n')
        for (rule in active) sb.append(ruleToSieve(rule)).append('\n')
        return sb.toString()
    }

    /**
     * Parse the rule list out of a script's JSON metadata comment (empty if none).
     *
     * A V1 marker is migrated on the spot; see the class KDoc for why that is not left to the
     * unknown-key tolerance the parser already has.
     */
    fun parseRules(script: String): List<FilterRule> {
        val lines = script.lineSequence().map { it.trim() }.toList()
        lines.firstOrNull { it.startsWith(MARKER) }?.let { line ->
            return decode(serializer, line.removePrefix(MARKER))
        }
        lines.firstOrNull { it.startsWith(LEGACY_MARKER) }?.let { line ->
            return decode(legacySerializer, line.removePrefix(LEGACY_MARKER)).map { it.toRule() }
        }
        return emptyList()
    }

    private fun <T> decode(target: KSerializer<List<T>>, payload: String): List<T> =
        runCatching { json.decodeFromString(target, payload.trim()) }.getOrDefault(emptyList())

    private fun ruleToSieve(rule: FilterRule): String {
        val tests = rule.activeConditions.mapNotNull { conditionToSieve(it) }
        if (tests.isEmpty()) return ""
        val test = when {
            tests.size == 1 -> tests.single()
            rule.mode == RuleMatchMode.ANY -> "anyof(${tests.joinToString(", ")})"
            else -> "allof(${tests.joinToString(", ")})"
        }
        val body = StringBuilder()
        // Flags must be set before fileinto/keep so the filed copy carries them.
        if (rule.markRead) body.append("    addflag \"\\\\Seen\";\n")
        if (rule.flag) body.append("    addflag \"\\\\Flagged\";\n")
        // A tag is an ordinary IMAP keyword, so it is the same addflag with no leading backslash.
        rule.addTag?.let { body.append("    addflag \"${escape(it)}\";\n") }
        if (rule.moveTo != null) body.append("    fileinto \"${escape(rule.moveTo)}\";\n")
        // Last, so everything this rule was asked to do has already happened.
        if (rule.stop) body.append("    stop;\n")
        return "if $test {\n$body}"
    }

    /** One condition as a Sieve test, or null when it cannot be compiled. */
    private fun conditionToSieve(condition: RuleCondition): String? {
        // Only the TEXT fields read these two; a size or presence test carries neither.
        val tag = matchTag(condition.match)
        val key = keyString(condition)
        val test = when (condition.field) {
            RuleField.FROM -> "address $tag \"from\" $key"
            RuleField.TO -> "address $tag \"to\" $key"
            RuleField.CC -> "address $tag \"cc\" $key"
            RuleField.TO_OR_CC -> "address $tag [\"to\", \"cc\"] $key"
            RuleField.SUBJECT -> "header $tag \"subject\" $key"
            RuleField.LIST_ID -> "header $tag \"list-id\" $key"
            RuleField.BODY -> "body :text $tag $key"
            RuleField.HAS_ATTACHMENT -> ATTACHMENT_TEST
            RuleField.SIZE -> sizeTest(condition) ?: return null
        }
        return if (condition.match in NEGATED) "not $test" else test
    }

    private fun sizeTest(condition: RuleCondition): String? {
        val kb = condition.sizeKb ?: return null
        val over = if (condition.match == RuleMatch.UNDER) ":under" else ":over"
        return "size $over ${kb}K"
    }

    private fun matchTag(match: RuleMatch): String = when (match) {
        RuleMatch.IS, RuleMatch.NOT_IS -> ":is"
        RuleMatch.STARTS_WITH, RuleMatch.ENDS_WITH -> ":matches"
        else -> ":contains"
    }

    /**
     * The quoted key for a text condition.
     *
     * 🔴 "starts with" and "ends with" are Sieve's `:matches` with one anchoring `*`, so the typed
     * value has to be GLOB-escaped first or a reader looking for "50% off?" would get a wildcard
     * instead of the characters they typed. [glob] escapes for the pattern language, [escape]
     * escapes for the quoted string around it, and the anchoring `*` is added after both so that
     * one stays a wildcard.
     */
    private fun keyString(condition: RuleCondition): String = when (condition.match) {
        RuleMatch.STARTS_WITH -> "\"${escape(glob(condition.value))}*\""
        RuleMatch.ENDS_WITH -> "\"*${escape(glob(condition.value))}\""
        else -> "\"${escape(condition.value)}\""
    }

    /** Escape a value for a Sieve quoted-string (backslash and double-quote). */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Escape a value so `:matches` reads it literally: backslash, `*` and `?` lose their meaning. */
    private fun glob(value: String): String =
        value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")

    /**
     * A v1 rule, kept only to read scripts written before conditions became a list.
     *
     * Never written. [toRule] lifts the flat condition into a one-entry list, which is exactly
     * what the rule always meant.
     */
    @Serializable
    private data class LegacyRule(
        val name: String = "",
        val enabled: Boolean = true,
        val field: RuleField = RuleField.FROM,
        val match: RuleMatch = RuleMatch.CONTAINS,
        val value: String = "",
        val moveTo: String? = null,
        val markRead: Boolean = false,
        val flag: Boolean = false,
    ) {
        fun toRule() = FilterRule(
            name = name,
            enabled = enabled,
            conditions = listOf(RuleCondition(field = field, match = match, value = value)),
            moveTo = moveTo,
            markRead = markRead,
            flag = flag,
        )
    }
}
