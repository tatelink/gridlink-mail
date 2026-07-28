package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email

/**
 * What a search answered.
 *
 * [complete] is false when the search stopped before it could see everything — a full page (the
 * server had more than the cap), an IMAP attachment scan that gave up on its scan cap, or an
 * account that failed while others answered. The screen must then say "at least N" instead of
 * presenting [emails].size as a total: a truncated search counted as a total is a lie the user
 * has no way to spot.
 */
data class MailSearchResult(
    val emails: List<Email>,
    val complete: Boolean = true,
)
