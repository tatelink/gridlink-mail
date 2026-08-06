package app.gridlink.core.data.mail

/**
 * Which folders one conversation is counted in, and listed from.
 *
 * A conversation is folder-scoped: a row in a folder describes the thread's messages in the
 * viewed folder(s) plus the replies its own account filed in Sent. Two things state that
 * membership — the chip on the collapsed row (SQL, see [conversationSql]) and the list of
 * messages the row unfolds into (InboxViewModel) — and the only invariant a reader can check is
 * that they agree: a chip saying 3 must unfold to 3.
 *
 * They agreed only by luck as long as each side resolved "which folder is Sent" on its own. The
 * chip read the folder cache reactively; the unfold made a SECOND, one-shot read whose failure
 * was swallowed into "this account has no Sent folder". A thread with a reply in Sent — that is,
 * any thread you have answered — then unfolded to fewer messages than its own chip had announced,
 * with nothing on screen to explain the difference.
 *
 * So there is one definition, taking the resolution as an argument rather than fetching it: both
 * sides call [folders] with the SAME resolution, and a chip that disagrees with its unfold stops
 * being something this code can express. Keep it that way — a second lookup on either side is how
 * the divergence returns.
 */
object ConversationScope {

    /**
     * The Sent folders belonging to [accountId] among [sentMailboxes] — account-pinned
     * `(accountId, mailboxId)` pairs, as `MailRepository.observeSentMailboxes` resolves them.
     * A null [accountId] keeps them all: the unified list spans accounts and pins each row's
     * own account inside the query instead.
     *
     * Pinned rather than matched on the bare mailbox id because same-server accounts carry
     * COLLIDING ids (servers number folders per account): a sibling account's folder whose id
     * happens to equal this account's Sent id must not widen this conversation (#92).
     */
    fun sentFolders(
        sentMailboxes: List<Pair<String, String>>,
        accountId: String?,
    ): List<Pair<String, String>> =
        sentMailboxes.distinct().filter { accountId == null || it.first == accountId }

    /**
     * The folder ids a conversation of [accountId] covers: the viewed folder(s) plus that
     * account's Sent folder(s) from [sentMailboxes].
     *
     * A resolution that yields nothing — a folder cache not synced yet, an account with no Sent
     * role, a lookup that failed — leaves the viewed folders alone. That answer is honest as long
     * as BOTH sides are given it: the chip then counts the smaller conversation the unfold shows.
     * It is the divergence that hurts, not the smaller number.
     */
    fun folders(
        viewedMailboxIds: List<String>,
        sentMailboxes: List<Pair<String, String>>,
        accountId: String?,
    ): Set<String> =
        (viewedMailboxIds + sentFolders(sentMailboxes, accountId).map { it.second }).toSet()
}
