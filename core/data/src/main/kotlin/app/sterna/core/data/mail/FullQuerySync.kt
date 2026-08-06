package app.sterna.core.data.mail

/**
 * The order in which a FULL RE-QUERY of a folder writes and deletes: every page as it lands, and
 * the reconcile ONCE, at the end, on a walk that finished.
 *
 * ⛔ THE RED LINE OF THIS PATH, and the reason this is a function of its own rather than four lines
 * inside `MailRepository.syncMailbox`: the reconcile DELETES every cached row outside [keepIds]
 * (`EmailDao.reconcileMailbox` → [reconcileEvictions]). It is therefore correct only against the
 * ids of a walk that ran to the end AND saw the folder. A walk cut off in the middle — the network
 * dropped, the user left the screen, the process was killed — describes a slice of the folder, and
 * reconciling against that slice deletes the rest. A walk that returned without seeing anything is
 * the same danger without an exception to signal it, and that one is [keepIds]' to refuse.
 *
 * So the reconcile sits on the NORMAL RETURN PATH and nowhere else:
 *
 * - there is no `try`/`catch` here. Any failure from [walk] (or from [writePage], which runs
 *   inside it) propagates and takes the reconcile with it;
 * - a `CancellationException` is a failure like any other for this purpose and must NOT be caught
 *   to "clean up" — cleaning up here means deleting mail;
 * - ⛔ and the reconcile must never be moved into a `finally`. A `finally` runs on exactly the path
 *   this rule exists to keep it off.
 *
 * What that costs when a walk is interrupted: the pages already written stay in the cache, and no
 * NEW sync cursor is stored. ⚠ That is not the same as "the next refresh cleans up". It does when
 * the folder had no cursor at all, which is the ordinary way into this branch (a cold cache): the
 * next refresh re-queries whole and the reconcile of THAT walk removes the surplus. But this branch
 * is also taken when a cursor EXISTS and the server merely could not compute the delta — and there
 * the old cursor survives the interruption, so if the server recovers, the deltas resume and
 * nothing ever removes the surplus rows. The worst case is still a cache holding MORE than the
 * window, and never a cache that lost mail; it is simply not always temporary.
 *
 * [spareIds] is a function and not a set so the recently-mutated protection window
 * (`MailRepository.recentlyMutatedIds`, 45 s wide) is read at the RECONCILE and not before the
 * walk. ⚠ It was already read at that moment before the streaming rewrite — it was an argument
 * evaluated after the walk returned — so this is a property preserved, not a defect fixed. What
 * changed is that it now matters: a walk over a deep folder can take minutes, and a set read at
 * the entry would have expired by the time it is used, deleting an optimistic Undo the server has
 * not caught up on. ⚠ 45 s can expire DURING such a walk whatever this function does; that is a
 * known, out-of-scope problem and this arrangement is the cheapest thing that does not worsen it.
 *
 * [P] is whatever one page of messages is; nothing here looks inside it. [R] is whatever the walk
 * answers with — the ids it accumulated, plus whatever else its protocol has to say (JMAP's
 * cursors, `JmapClient.queryEmailsWindow`; IMAP's "did the folder move", `ImapFolderWalk`). One
 * function for both, because there must be exactly ONE place where the order of these four steps
 * is written down: two orchestrators would be two places to get the same deletion wrong.
 *
 * [keepIds] turns that answer into what the reconcile may keep, and it is where a protocol says
 * "not this time": ⛔ NULL means DO NOT RECONCILE AT ALL, and is not the same thing as an empty
 * set — an empty set deletes the folder. IMAP returns null when the folder was renumbered under
 * the walk (`reconcilableIds`), because a page may then have skipped a message the server still
 * holds and the reconcile would delete it. JMAP never returns null: its walk pages by cursor, not
 * by position, so there is nothing to slip.
 *
 * Whatever it returns is the ids of ALL of the walk's requests, never the last page's — those ids
 * are both what the reconcile keeps and what the caller hands the retention prune as `freshIds`
 * (Codeberg #110).
 */
internal suspend fun <P, R> fullQueryWriteThrough(
    walk: suspend (onPage: suspend (P) -> Unit) -> R,
    writePage: suspend (P) -> Unit,
    keepIds: (R) -> Set<String>?,
    spareIds: suspend () -> List<String>,
    // Given the walk's own answer as well, because the folder to reconcile is not always known
    // before the walk: IMAP resolves it while walking (a null mailbox means "the inbox, whatever
    // the server calls it").
    reconcile: suspend (walked: R, keepIds: Set<String>, spareIds: List<String>) -> Unit,
): R {
    val walked = walk(writePage)
    // Reaching this line proves the walk RETURNED — that no failure, and no cancellation, came out
    // of it. That is a necessary condition for deleting, not a sufficient one: a walk can return
    // normally having learned nothing (JMAP's muted `Email/get`) or having skipped a message (an
    // IMAP folder renumbered under it). The sufficient condition is [keepIds]', on the next line.
    // Nothing above may catch, nothing below may move into a finally — see the KDoc.
    val keep = keepIds(walked) ?: return walked
    val spare = spareIds()
    reconcile(walked, keep, spare)
    return walked
}
