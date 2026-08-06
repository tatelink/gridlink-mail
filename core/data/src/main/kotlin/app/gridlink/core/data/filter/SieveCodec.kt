package app.gridlink.core.data.filter

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Compiles structured [FilterRule]s to a Sieve script and reads them back.
 *
 * The round-trip trick: the full rule list is serialized to JSON and stored on a
 * single `# GRIDLINK-RULES-V1:` comment line at the top of the script. Generation
 * is one-way (rules → Sieve); reading just parses that JSON comment, so we never
 * parse arbitrary Sieve. Disabled rules are kept in the JSON but not emitted as
 * Sieve, so they survive a save without filtering mail.
 */
object SieveCodec {
    const val SCRIPT_NAME = "gridlink"
    private const val MARKER = "# GRIDLINK-RULES-V1:"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FilterRule.serializer())

    /** Build the Sieve script (with embedded JSON metadata) for [rules]. */
    fun generate(rules: List<FilterRule>): String {
        val sb = StringBuilder()
        sb.append(MARKER).append(' ').append(json.encodeToString(serializer, rules)).append('\n')

        val active = rules.filter { it.enabled && it.value.isNotBlank() }
        val requires = buildList {
            if (active.any { it.moveTo != null }) add("fileinto")
            if (active.any { it.markRead || it.flag }) add("imap4flags")
        }
        if (requires.isNotEmpty()) {
            sb.append("require [").append(requires.joinToString(", ") { "\"$it\"" }).append("];\n")
        }
        sb.append('\n')
        for (rule in active) sb.append(ruleToSieve(rule)).append('\n')
        return sb.toString()
    }

    /** Parse the rule list out of a script's JSON metadata comment (empty if none). */
    fun parseRules(script: String): List<FilterRule> {
        val line = script.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(MARKER) } ?: return emptyList()
        val payload = line.removePrefix(MARKER).trim()
        return runCatching { json.decodeFromString(serializer, payload) }.getOrDefault(emptyList())
    }

    private fun ruleToSieve(rule: FilterRule): String {
        val matchTag = if (rule.match == RuleMatch.IS) ":is" else ":contains"
        val value = escape(rule.value)
        val test = when (rule.field) {
            RuleField.SUBJECT -> "header $matchTag \"subject\" \"$value\""
            RuleField.FROM -> "address $matchTag \"from\" \"$value\""
            RuleField.TO -> "address $matchTag \"to\" \"$value\""
            RuleField.CC -> "address $matchTag \"cc\" \"$value\""
        }
        val body = StringBuilder()
        // Flags must be set before fileinto/keep so the filed copy carries them.
        if (rule.markRead) body.append("    addflag \"\\\\Seen\";\n")
        if (rule.flag) body.append("    addflag \"\\\\Flagged\";\n")
        if (rule.moveTo != null) body.append("    fileinto \"${escape(rule.moveTo)}\";\n")
        return "if $test {\n$body}"
    }

    /** Escape a value for a Sieve quoted-string (backslash and double-quote). */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
