package app.sterna.core.data.filter

/**
 * The name a server that implements the vacation responder as a Sieve script gives that
 * script. Its EXISTENCE is the only observable proof we have that this server couples the
 * two: nothing in `VacationResponse/get`, nor in the session, says so. Measured against
 * Stalwart; the repo knows nothing about Cyrus, Fastmail or Apache James, and deliberately
 * carries no list of server names — a table like that is paid for over years.
 */
const val VACATION_SCRIPT_NAME = "vacation"

/**
 * What the account's Sieve scripts say about the user's filter rules, as observed on the
 * server. Six facts, nothing else: enough to decide [filterScriptWarning] and
 * [foreignScriptNotice], and small enough that no screen has to reason about scripts.
 *
 * The default is the "we know nothing" value: no script, so no warning. It is what an IMAP
 * account and a server without the Sieve capability produce. A FAILED read is NOT this value —
 * it is `null`, because "nothing to report" and "I could not look" are different things and the
 * screens act on the difference (see [refreshedFilterWarning]).
 */
data class FilterScriptStatus(
    /** A script named `sterna` exists on the server. */
    val scriptExists: Boolean = false,
    /** That script is the ACTIVE one. A server keeps a single active script per account. */
    val scriptActive: Boolean = false,
    /** How many rules it carries that actually filter mail (see [enabledRuleCount]). */
    val enabledRuleCount: Int = 0,
    /**
     * A script named [VACATION_SCRIPT_NAME] exists next to it. EXISTENCE, not activity: this is
     * what [filterScriptWarning] predicts on ("this server materialises the responder as a Sieve
     * script, so switching it on will stop the rules"), and that prediction needs nothing more.
     */
    val vacationScriptExists: Boolean = false,
    /**
     * That `vacation` script is the ACTIVE one — the responder is running right now, on the
     * evidence of the same list. Read next to the name, on the same object, at no extra cost.
     *
     * Separate from [vacationScriptExists] because the two answer different questions and only
     * one of them is about the present. Saying "your auto-reply is running, saving will stop it"
     * on mere existence is false on an account whose active script is a third one (`roundcube`,
     * `managesieve`), and false again when the refusal came from a `sterna` script we could not
     * read rather than from any foreign script at all.
     */
    val vacationScriptActive: Boolean = false,
    /**
     * Some script OTHER than `sterna` is the active one — so a save, which activates Sterna's
     * script, switches that other one off. Read from the same script list as the rest: no
     * second round trip.
     */
    val foreignActive: Boolean = false,
)

/** What a screen must tell the user about the state of their filter rules, or null for nothing. */
enum class FilterScriptWarning {
    /**
     * The rules exist but are NOT running right now: the script that carries them is not the
     * active one. This is the state a server leaves the account in after the vacation
     * responder has been turned on and then off again — measured 2026-08-04: no script at all
     * is active, and the rules stay dead until the filters are saved by hand.
     */
    RULES_NOT_RUNNING,

    /**
     * The rules ARE running, but this server materialises the responder as a Sieve script, so
     * turning the responder on will make that script the active one and stop the rules.
     */
    RESPONDER_WILL_SUSPEND_RULES,
}

/**
 * Decide, from what the server shows, which warning (if any) the vacation and filter screens
 * must carry. A pure function on purpose: it is the decision, and the decision is what has to
 * be testable — the two screens that use it live in composables and an [android.app.Application]-bound
 * view model, neither of which runs in a JVM test.
 *
 * The order of the guards is the whole content:
 * - no script: a fresh account that never had a filter. Nothing to say.
 * - no rule that filters: an empty or fully disabled script. Warning about it would be a false
 *   alarm about nothing, which is how a warning stops being read.
 * - script present but inactive: the rules are not applying. A fact, observed, with nothing
 *   supposed about why or about how this server works. This is the one that matters.
 * - script active, a `vacation` script exists, and the responder is KNOWN to be off: a
 *   prediction, and it is only honest because of those last two conditions. On a server that
 *   keeps the responder away from Sieve, that script does not exist and nothing is claimed; and
 *   telling somebody whose responder is already running that switching it on will suspend their
 *   rules is false in the present tense — the rules they can see are running.
 *
 * @param responderEnabled whether the responder is on, or null when the caller does not know.
 *   Unknown is NOT "off": the filters screen never reads the responder, and a prediction made on
 *   its behalf would be a guess. Only a known-off responder can be predicted about.
 */
fun filterScriptWarning(
    scriptExists: Boolean,
    scriptActive: Boolean,
    enabledRuleCount: Int,
    vacationScriptExists: Boolean,
    responderEnabled: Boolean?,
): FilterScriptWarning? = when {
    !scriptExists -> null
    enabledRuleCount == 0 -> null
    !scriptActive -> FilterScriptWarning.RULES_NOT_RUNNING
    vacationScriptExists && responderEnabled == false -> FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES
    else -> null
}

/**
 * The warning to show after a status read that may have failed.
 *
 * `status == null` means the read failed, NOT "nothing to report", and the two must not be
 * confused: the clean case is the return from holiday. The responder is switched off, the server
 * leaves no script active at all, the rules are dead — and if the read that follows the save
 * fails, replacing the warning with "nothing" ERASES the line at the exact moment it becomes
 * true, over a screen that has just written to the server. So a failed read changes nothing:
 * what was on screen stays on screen, right or wrong, until a read succeeds.
 *
 * It is also the shape of the fix for the other half: a read that DOES succeed replaces the
 * warning outright, including with `null`. A stale red line that survives a good read is the
 * same lie the other way round.
 */
fun refreshedFilterWarning(
    previous: FilterScriptWarning?,
    status: FilterScriptStatus?,
    responderEnabled: Boolean?,
): FilterScriptWarning? = if (status == null) {
    previous
} else {
    filterScriptWarning(
        scriptExists = status.scriptExists,
        scriptActive = status.scriptActive,
        enabledRuleCount = status.enabledRuleCount,
        vacationScriptExists = status.vacationScriptExists,
        responderEnabled = responderEnabled,
    )
}

/** Which "another script is active" line the filters screen must carry, or null for none. */
enum class ForeignScriptNotice {
    /** Some other script is active. Whose, and what it does, is unknown — so nothing is claimed. */
    ANOTHER_SCRIPT,

    /**
     * The `vacation` script IS the active one: the auto-reply is running, so what Save costs is
     * the auto-reply, and that is what the line must say.
     */
    STOPS_AUTO_REPLY,

    /**
     * This account has a `sterna` script nobody could parse. Until now the only thing said about
     * it was one of the two lines above — a sentence about somebody else's script, or about an
     * auto-reply — and neither names what a save would actually do here: replace content no one
     * has read.
     */
    UNREADABLE_SCRIPT,
}

/**
 * Which foreign-script line the filters screen shows above its Save button.
 *
 * Save does not merely write Sterna's script, it ACTIVATES it, and a server keeps one active
 * script per account: pressing Save while away switches the vacation responder off. The screen
 * did say something — "another filter script is active on the server" — but nothing in this app
 * ever calls the auto-reply a *filter script*, so the sentence named the cause and hid the
 * consequence, above a button this release opens without any edit having been made.
 *
 * The order of the guards is the whole content:
 * - [scriptUnreadable] FIRST, before anything about another script. It is the only state where
 *   pressing Save destroys something: the account's own script holds content this app could not
 *   parse, and a save rewrites the whole script from the list on screen. The user is entitled to
 *   read THAT before she reads what the save costs the auto-reply — and the cause is otherwise
 *   invisible, since it reaches this screen folded into the same flag as "another script is
 *   active" (the fold is deliberate, it is what keeps the write refused elsewhere).
 * - no foreign script active: our own script is the active one, nothing to say.
 * - [vacationScriptActive]: the `vacation` script is the one running, so what Save switches off
 *   is the auto-reply. ACTIVITY, not existence — an inactive `vacation` script sitting beside a
 *   third active one would otherwise make the app accuse the responder of something another
 *   script is doing, and an unreadable `sterna` script with nothing foreign active at all would
 *   make it promise a stop that never happens.
 * - anything else foreign and active: the generic line, which claims nothing about whose it is.
 *
 * ONE line out of these three, never two: they all sit above one button, and two red sentences
 * over one button is how both stop being read. ⚠ That promise is about THIS decision only. The
 * filters screen also carries [FilterScriptWarning.RULES_NOT_RUNNING] above it, from a different
 * fact, and during an absence the two DO stack — truthfully: the rules are stopped, and saving
 * would stop the auto-reply.
 */
fun foreignScriptNotice(
    scriptUnreadable: Boolean,
    foreignActive: Boolean,
    vacationScriptActive: Boolean,
): ForeignScriptNotice? = when {
    scriptUnreadable -> ForeignScriptNotice.UNREADABLE_SCRIPT
    !foreignActive -> null
    vacationScriptActive -> ForeignScriptNotice.STOPS_AUTO_REPLY
    else -> ForeignScriptNotice.ANOTHER_SCRIPT
}

/** Which sentence the RESPONDER screen puts at its head, or null for none. */
enum class VacationFilterLine {
    /** The rules are not running, and the way to put them back is named. */
    RULES_NOT_RUNNING_WITH_REMEDY,

    /** The rules are not running. The fact alone: the remedy would undo this very screen. */
    RULES_NOT_RUNNING_FACT,

    /** Switching the responder on will suspend the rules. */
    RESPONDER_WILL_SUSPEND_RULES,
}

/**
 * Which of the two "not running" sentences the responder screen shows, given what its own switch
 * says.
 *
 * The remedy — "you can put them back in Settings → Filters" — was printed on
 * [FilterScriptWarning.RULES_NOT_RUNNING] with no condition at all. But while the user is AWAY
 * that warning is true *because the responder is running*, so the screen whose switch reads ON
 * was offering, in the same breath, the one gesture that switches it back OFF, without saying so:
 * Save on the filters screen activates Sterna's script, and a server keeps one active script per
 * account.
 *
 * So the remedy is offered only when the responder is known OFF — the return from holiday, which
 * is the case it was written for. With the responder on, the screen states the fact and stops
 * there: [FilterScriptWarning] does not change, the sentence does.
 *
 * @param responderEnabled what the switch on that screen shows. Not the server's copy: the
 *   sentence sits three rows above the switch, and a sentence contradicting the widget beside it
 *   is the same lie in the other direction.
 */
fun vacationFilterLine(warning: FilterScriptWarning?, responderEnabled: Boolean): VacationFilterLine? =
    when (warning) {
        null -> null
        FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES -> VacationFilterLine.RESPONDER_WILL_SUSPEND_RULES
        FilterScriptWarning.RULES_NOT_RUNNING ->
            if (responderEnabled) {
                VacationFilterLine.RULES_NOT_RUNNING_FACT
            } else {
                VacationFilterLine.RULES_NOT_RUNNING_WITH_REMEDY
            }
    }

/**
 * Whether [warning] is the statement of fact — the rules are on the server and not running.
 *
 * A function rather than a comparison written at the call site: it is the filters screen's whole
 * reading of the decision (that screen shows the fact and leaves the prediction to the responder
 * screen), and it gates the Save button that is the way out of the state. Inverted by accident it
 * would shout in the nominal case and go quiet in the broken one, so it is executed by a test
 * instead of being trusted.
 */
fun rulesAreNotRunning(warning: FilterScriptWarning?): Boolean = warning == FilterScriptWarning.RULES_NOT_RUNNING

/**
 * How many of [rules] actually filter mail: enabled, and with something to match on. Same
 * predicate as the one [SieveCodec.generate] emits Sieve for — a rule it drops filters
 * nothing, so it cannot be the reason to alarm the user.
 */
fun enabledRuleCount(rules: List<FilterRule>): Int = rules.count { it.enabled && it.value.isNotBlank() }
