package app.sterna.core.data.mail

/**
 * Which cached folder ids a full folder-list sync drops, in batches no single statement can choke
 * on — `MailboxDao.replaceAll`'s decision, extracted here to be executed in a JVM test.
 *
 * ⛔ Why a complement and not a `NOT IN (:keepIds)`: SQLite binds at most 999 variables below
 * Android 12 ([MAX_CHANGES] is this module's bound for exactly that reason, Codeberg #29), and a
 * folder list is as long as the server's — 1 040 folders on the account that reported this
 * (Codeberg #142). The purge therefore bound MORE variables than the engine accepts, and the
 * statement failed to COMPILE, inside `replaceAll`'s `@Transaction`: the upsert of the same
 * transaction rolled back with it, so every refresh threw and the drawer stayed empty forever,
 * with no way out from the app.
 *
 * ⛔ And why the complement is computed ONCE, against the WHOLE [keepIds]: chunking a
 * `NOT IN (:keepIds)` into several statements would delete everything outside each chunk in turn,
 * i.e. the entire drawer. The set to delete is decided first and only then cut up; each batch is
 * then a plain list of ids to remove, and cutting it differently can never change what goes.
 *
 * [cachedIds] must already be scoped to one account (`MailboxDao.idsForAccount`): the primary key
 * is (accountId, id), so a bare id is ambiguous between accounts, and each batch is deleted with
 * its account in the `WHERE` for the same reason.
 */
internal fun mailboxEvictions(
    cachedIds: List<String>,
    keepIds: Set<String>,
    chunk: Int = MAX_CHANGES,
): List<List<String>> =
    cachedIds.filter { it !in keepIds }.chunked(chunk)
