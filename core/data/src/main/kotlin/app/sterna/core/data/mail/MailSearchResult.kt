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

/**
 * Merge the per-account answers of a unified search into one capped list.
 *
 * FAIR by account, then sorted newest-first. Merging everything and keeping the newest [limit]
 * silently dropped whole accounts: an account whose mail is all older than another's [limit]
 * newest hits contributed nothing at all, so a message the user could find by searching that
 * account alone was simply absent from the unified search — with no sign that anything had been
 * cut. Taking one hit per account per round instead guarantees every account that answered is
 * represented before any account gets a second share.
 *
 * De-duplicated on (accountId, id): a same-server sibling account can hold the same JMAP id, and
 * those are two different messages that must both be listed.
 *
 * [MailSearchResult.complete] is false as soon as anything was left out — an account that failed
 * or was truncated server-side (its own flag), or hits this cap dropped.
 */
internal fun mergeAccountSearches(perAccount: List<MailSearchResult>, limit: Int): MailSearchResult {
    // receivedAt is an ISO-8601 UTC string, so lexicographic sort == chronological.
    val lists = perAccount.map { result ->
        result.emails.distinctBy { it.accountId to it.id }.sortedByDescending { it.receivedAt ?: "" }
    }
    val picked = LinkedHashMap<Pair<String?, String>, Email>()
    var round = 0
    while (picked.size < limit && lists.any { it.size > round }) {
        for (list in lists) {
            if (picked.size >= limit) break
            val email = list.getOrNull(round) ?: continue
            val key = email.accountId to email.id
            if (!picked.containsKey(key)) picked[key] = email
        }
        round++
    }
    val available = lists.flatten().distinctBy { it.accountId to it.id }.size
    return MailSearchResult(
        emails = picked.values.sortedByDescending { it.receivedAt ?: "" },
        complete = perAccount.all { it.complete } && picked.size == available,
    )
}
