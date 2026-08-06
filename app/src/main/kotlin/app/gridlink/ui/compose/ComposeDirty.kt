package app.gridlink.ui.compose

/**
 * The unsaved-changes verdict, pulled out of the composable so it can be unit-tested (#70/#94).
 *
 * Two mistakes it exists to avoid:
 *  - counting a PRE-FILLED field as an edit. A message reopened from the outbox (or a forward) opens
 *    with Cc/Bcc and attachments already set; comparing them against "" or "any attachment" made the
 *    discard dialog fire on a close where the user changed nothing (#70 BLOCKER 3).
 *  - comparing recipient fields as STRINGS. Tapping a chip to edit it reorders the field, so a
 *    string compare saw a change where the set of addresses was identical, again firing the dialog
 *    on an untouched reply (#94).
 *
 * So recipients are compared as order-independent sets, subject/body as text, and attachments via an
 * explicit "the user touched them" flag rather than their mere presence.
 */
internal object ComposeDirty {
    private fun addressSet(value: String): Set<String> =
        recipientTokens(value).map { it.trim().lowercase() }.toSet()

    fun isDirty(
        onlyCopy: Boolean,
        to: String,
        initialTo: String,
        cc: String,
        initialCc: String,
        bcc: String,
        initialBcc: String,
        subject: String,
        initialSubject: String,
        body: String,
        initialBody: String,
        attachmentsTouched: Boolean,
    ): Boolean =
        // An undone send lives only on this screen: guard it even untouched, closing destroys it.
        onlyCopy ||
            addressSet(to) != addressSet(initialTo) ||
            addressSet(cc) != addressSet(initialCc) ||
            addressSet(bcc) != addressSet(initialBcc) ||
            subject != initialSubject ||
            body != initialBody ||
            attachmentsTouched
}
