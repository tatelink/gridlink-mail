package app.gridlink.core.data.filter

import kotlinx.serialization.Serializable

/**
 * The SHAPE of a test, which is what decides whether a [RuleField] and a [RuleMatch] can be paired
 * and whether a value is typed at all.
 *
 * 🔴 Without this the editor would offer "Attachment is exactly ___" and "Size contains ___", and
 * the codec would have to emit something for them. Three shapes cover every field worth filtering
 * on, so the pairing is checked by kind rather than by a table of special cases:
 *
 * - [TEXT] compares a header (or the body) to a string the reader types.
 * - [SIZE] compares the whole message to a number of kilobytes.
 * - [PRESENCE] asks whether something is there at all, and takes no value.
 */
@Serializable
enum class FieldKind { TEXT, SIZE, PRESENCE }

/**
 * Which part of the message a condition tests.
 *
 * ⚠️ [HAS_ATTACHMENT] is the one entry that is a HEURISTIC rather than a fact. Sieve has no
 * attachment test: the script looks for a `multipart/mixed` content type, which is how mail with
 * a file attached is nearly always assembled but is not a guarantee in either direction. The
 * editor says so out loud rather than letting it look exact, because a filter that quietly misses
 * one message in fifty is worse than one the reader knows to check.
 *
 * ⚠️ The names are WIRE VALUES. They are written into the JSON metadata comment on the server's
 * script, so renaming one silently drops that condition from every rule already saved. Add
 * entries; do not rename them.
 */
@Serializable
enum class RuleField(val kind: FieldKind) {
    FROM(FieldKind.TEXT),
    TO(FieldKind.TEXT),
    CC(FieldKind.TEXT),
    TO_OR_CC(FieldKind.TEXT),
    SUBJECT(FieldKind.TEXT),
    BODY(FieldKind.TEXT),
    LIST_ID(FieldKind.TEXT),
    HAS_ATTACHMENT(FieldKind.PRESENCE),
    SIZE(FieldKind.SIZE),
    ;

    /** The matches this field can be paired with, in the order the editor offers them. */
    val matches: List<RuleMatch> get() = RuleMatch.entries.filter { it.kind == kind }
}

/**
 * How a [RuleField] is compared.
 *
 * ⚠️ Wire values, exactly as for [RuleField]: add, never rename.
 */
@Serializable
enum class RuleMatch(val kind: FieldKind) {
    CONTAINS(FieldKind.TEXT),
    NOT_CONTAINS(FieldKind.TEXT),
    IS(FieldKind.TEXT),
    NOT_IS(FieldKind.TEXT),
    STARTS_WITH(FieldKind.TEXT),
    ENDS_WITH(FieldKind.TEXT),
    OVER(FieldKind.SIZE),
    UNDER(FieldKind.SIZE),
    PRESENT(FieldKind.PRESENCE),
    ABSENT(FieldKind.PRESENCE),
}

/** Whether every condition has to hold, or just one of them. */
@Serializable
enum class RuleMatchMode { ALL, ANY }

/**
 * One test inside a rule: a field, how it is compared, and what it is compared to.
 *
 * [value] is a plain string for every kind, including [FieldKind.SIZE] where it holds a whole
 * number of kilobytes. One field rather than a sealed hierarchy because this is what goes through
 * a single text box in the editor and a single JSON key on the wire; [sizeKb] is the one place
 * that reads it as a number, and it refuses anything that is not one.
 */
@Serializable
data class RuleCondition(
    val field: RuleField = RuleField.FROM,
    val match: RuleMatch = RuleMatch.CONTAINS,
    val value: String = "",
) {
    /** [value] as a positive whole number of kilobytes, or null when it is not one. */
    val sizeKb: Int? get() = value.trim().toIntOrNull()?.takeIf { it > 0 }

    /**
     * True when this condition cannot be compiled into a test.
     *
     * 🔴 A [FieldKind.PRESENCE] condition is NEVER blank: "has an attachment" is a complete
     * question with nothing left to type. Treating it as blank the way an empty text box is
     * treated would drop the only condition the reader entered and leave a rule that filters
     * nothing.
     */
    val isBlank: Boolean
        // this.field, not field: inside an accessor a bare `field` is the backing-field keyword.
        get() = when (this.field.kind) {
            FieldKind.TEXT -> value.isBlank()
            FieldKind.SIZE -> sizeKb == null
            FieldKind.PRESENCE -> false
        }

    /**
     * Move to another field, dragging [match] and [value] into range.
     *
     * 🔴 Not a bare `copy(field = ...)`: the editor lets any field be picked at any time, so
     * "Subject contains invoice" → Size would otherwise become "Size contains invoice", which the
     * codec cannot emit and the summary cannot read. Changing kind resets both; staying inside a
     * kind keeps what was typed, so Sender → Recipient does not clear the address.
     */
    fun withField(newField: RuleField): RuleCondition {
        if (newField == field) return this
        if (newField.kind == field.kind) return copy(field = newField)
        return RuleCondition(field = newField, match = newField.matches.first())
    }
}

/**
 * One server-side filter rule, as edited in the form UI. Compiled to Sieve by [SieveCodec] and
 * round-tripped via a JSON metadata comment in the script, so the app never has to parse
 * arbitrary Sieve.
 *
 * ## What v2 added, and what it still refuses
 * v1 was one condition and three actions. v2 is a LIST of conditions joined by [mode], a wider
 * field and match set, and two more actions ([addTag], [stop]).
 *
 * 🔴 Still no destructive action. There is deliberately no "delete", no "discard" and no
 * "reject": those run on the server, on mail the reader has never seen, and a rule with a typo in
 * it would throw away mail with no trace and no undo. Filing it into a folder is recoverable;
 * discarding it is not. If that is ever revisited it needs a confirmation in the editor and a
 * plain warning, not just another switch in the Then section.
 *
 * @param mode ignored when there is one condition, and hidden by the editor in that case.
 * @param addTag a tag KEYWORD (the wire name), not a label. The label and colour live on this
 *   device only, so a rule that stored the label would file mail under a name the server does not
 *   know. See [app.gridlink.core.data.settings.MailTag].
 * @param stop `stop;` in Sieve: later rules are not considered for this message. The one control
 *   that makes rule ORDER matter, which is why it is worded as "stop processing" rather than
 *   something shorter.
 */
@Serializable
data class FilterRule(
    val name: String = "",
    val enabled: Boolean = true,
    val mode: RuleMatchMode = RuleMatchMode.ALL,
    val conditions: List<RuleCondition> = listOf(RuleCondition()),
    /** Target mailbox name to file into, or null for no move. */
    val moveTo: String? = null,
    val markRead: Boolean = false,
    val flag: Boolean = false,
    val addTag: String? = null,
    val stop: Boolean = false,
) {
    /** The conditions that can actually be compiled; a half-typed row is not one of them. */
    val activeConditions: List<RuleCondition> get() = conditions.filterNot { it.isBlank }

    /** The conditions to DRAW, which is never none: an empty rule still needs one row to type in. */
    val editableConditions: List<RuleCondition>
        get() = conditions.ifEmpty { listOf(RuleCondition()) }

    /** True when the rule would do something to a message it matched. */
    val hasAction: Boolean get() = moveTo != null || markRead || flag || addTag != null || stop

    /**
     * True for an untouched rule: no name, nothing to test and nothing to do. Such a rule can
     * neither filter nor be recognised, so it is dropped rather than kept in the list or written
     * to the script.
     */
    val isEmpty: Boolean
        get() = name.isBlank() && activeConditions.isEmpty() && !hasAction
}
