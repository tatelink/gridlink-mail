package app.sterna.core.data.account

import kotlinx.serialization.Serializable

/**
 * Per-account "messages to sync" window — how much of a mailbox to keep cached,
 * either by recency ([maxAgeDays]) or by message [limit] (ARCHITECTURE.md →
 * "Storage & retention"). Age windows still cap the fetch with [limit].
 *
 * On an age window the two numbers are read TOGETHER, and [limit] is a FLOOR on what is kept,
 * not only a ceiling on what is fetched: retention keeps the newest [limit] messages of a folder
 * whatever their age, plus everything inside [maxAgeDays]. A folder of ten old messages therefore
 * keeps its ten, while a busy folder still keeps only its ninety days (Codeberg #110 — before
 * this, the age was the only number retention looked at, so the setting could empty a quiet
 * folder down to whatever happened to be recent).
 *
 * [limit] counts MESSAGES, not threads: folder syncs are uncollapsed (the cache is
 * WYSIWYG and holds every thread member), so a window now matches its "N messages"
 * settings label exactly.
 *
 * ⚠ On IMAP the window is where the reader's mail ends. Measured on the bench, same device, same
 * gesture, counted in the database per account and per folder: the IMAP Archive stayed flat at
 * 1 000 messages while it was scrolled to the bottom, where the JMAP Archive grew from 1 000 to
 * 1 150 under the same gesture.
 *
 * ⚠ WHY it does not hold on IMAP is NOT established. Nothing below is a cause, only what the code
 * says. The scroll-to-load-more mediator is attached without testing the protocol ([pagedFolder]),
 * and its IMAP branch does ask the server for older pages ([folderMediator], then
 * `ImapMailService.fetchOlderPage` and `upsertAll`) — so "the app never asks" is not the answer.
 * Two candidates were read off the code and never told apart: every IMAP refresh re-walks the
 * window and its reconcile deletes what falls outside it (no cursor, so this runs every time), and
 * `endOfPaginationReached` may be frozen true by an early `Success`. What the JMAP branch has and
 * the IMAP one has not is a thread-aware fill (`MAX_APPEND_FILL_PAGES`, `APPEND_THREAD_TARGET`):
 * the IMAP branch appends one page of fifty and stops there. The unified inbox is paged with no
 * mediator at all ([pagedMailbox]), so it stops at the window on both protocols.
 */
@Serializable
enum class SyncWindow(val limit: Int, val maxAgeDays: Int?) {
    DAYS_30(limit = 200, maxAgeDays = 30),
    DAYS_90(limit = 200, maxAgeDays = 90),
    YEAR_1(limit = 500, maxAgeDays = 365),

    // -- the count scale the picker offers ([syncWindowChoices]) --------------------------------
    // 100 / 1 000 / 10 000, and 10 000 is only safe because of the plumbing under it: pages are
    // written as they land, the walk asks the server for one page at a time, and the reconcile
    // chunks its deletes. Without those, 10 000 would have the memory profile of the unbounded
    // window this scale replaces.
    COUNT_100(limit = 100, maxAgeDays = null),
    COUNT_1000(limit = 1_000, maxAgeDays = null),
    COUNT_10000(limit = 10_000, maxAgeDays = null),

    // -- retired from the picker, ALIVE in the format --------------------------------------------
    // ⛔ Nothing below may be removed or renamed, and the reason is the same one written out under
    // [ALL]: these names are on disk, in the account records of every install that ever chose them,
    // and an unknown name does not fail loudly — it empties the account list, which the next write
    // then makes permanent. They are not offered any more ([syncWindowChoices]); they still decode,
    // still carry their number, and still show their label if an account is on one.
    COUNT_50(limit = 50, maxAgeDays = null),
    COUNT_200(limit = 200, maxAgeDays = null),
    COUNT_500(limit = 500, maxAgeDays = null),

    /**
     * "Everything" — retired from the picker, and capped again at the largest window that IS
     * offered (`COUNT_10000`, 10 000 messages). It is also SHOWN under that window's label: a row
     * reading "Everything" over a cache of ten thousand is the screen lying about what it holds
     * (`SettingsScreen.syncWindowLabel`).
     *
     * ⛔ The NAME may not change, and it may not be deleted either, whatever happens to the number:
     * the reason is worse than a crash. `SyncWindow` is serialized by constant name into every
     * account record ([StoredAccount.syncWindow]). kotlinx does throw on a name it does not know —
     * for the whole account list at once, not just for the records that carried it — but
     * `AccountStore.accounts()` decodes inside `runCatching { … }.getOrDefault(emptyList())`, so
     * nothing surfaces. What the user sees is not an error: it is **no accounts**. And the first
     * write after that — `saveAccounts` is the choke point every setter goes through — persists
     * that empty list over the real one. Renaming or dropping this entry silently and permanently
     * deletes every account of every install that ever chose it.
     *
     * Why the value comes back down. Caching a whole mailbox buys exactly two things: reading old
     * mail offline, and scrolling far back without a round trip. It does NOT buy fast search —
     * search is hybrid (local index on headers, the SERVER for full text), because a server is
     * better placed for that than a phone. Measured on the bench at 110 000 messages, the price of
     * the second thing was not worth paying, so the scale tops out at 10 000 instead of at the
     * folder.
     *
     * Why 10 000 and not a third number to explain: an account left on "Everything" by an older
     * build now behaves exactly like the biggest choice on offer, so the picker has one meaning per
     * number and no orphan value behind it. Both readers of [limit] treat it like any other count
     * window — the fetch (`folderSyncSizing`.`windowTarget`, walked one server page at a time) and
     * the retention floor (`retentionEvictions`.`keepNewest`, "keep at least the newest N whatever
     * their age", Codeberg #110). The floor is read off the WINDOW and never off a request size;
     * capping it with the page size is #110 re-opened.
     *
     * ⚠ In service only the FIRST of those two runs for a count window: [maxAgeDays] is null here,
     * so the caller computes no cutoff and both refresh paths skip the prune. What bounds the cache
     * on this window is the fetch and the full-query reconcile, not the floor.
     *
     * ⚠ No migration writes over an account that carries this name (or any retired one). The
     * setting keeps working as it reads; it changes only when the user opens the row and picks,
     * and then she lands on the new scale.
     */
    ALL(limit = 10_000, maxAgeDays = null),
}

/**
 * The windows the "Messages to sync" row offers, in the order it shows them — the three age windows
 * and the count scale 100 / 1 000 / 10 000.
 *
 * ⛔ This is a WHITELIST, deliberately, and not `SyncWindow.entries` minus something: the retired
 * members ([SyncWindow.COUNT_50], [SyncWindow.COUNT_200], [SyncWindow.COUNT_500], [SyncWindow.ALL])
 * must stay in the enum for ever, because their names are the persisted format (see [SyncWindow.ALL]),
 * and the only thing that changes is that nobody can choose them any more. A screen that listed
 * `entries` would put them straight back on the picker the day one is added for another reason.
 *
 * A function, and not a list written inline in `SettingsScreen`, so the choice can be EXECUTED in a
 * JVM test: a Composable cannot be, and a test that re-reads the screen's source only proves the
 * text is what it was.
 */
fun syncWindowChoices(): List<SyncWindow> = listOf(
    SyncWindow.DAYS_30,
    SyncWindow.DAYS_90,
    SyncWindow.YEAR_1,
    SyncWindow.COUNT_100,
    SyncWindow.COUNT_1000,
    SyncWindow.COUNT_10000,
)

/**
 * Whether picking [chosen] over [current] has to drop the account's JMAP sync cursors
 * (`MailRepository.dropSyncCursors`), so the next refresh re-queries the folder in full and
 * applies the new window. The delta branch of a sync never looks at the window and never fetches
 * backwards, so on a folder that already has a cursor the setting is inert until it falls.
 *
 * Both directions: a narrower window is applied by the same full re-query (which evicts the
 * surplus), so widening and narrowing are one rule, not two.
 *
 * ONLY on a real change. A choice row calls its `onSelect` when the value already in place is
 * tapped again, and that gesture must cost nothing: a full re-query of a folder is a real
 * download, and the answer to "did anything change?" is a comparison, not a state machine.
 */
fun syncWindowChanged(current: SyncWindow, chosen: SyncWindow): Boolean = chosen != current
