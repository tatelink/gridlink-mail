package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

/**
 * What reading an account's server-side filters answers — the decision behind
 * `MailRepository.loadFilterRules`, as a plain function two arguments can execute, because the
 * repository itself needs a session, a JMAP client and a device to exist.
 *
 * [sternaScript] is the body of the account's `sterna` script, or null when the account has none
 * at all. [otherActiveScript] is whether some OTHER script is the active one.
 *
 * **The two ways this answer says "do not write here".** They add up into
 * [FilterRulesState.Loaded.foreignActiveScript] on purpose: that one flag is what the two
 * surfaces of the per-sender GESTURE already refuse — the reader's menu entry and the by-sender
 * list — so the write that started this is stopped without a new state to thread through them.
 *
 * ⚠ It is not refused everywhere. The **Filters screen** writes without consulting it: its Save
 * button rewrites the whole script from the list on screen, and that path is deliberately left
 * open because it is the only way out of "the rules are not running" (the arbitration, and what
 * it costs, are in `ai-work/issues/findings/finding-script-sieve-ecrase-par-ecran-filtres.md`).
 * What that screen got instead is a red line of its own that names the overwrite —
 * [ForeignScriptNotice.UNREADABLE_SCRIPT], fed by [FilterRulesState.Loaded.scriptUnreadable]
 * below, because the fold above cannot tell the two causes apart once they are one boolean.
 *
 * The two causes:
 *
 *  - **another script is active.** Saving does not merely write Sterna's script, it ACTIVATES
 *    it, switching off whatever was running. Known since the filter editor learned to warn in
 *    red before its Save button.
 *  - **our own script is there and unreadable** ([SieveCodec.parseRulesOrNull] answering null:
 *    content, but no marker or broken JSON). This one was silent. An unreadable script read as
 *    "zero rules" made the per-sender gesture available, and confirming it recompiled the whole
 *    script from that single new rule — deleting the content nobody had managed to read.
 *    `SenderBlock.addBlockRule` promises in as many words that a read which is not `Loaded`
 *    ends the gesture with no write at all, "never a 'start from an empty list and hope'"; it
 *    was the layer underneath that handed it an empty list and called it a successful read.
 *
 * Deliberately NOT conditioned on whether the `sterna` script is the active one. An unreadable
 * script that is merely inactive is overwritten by the very same save — its content is no less
 * lost for having been switched off.
 */
fun loadedFilterRules(sternaScript: String?, otherActiveScript: Boolean): FilterRulesState.Loaded {
    val parsed = sternaScript?.let { SieveCodec.parseRulesOrNull(it) }
    val unreadable = sternaScript != null && parsed == null
    return FilterRulesState.Loaded(
        rules = parsed.orEmpty(),
        foreignActiveScript = otherActiveScript || unreadable,
        scriptUnreadable = unreadable,
    )
}
