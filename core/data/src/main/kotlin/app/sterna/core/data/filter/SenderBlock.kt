package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

/**
 * The rule the per-sender screen writes, and the arithmetic around writing it — as plain
 * functions, so what the screen decides can be EXECUTED by a test instead of read back out of a
 * ViewModel that no JVM test can instantiate.
 */

/**
 * "File future mail from [address] into [trashFolder], already read."
 *
 * [RuleMatch.IS] and the WHOLE address, not `CONTAINS`: `a+news@` and `a+facture@` are two
 * different subscriptions, and Sieve's `address :is "from"` tests the address — the same thing
 * the screen grouped on. A `CONTAINS` on `a@` would swallow both, and much else.
 *
 * Trash **and** marked read, and nothing else: the mail lands somewhere it can be fished back
 * out of until the server purges it, and it lights no badge on the way. Not Junk (this is not
 * spam, and poisoning the folder one hunts false positives in is a poor trade), not "mark read"
 * alone (the mail would keep piling up in the inbox), and above all not a Sieve `discard` or
 * `reject` — [SieveCodec] can emit neither, and it must stay that way: a one-finger gesture in a
 * list that destroys server-side mail with no undo is not a feature this app offers.
 *
 * The rule is NAMED by the address alone. The filter editor already prints a localised summary
 * beside the name ("From is news@example.com → Trash, mark read"), so a prefix invented here
 * would be untranslated vocabulary added to the server's script for no gain.
 */
fun blockRule(address: String, trashFolder: String): FilterRule = FilterRule(
    name = address,
    enabled = true,
    field = RuleField.FROM,
    match = RuleMatch.IS,
    value = address,
    moveTo = trashFolder,
    markRead = true,
)

/**
 * Whether [rules] already send [address] away — a FROM/IS rule on the same address, case
 * ignored, since that is how the screen groups. A `CONTAINS` rule, or one on another field, is
 * not this rule: it may or may not catch the same mail, and guessing would either add a
 * duplicate or silently refuse to add anything.
 */
fun alreadyBlocked(rules: List<FilterRule>, address: String): Boolean = rules.any {
    it.field == RuleField.FROM && it.match == RuleMatch.IS && it.value.equals(address, ignoreCase = true)
}

/**
 * [loaded] plus the new rule — ADDED, never substituted.
 *
 * This is the trap the whole path exists around: `MailRepository.saveFilterRules` recompiles and
 * reactivates the WHOLE script, so saving `listOf(newRule)` does not "add a filter", it deletes
 * every filter the account had. Returns [loaded] untouched when the address is already handled.
 */
fun withBlockRule(loaded: List<FilterRule>, address: String, trashFolder: String): List<FilterRule> =
    if (alreadyBlocked(loaded, address)) loaded else loaded + blockRule(address, trashFolder)

/** What [addBlockRule] did. */
enum class BlockOutcome {
    /** The script now carries the rule, on top of everything it already carried. */
    ADDED,

    /** A FROM/IS rule for this address was already there; nothing was written. */
    ALREADY_PRESENT,

    /** The rules could not be read, or the save was refused. NOTHING was written. */
    FAILED,
}

/**
 * Add the block rule for [address] to the account's server-side script: read the rules, then
 * write them back with one more.
 *
 * [load] and [save] are passed in rather than reached for, so this decision — the ORDER, and
 * what happens when the read fails — can be executed by a JVM test with two fakes.
 *
 * The order is the point. `save` rewrites the entire script, so a save built on anything other
 * than a successful, complete read wipes the account's filters. A read that throws, or that
 * answers anything but [FilterRulesState.Loaded] (an IMAP account, a JMAP server with no Sieve
 * capability), therefore ends the gesture at [BlockOutcome.FAILED] with no write at all — never
 * a "start from an empty list and hope".
 */
suspend fun addBlockRule(
    address: String,
    trashFolder: String,
    load: suspend () -> FilterRulesState,
    save: suspend (List<FilterRule>) -> Unit,
): BlockOutcome {
    val state = runCatching { load() }.getOrNull()
    if (state !is FilterRulesState.Loaded) return BlockOutcome.FAILED
    if (alreadyBlocked(state.rules, address)) return BlockOutcome.ALREADY_PRESENT
    return runCatching { save(withBlockRule(state.rules, address, trashFolder)) }
        .fold(onSuccess = { BlockOutcome.ADDED }, onFailure = { BlockOutcome.FAILED })
}
