package app.sterna.ui.inbox

import app.sterna.core.data.settings.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the list is showing: a single folder, or the unified inbox. */
internal sealed interface Sel {
    data class Folder(val id: String?) : Sel
    data object Unified : Sel
}

/** Inputs that, together, determine the current paged source. */
internal data class PageKey(
    val sel: Sel,
    // The unified inbox's folders as (account id, inbox id) PAIRS. Bare ids listed the rows
    // of accounts that are not in this list — a same-server sibling's colliding folder id,
    // and above all a REMOVED account's leftovers, which no account could then label, sync or
    // act on (Codeberg #121). MailRepository.folderScopeSql says the rest.
    val unifiedScopes: List<Pair<String, String>>,
    val sort: SortOrder,
    val unreadOnly: Boolean,
    val conversationView: Boolean,
    // The active account, so switching accounts re-subscribes the pager even when the
    // new inbox shares the old one's mailbox id (JMAP servers number mailboxes per
    // account, so two accounts' inboxes often collide on the same id, e.g. "a").
    val accountId: String? = null,
)

/**
 * The key [InboxViewModel.pagedEmails] pages from, as a flow of its own so a test can run it.
 *
 * Every emission of this flow REBUILDS the pager: the caller's `flatMapLatest` cancels the running
 * `Pager` and constructs a new one, which starts from `initialKey = null` — the first page of the
 * cache. So an emission is not free: it is the list jumping back to the top, and the reader's
 * swipe-to-next-message losing its place with it (both page the same flow).
 *
 * That is why the chain ends on [distinctUntilChanged], and why the guard has to live HERE rather
 * than in the caller: DataStore republishes the WHOLE `Preferences` on every write, whatever key was
 * touched, so a setting with nothing to do with the list ("always show images from this sender",
 * `SettingsRepository.setImageAllowed`) re-emitted an EQUAL key and rebuilt the pager. `cachedIn`
 * cannot help: it caches the flow, not the decision to build a new one.
 *
 * ⚠ The dedupe is only correct because all six members of [PageKey] have value equality — `Sel` is
 * a sealed interface of a data class and a data object, `unifiedScopes` a list of pairs, the rest
 * primitives and an enum. A member added later WITHOUT it (a lambda, a repository handle, any
 * identity-compared object) would make every key distinct and quietly bring the rebuild storm back;
 * a member added with equality but not read here would stop the list rebuilding when it must.
 * `PageKeyFlowTest` pins both directions, member by member.
 */
internal fun pageKeyFlow(
    selection: Flow<Sel>,
    unifiedInboxScopes: Flow<List<Pair<String, String>>>,
    sortOrder: Flow<SortOrder>,
    unreadOnly: Flow<Boolean>,
    conversationView: Flow<Boolean>,
    currentAccountId: Flow<String?>,
): Flow<PageKey> =
    combine(selection, unifiedInboxScopes, sortOrder, unreadOnly, conversationView) {
            sel, scopes, sort, unread, conversation ->
        PageKey(sel, scopes, sort, unread, conversation)
    }.combine(currentAccountId) { key, accountId -> key.copy(accountId = accountId) }
        .distinctUntilChanged()
