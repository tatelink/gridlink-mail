package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email

/**
 * How a bulk delete splits: what is permanently [destroy]ed (behind the Undo window), what is
 * [move]d to the Trash, and what is [untreated] — deleted nowhere, because doing either would be
 * a guess, and the guess that is wrong destroys mail.
 */
internal data class SelectionDeletePlan(
    val destroy: List<Email>,
    val move: List<Email>,
    val untreated: List<Email>,
)

/**
 * Who gets destroyed and who gets moved, decided WITHOUT ever reading an untrusted row's folder.
 *
 * `MailRepository.deleteWouldDestroy` answers from the row's own `mailboxId` — "is it already in
 * the Trash?" — and a row that only exists on screen carries the folder the crawl froze, which may
 * be stale or empty. So it is asked about trusted rows only.
 *
 * An untrusted row is moved to the Trash: search excludes Trash and Junk on both sides, so a search
 * hit is never in the Trash already, and the safe leg is also the true one. The exception is an
 * account with NO Trash, where `deleteWouldDestroy` short-circuits to `true` for everything
 * (`roleMailboxId(...) ?: return true`): there the move has nowhere to go and the destroy would
 * rest on a supposition, so the row is left alone and returned in [SelectionDeletePlan.untreated]
 * for the caller to count and announce. [hasTrash] answers that question without looking at any
 * row's folder, and is asked once per account.
 *
 * Returns plain data rather than doing the work, so this decision runs in a JVM test —
 * `InboxViewModel` is an `AndroidViewModel` and cannot be instantiated in one.
 */
internal suspend fun planSelectionDelete(
    targets: List<SelectionTarget>,
    hasTrash: suspend (Email) -> Boolean,
    wouldDestroy: suspend (Email) -> Boolean,
): SelectionDeletePlan {
    val destroy = mutableListOf<Email>()
    val move = mutableListOf<Email>()
    val untreated = mutableListOf<Email>()
    val trashByAccount = mutableMapOf<String?, Boolean>()
    for (target in targets) {
        val email = target.email
        when {
            target.folderTrusted -> if (wouldDestroy(email)) destroy += email else move += email
            trashByAccount.getOrPut(email.accountId) { hasTrash(email) } -> move += email
            else -> untreated += email
        }
    }
    return SelectionDeletePlan(destroy, move, untreated)
}
