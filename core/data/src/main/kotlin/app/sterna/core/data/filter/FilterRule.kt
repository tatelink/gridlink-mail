package app.sterna.core.data.filter

import kotlinx.serialization.Serializable

/** Which message field a rule tests. */
@Serializable
enum class RuleField { FROM, TO, CC, SUBJECT }

/** How the field is compared to the value. */
@Serializable
enum class RuleMatch { CONTAINS, IS }

/**
 * One server-side filter rule, as edited in the form UI. Compiled to Sieve by
 * [SieveCodec] and round-tripped via a JSON metadata comment in the script, so
 * the app never has to parse arbitrary Sieve.
 *
 * v1: a single condition plus a curated, non-destructive action set (move to a
 * folder, mark read, flag). Multiple conditions and destructive actions are left
 * for later.
 */
@Serializable
data class FilterRule(
    val name: String = "",
    val enabled: Boolean = true,
    val field: RuleField = RuleField.FROM,
    val match: RuleMatch = RuleMatch.CONTAINS,
    val value: String = "",
    /** Target mailbox name to file into, or null for no move. */
    val moveTo: String? = null,
    val markRead: Boolean = false,
    val flag: Boolean = false,
)
