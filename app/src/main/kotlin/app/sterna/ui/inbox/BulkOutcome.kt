package app.sterna.ui.inbox

/** What a finished bulk action has to tell the user. */
internal enum class BulkOutcome {
    /** Everything the batches were given went through — say nothing. */
    NONE,

    /** Some ids went through and some did not. */
    PARTIAL,

    /** Nothing went through. */
    TOTAL,
}

/**
 * Which of the three a bulk action landed on, from the number of messages the action set out to
 * handle ([attempted]) and the number it did not ([failed]).
 *
 * [attempted] is the SELECTION size, not the number of rows the cache happened to return: every
 * bulk path now resolves the whole selection, and a key nothing could be resolved for was still
 * something the user asked for. It counts on BOTH sides — attempted and failed — because counting
 * it as failed alone would say "some of it failed" over an action that reached everything, while
 * counting it as neither lets `failed <= 0 -> NONE` swallow a batch that reached NOTHING: ten
 * search results selected, none of them acted on, and not a word said. That silence is the report
 * this lot exists to close.
 *
 * Returns the enum rather than a string resource so this decision runs in a plain JVM test —
 * `InboxViewModel` is an `AndroidViewModel` and cannot be instantiated in one.
 */
internal fun bulkOutcome(attempted: Int, failed: Int): BulkOutcome = when {
    attempted <= 0 || failed <= 0 -> BulkOutcome.NONE
    failed >= attempted -> BulkOutcome.TOTAL
    else -> BulkOutcome.PARTIAL
}
