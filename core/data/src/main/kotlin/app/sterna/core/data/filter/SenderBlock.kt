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
 * Whether a block rule may target [address] AT ALL — asked before anything the account's script
 * says, because these two noes are about the ADDRESS and no state of the server changes them.
 *
 * Both were found on a device, on the gesture offered from the open message (`banc-1.4.8.md`
 * § 4 and § 5.3):
 *
 *  - **one's own address.** `FROM :is <me> → Trash, marked read` files one's OWN mail away:
 *    every message sent to oneself, every reply a mailing list echoes back, everything an alias
 *    of the account posts. It is the one rule in this file that cannot be undone by ignoring it,
 *    because the mail it eats is the mail one would have gone looking for. The reader reaches it
 *    from the Sent folder; the per-sender screen reaches it too — a message from oneself filed in
 *    an ordinary folder is counted like any other, and its row then offers the gesture.
 *  - **no address at all.** A `From:` carrying only a display name maps to `email = ""`
 *    (`EmailMapper`), and `FROM :is ""` is a rule about nobody: it matches no mail, it cannot be
 *    read back as anything, and it sits in the account's script for ever.
 *
 * [ownAddresses] is the account's send-as identities plus the login it authenticates with. They
 * are compared the way [alreadyBlocked] compares — trimmed, case ignored — because that is how
 * the rule is written and how Sieve's `address :is "from"` reads it back.
 */
fun blockableSender(address: String, ownAddresses: List<String>): Boolean {
    val target = address.trim()
    if (target.isEmpty()) return false
    return ownAddresses.none { it.trim().equals(target, ignoreCase = true) }
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
 *
 * And it refuses outright when [FilterRulesState.Loaded.foreignActiveScript] is set. Saving does
 * not merely write Sterna's script, it ACTIVATES it — which switches off whatever other Sieve
 * script the account had running. The filter editor warns about that in red before its Save
 * button; a one-finger gesture in a list has nowhere to put such a warning, so it must not be
 * able to do it. The guard lives HERE and not only in the screen: an unreachable path is not a
 * safe one, it is a path whose only guard is the way in.
 */
suspend fun addBlockRule(
    address: String,
    trashFolder: String,
    load: suspend () -> FilterRulesState,
    save: suspend (List<FilterRule>) -> Unit,
): BlockOutcome {
    val state = runCatching { load() }.getOrNull()
    if (state !is FilterRulesState.Loaded) return BlockOutcome.FAILED
    // The duplicate check answers BEFORE the foreign-script refusal, and the order is the
    // message. Neither branch writes anything, so they differ only in what the screen then says:
    // "couldn't complete the action" reports that something went wrong, and on an address the
    // script already handles nothing did — the rule she is being told about is already there.
    if (alreadyBlocked(state.rules, address)) return BlockOutcome.ALREADY_PRESENT
    if (state.foreignActiveScript) return BlockOutcome.FAILED
    return runCatching { save(withBlockRule(state.rules, address, trashFolder)) }
        .fold(onSuccess = { BlockOutcome.ADDED }, onFailure = { BlockOutcome.FAILED })
}
