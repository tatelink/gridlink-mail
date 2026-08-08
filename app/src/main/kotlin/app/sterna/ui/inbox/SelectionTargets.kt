package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email

/**
 * One selected message a bulk action is about to act on, and whether its folder can be believed.
 *
 * [folderTrusted] is false for a row that was only ever drawn on screen. Such a row can come from
 * the search index, whose `mailboxId` was frozen at crawl time and may be empty or long out of
 * date — and two decisions read that field: the Undo entry, which would move the message back into
 * a stale folder AND strip every other folder it belongs to with no way back, and the destroy leg
 * of a delete. A caller holding an untrusted row must therefore look the folder up itself, or drop
 * the part of the action that needs one; a non-empty [Email.mailboxId] is not evidence of anything.
 */
internal data class SelectionTarget(val email: Email, val folderTrusted: Boolean)

/**
 * The selection turned into rows to act on, plus the keys nothing could be found for.
 *
 * [unresolved] is not an error on its own — it is what the caller must still count as attempted and
 * failed, so a batch that silently reached none of the selection says so instead of saying nothing.
 */
internal data class SelectionTargets(
    val targets: List<SelectionTarget>,
    val unresolved: Set<EmailKey>,
)

/**
 * Which row every selected key stands for: the cached one when the local table has it (fresher, and
 * its folder is written and rewritten by the sync rather than frozen at crawl time — stale offline
 * or after a move made from another client, but never simply absent), otherwise the row the list is
 * currently drawing ([displayed], non-null only while search results are on screen), otherwise
 * nothing.
 *
 * The walk is over [keys], not over what the cache query happened to return: a search hit often has
 * no row in the local `emails` table at all, and iterating the query's answer is exactly how the six
 * bulk actions used to skip those messages without acting, failing or telling anyone.
 *
 * Matching is by [EmailKey] throughout: ids collide between two accounts of the same server, so an
 * id-only lookup hands one account's message to the other account's action.
 *
 * Returns plain data rather than doing the work, so this decision runs in a JVM test —
 * `InboxViewModel` is an `AndroidViewModel` and cannot be instantiated in one.
 */
internal fun resolveSelectionTargets(
    keys: Set<EmailKey>,
    cached: List<Email>,
    displayed: List<Email>?,
): SelectionTargets {
    val cachedByKey = cached.associateBy { it.emailKey() }
    val displayedByKey = displayed.orEmpty().associateBy { it.emailKey() }
    val targets = mutableListOf<SelectionTarget>()
    val unresolved = LinkedHashSet<EmailKey>()
    for (key in keys) {
        val fresh = cachedByKey[key]
        val drawn = displayedByKey[key]
        when {
            fresh != null -> targets += SelectionTarget(fresh, folderTrusted = true)
            drawn != null -> targets += SelectionTarget(drawn, folderTrusted = false)
            else -> unresolved += key
        }
    }
    return SelectionTargets(targets, unresolved)
}
