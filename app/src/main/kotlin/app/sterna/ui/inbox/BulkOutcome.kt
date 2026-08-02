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
 * Which of the three a bulk action landed on, from the number of rows actually handed to the
 * batches ([attempted]) and the number that came back rejected ([failed]).
 *
 * [attempted] is the cached-row count, not the selection size: a selected key with no cached row
 * is never passed to any batch, so counting it would turn a complete success into a "failure".
 *
 * Returns the enum rather than a string resource so this decision runs in a plain JVM test —
 * `InboxViewModel` is an `AndroidViewModel` and cannot be instantiated in one.
 */
internal fun bulkOutcome(attempted: Int, failed: Int): BulkOutcome = when {
    attempted <= 0 || failed <= 0 -> BulkOutcome.NONE
    failed >= attempted -> BulkOutcome.TOTAL
    else -> BulkOutcome.PARTIAL
}
