package app.sterna.core.data.mail

/**
 * Outcome of [MailRepository.saveDraft]. [ORIGINAL_KEPT] means the new draft could NOT reproduce
 * everything the edited one held — an attachment that couldn't be re-attached, or an HTML body the
 * plain-text editor flattened — so the original was deliberately left on the server: a duplicate
 * in Drafts is recoverable, a destroyed original is not (#63).
 */
enum class DraftSaveOutcome { SAVED, ORIGINAL_KEPT }

/**
 * Whether a re-saved draft reproduced the one it replaces, i.e. whether the original may be
 * destroyed. Pure decision logic of [MailRepository.saveDraft], extracted for unit tests:
 *
 * - every attachment handed to the save must have made it into the new draft
 *   ([attachmentsCarried] == [attachmentsIn]) — compose shows those parts as chips, so dropping
 *   them AND destroying the original would delete files the user was just looking at;
 * - [bodyIsLossy] marks content this composer cannot give back (a draft authored elsewhere in
 *   HTML is flattened to plain text on open; inline images and calendar parts aren't carried).
 *
 * Anything else is a duplicate in Drafts, which the user can delete. Data loss, they cannot undo.
 */
internal fun draftReplacementIsFaithful(
    attachmentsIn: Int,
    attachmentsCarried: Int,
    bodyIsLossy: Boolean,
): Boolean = !bodyIsLossy && attachmentsCarried == attachmentsIn
