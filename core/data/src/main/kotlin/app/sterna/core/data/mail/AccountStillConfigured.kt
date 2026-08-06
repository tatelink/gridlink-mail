package app.sterna.core.data.mail

/**
 * A sync walk found, mid-flight, that the account it writes for is no longer configured — signed
 * out from the settings screen while it was running.
 *
 * ⛔ A [CancellationException] on purpose, and the choice is load-bearing three times over:
 *
 *  - `fullQueryWriteThrough` treats a cancellation like any other failure: it climbs out of the
 *    walk and the reconcile NEVER runs. Abandoning a walk must not delete anything;
 *  - `ImapMailService.runWithRetry` lets a cancellation through instead of replaying the block, so
 *    a deliberate sign-out does not cost a reconnect, a LOGIN and a second walk of the folder;
 *  - `InboxViewModel.refresh` re-throws a cancellation rather than turning it into a banner. The
 *    user asked for the account to go; a red error line for a gesture that SUCCEEDED would be a
 *    lie, and `#65`'s connectivity probe would count it as an offline network besides.
 *
 * ⛔ Which is why `MailRepository.refreshAllInboxes` catches THIS type by name, above its
 * cancellation catch, and skips the account instead of letting it out. A first version of this file
 * argued the opposite — "the sign-out re-points the list, which refreshes again straight away" —
 * and that is only true when the account signed out was the CURRENT one. For any other account
 * `SternaApp` recomputes the same current id, `InboxScreen` never calls `onAccountChanged()`, and
 * nothing refreshes: the cancellation climbs into `InboxViewModel.refresh`'s
 * `catch (CancellationException) { throw c }` arm, which deliberately leaves the status alone, so
 * `refreshing = true` stays set and the list spins for ever over a sync that no longer exists.
 * WYSIWYG decides it: the state on screen has to be true.
 */
class AccountGoneException(val accountId: String) : kotlin.coroutines.cancellation.CancellationException(
    "account $accountId is no longer configured; abandoning what was still writing in its name (#121)",
)

/**
 * The decision a sync makes before it writes: may rows still be tagged with [localAccountId]?
 *
 * ⭐ This is the blank-id guard of Codeberg #121 one notch further along. That one refuses an id
 * that was never an account (`require(…isNotBlank())` in `MailRepository.refresh`/`syncMailbox`);
 * this one refuses an id that STOPPED being one — the same stranded row with another face. Signing
 * an account out cancels nothing: the walk lives in the list screen's scope, not the settings
 * screen's, so it carried on writing pages under the removed id for as long as it had folder left
 * to read, and re-created the very ghosts the sign-out's orphan sweep had just removed.
 *
 * The two cases are answered differently ON PURPOSE:
 *
 *  - a BLANK id is a programming error — no add path can produce one any more, so it throws
 *    [IllegalArgumentException] loudly, exactly as the neighbouring guards do. Answering "abandon"
 *    here would turn that guard into the silent no-op #121's comment forbids, and a fourth add path
 *    making the old mistake would look like a sign-out;
 *  - an id that no configured account owns is a NORMAL, user-initiated event. It throws
 *    [AccountGoneException] — a cancellation, which unwinds the walk without deleting anything and
 *    without reporting a failure.
 *
 * ⚠ An EMPTY [configuredAccountIds] abandons, and "empty" does not only mean "no account is
 * configured". `AccountStore.accounts()` also answers `emptyList()` when the stored JSON fails to
 * DECODE — so a corrupt blob stops every sync of every account here, silently, since a cancellation
 * raises no banner and writes no error. The direction is still the right one (refusing a write
 * loses nothing), and it is kept, but the cause is named rather than hidden. Note the asymmetry
 * with `OrphanedAccountCache.orphans`, which names the same empty answer and refuses to read it as
 * "everything is an orphan": there an empty inventory would DELETE every cached row, so the safe
 * answer is to do nothing; here the failure mode is reversed. Both fail on the side that cannot
 * destroy data.
 *
 * ⚠ What this does NOT close: a page whose check passed microseconds before the account left the
 * store still lands, and lands after the purge. The race is not removed, it is narrowed from the
 * minutes a deep folder takes to the width of one write. What collects those is the orphan sweep —
 * the one at the end of the next `signOut`, and the one `SternaApplication` runs at every process
 * start, which is the one that reaches a row nobody signs out again.
 */
fun checkAccountStillConfigured(localAccountId: String, configuredAccountIds: Collection<String>) {
    require(localAccountId.isNotBlank()) {
        "checkAccountStillConfigured() needs a real account id: a blank one is the caller's own " +
            "bug (#121), not a signed-out account, and must not be mistaken for one."
    }
    if (localAccountId !in configuredAccountIds) throw AccountGoneException(localAccountId)
}
