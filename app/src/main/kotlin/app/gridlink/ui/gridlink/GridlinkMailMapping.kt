package app.gridlink.ui.gridlink

import app.gridlink.appLocale
import app.gridlink.core.data.db.EmailKeywords
import app.gridlink.core.jmap.model.Email
import app.gridlink.util.MailDates
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * How a list row's identity is written down when the list can hold more than one account's mail.
 *
 * ## 🔴 Why a bare message id is not enough
 * A JMAP email id is unique only within its account (RFC 8620 §1.6.2), which is why the cache's
 * `EmailEntity` is keyed by the PAIR `(accountId, id)` rather than by the id. Two of Brandon's
 * accounts sit on the same Stalwart and hand out the same ids, so in a merged inbox "archive the
 * selected row" against a bare id would resolve to a message in each account and archive both. That
 * is not a display bug, it is mail moving in a mailbox nobody touched.
 *
 * ## Why an encoded string rather than a pair
 * The list screen threads identity through as `Set<String>` in a dozen places (the selection set,
 * the swipe recycle, `removedIds`, the open-message highlight) and compares row keys to row keys
 * without ever looking inside one. Encoding keeps that entire surface untouched and confines the
 * question to the two ends: [encode] where a row is built, [decode] where an action needs a real
 * account to talk to.
 *
 * [SEPARATOR] is NUL because it is the one byte that cannot appear in either half. An account id is
 * generated locally and a message id comes off the wire, and picking a printable delimiter would be
 * betting that no server ever puts it in an id.
 */
object GridlinkRowKey {

    private const val SEPARATOR = '\u0000'

    /**
     * The row key for [emailId] in [accountId], or the bare id when [accountId] is null.
     *
     * 🔴 Null means "the list is showing one account", and it returns the id UNCHANGED on purpose:
     * the single-account inbox is the overwhelmingly common case, and leaving its keys exactly as
     * they were means nothing that persists or compares one (a saved selection, a pending recycle)
     * can be confused by the unified inbox having been open earlier.
     */
    fun encode(accountId: String?, emailId: String): String =
        if (accountId == null) emailId else "$accountId$SEPARATOR$emailId"

    /**
     * A row key back into the account it belongs to and the message id the server knows.
     *
     * An unqualified key yields a null account, which the caller resolves against the account the
     * list is bound to. That is not a fallback bolted on, it is the exact meaning of an unqualified
     * key: it was written by a list that was showing one account, and that account is the one still
     * bound.
     */
    fun decode(key: String): Pair<String?, String> {
        val cut = key.indexOf(SEPARATOR)
        return if (cut < 0) null to key else key.substring(0, cut) to key.substring(cut + 1)
    }

    /** The message id inside [key], whichever form it is in. */
    fun emailId(key: String): String = decode(key).second
}

/**
 * Real mail, as the Gridlink screens want it.
 *
 * ## Why this is a separate file and not a method on [GridlinkMessage]
 * Everything under `ui.gridlink` renders data handed to it and knows nothing about JMAP, a cache or
 * an account — which is what lets the debug gallery draw every screen with no account and no
 * network. A constructor that took an [Email] would put the whole `core:jmap` model behind that
 * boundary for one conversion. Here it is one file, on the app side of the line, and it is pure:
 * given the same message and the same "now" it produces the same row, which is the only reason the
 * sectioning and the stamping below can be tested at all.
 *
 * ## What is genuinely not known here
 * A list fetch asks the server for headers, not bodies. So a mapped message carries no body (the
 * thread fetches it on open) and no [GridlinkMessage.attachments], only the `hasAttachment` flag it
 * arrives with, as [GridlinkMessage.attachmentPending]. Both are absences with a shape, not
 * defaults to be filled in with something plausible.
 */
object GridlinkMailMapping {

    /**
     * Local parts that mean "do not write back". The automated split reads THIS and nothing else.
     *
     * ## 🔴 What this is, stated plainly: a guess, from the address alone
     * The signals that actually settle the question are headers — `List-Unsubscribe`,
     * `Auto-Submitted`, `Precedence: bulk` — and none of them are in the list cache, which stores
     * the columns a row draws. Fetching them for every message to sort a list would be a per-message
     * round trip on a screen whose whole job is to be instant. So this is the conventional first
     * pass every mail client makes, and it is wrong in both directions: a person whose company
     * routes mail through `notifications@` lands in the bundle, and a marketing blast from a
     * human-looking address does not.
     *
     * ## Why being wrong is survivable here, and where it would not be
     * Nothing is hidden and nothing is deleted. The bundle sits ABOVE the timeline with its exact
     * unread count on it and opens with one tap, so the worst case is a message one row further
     * away than it should be. That is the only reason a heuristic is allowed to touch mail at all.
     * 🔴 The moment anything downstream wants to mute, auto-archive or suppress a notification for
     * "automated" mail, this stops being good enough and the headers have to be fetched and stored.
     *
     * `mailer-daemon` and `postmaster` are in the list and are the one entry worth arguing about: a
     * bounce is machine-written but it is also frequently the most important thing in the mailbox.
     * They are here because the bundle is a grouping and not a mute, per the paragraph above; if
     * that ever changes they come out first.
     */
    private val AUTOMATED_LOCAL_PARTS = listOf(
        "noreply",
        "no-reply",
        "no_reply",
        "donotreply",
        "do-not-reply",
        "do_not_reply",
        "notifications",
        "notification",
        "alerts",
        "alert",
        "automated",
        "autoreply",
        "auto-reply",
        "bounce",
        "bounces",
        "mailer-daemon",
        "postmaster",
    )

    /**
     * The four pieces of text a mapped row can need that the message itself does not carry.
     *
     * ## Why these are English constants and not `R.string`
     * ⚠️ Every label in `ui.gridlink` is hard-coded English: the package implements a written design
     * brief and its wording is part of that brief. Two of these four (`(unknown sender)`,
     * `(no subject)`) even exist as translated resources already, for upstream's list. Reaching for
     * them here would leave the Gridlink list with two translated strings sitting beside two hundred
     * untranslated ones, which is not "half done", it is a list that changes language mid-row.
     *
     * 🔴 So this is a **known debt with a shape**, deliberately taken in one place: when the Gridlink
     * UI is localised, it is localised as a package, and this data class is where its list strings
     * are already gathered and waiting to be handed in from resources instead of defaulted. Nothing
     * else has to move.
     */
    data class Labels(
        val yesterday: String = "Yesterday",
        val unknownSender: String = "(unknown sender)",
        val noSubject: String = "(no subject)",
        val bundleTitle: String = "Automated",
    )

    /**
     * One mailbox's worth of cached mail, split the way the list draws it.
     *
     * [bundle] is null rather than empty when nothing qualifies: the collapsed bundle row is a claim
     * that there is machine mail to look at, and drawing it over zero messages would be a permanent
     * empty drawer at the top of the list.
     */
    data class Mail(
        val humans: List<GridlinkMessage>,
        val bundle: GridlinkBundle?,
    )

    /**
     * Map [emails] (newest first, as the cache returns them) into the two lists the list screen owns.
     *
     * [today] and [zone] are the reader's calendar, passed rather than read, so the day headings can
     * be tested across a midnight and a time-zone change instead of only wherever the test machine
     * happens to sit.
     */
    /**
     * One cached message as the list's source, with the two facts a COLLAPSED row needs and a flat
     * row does not.
     *
     * Exists so [map] takes one list in both modes instead of a list plus two side tables keyed by
     * id. [threadCount] is 1 and [threadUnread] follows the message itself in flat mode, which is
     * exactly what a one-message thread is, so nothing downstream has to ask which mode it is in.
     */
    data class Row(
        val email: Email,
        val threadCount: Int = 1,
        val threadUnread: Boolean = !email.isSeen,
        /**
         * The account this row came out of, or null when the list is showing one account.
         *
         * 🔴 Null is not "unknown", it is "the question does not arise". Set only by the unified
         * inbox, where it both qualifies the row key ([GridlinkRowKey]) and supplies the marker the
         * row draws. See [account].
         */
        val account: Account? = null,
    ) {
        /**
         * An account, as a merged row needs it: one id to act on, one string to name it, and the
         * colour that names it on the row itself.
         *
         * [color] is ARGB and never null in the unified inbox: every account has one, chosen or
         * assigned (see `resolveAccountColors`). The label survives alongside it because the colour
         * is not readable aloud — it is what the bar tells a screen reader.
         */
        data class Account(val id: String, val label: String, val color: Int)
    }

    fun map(
        rows: List<Row>,
        labels: Labels,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
        bundleAutomated: Boolean = false,
    ): Mail {
        val mapped = rows.map {
            message(
                email = it.email,
                labels = labels,
                zone = zone,
                today = today,
                locale = locale,
                bundleAutomated = bundleAutomated,
                threadCount = it.threadCount,
                threadUnread = it.threadUnread,
                account = it.account,
            )
        }
        // 🔴 One list and no bundle when the setting is off, which is the default. See
        // [SettingsRepository.bundleAutomated]: a list that reorganises itself has to be understood
        // before it can be read. The rows still carry [GridlinkMessage.automated] because other
        // things read it; what changes is that nothing acts on it here.
        if (!bundleAutomated) return Mail(humans = mapped, bundle = null)
        val robots = mapped.filter { it.automated }
        return Mail(
            humans = mapped.filterNot { it.automated },
            bundle = if (robots.isEmpty()) null else bundle(robots, labels.bundleTitle),
        )
    }

    /**
     * One cached [Email] as a list row.
     *
     * [showRecipient] is the Sent/Drafts switch and belongs to the MAILBOX, not the message: it is
     * true because of where the row is being drawn, not because of anything the message says about
     * itself. See [GridlinkMessage.sentTo].
     */
    fun message(
        email: Email,
        labels: Labels,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
        showRecipient: Boolean = false,
        /**
         * 🔴 Affects the SECTION only, never [GridlinkMessage.automated]. Whether a sender is a robot
         * is a fact about the address; whether the list gathers robots is a preference. With the
         * preference off, an automated message takes the day heading its date earns and sits in the
         * timeline with everything else, which is what "by default it should just show mail" means.
         */
        bundleAutomated: Boolean = false,
        /** Messages the collapsed row stands for, 1 in flat mode. See [Row]. */
        threadCount: Int = 1,
        /**
         * The THREAD's unread state, which is not the message's once a row stands for several: the
         * newest message can be read while an older one under it is not. Null means "ask the
         * message", which is the flat-mode answer and the only right one for a search hit.
         */
        threadUnread: Boolean? = null,
        /** Set only in the unified inbox. See [Row.account] and [GridlinkRowKey]. */
        account: Row.Account? = null,
    ): GridlinkMessage {
        val from = email.from.firstOrNull()
        val address = from?.email.orEmpty()
        val automated = isAutomated(address)
        return GridlinkMessage(
            // 🔴 Account-qualified in the unified inbox and byte-identical to the server id
            // everywhere else. An id alone does not identify a message across accounts.
            id = GridlinkRowKey.encode(account?.id, email.id),
            accountLabel = account?.label,
            accountColor = account?.color,
            // The display name if the sender wrote one, otherwise the address itself. Not the local
            // part prettied up: "accounts.payable" becoming "Accounts Payable" is the app inventing
            // a name for a correspondent, and the address is both shorter and true.
            sender = from?.display()?.takeIf { it.isNotBlank() } ?: labels.unknownSender,
            domain = address.substringAfterLast('@', "").lowercase(),
            subject = email.subject?.takeIf { it.isNotBlank() } ?: labels.noSubject,
            timestamp = MailDates.formatGridlinkStamp(
                iso = email.receivedAt,
                zone = zone,
                today = today,
                locale = locale,
                yesterday = labels.yesterday,
            ),
            unread = threadUnread ?: !email.isSeen,
            starred = email.isFlagged,
            threadCount = threadCount,
            // The key the expansion queries by, and the same expression the collapsed SQL groups on:
            // a message with no server thread is a thread of one, keyed by itself.
            // 🔴 Qualified for the same reason as the id above, and it is not the same fact twice: a
            // THREAD id is scoped to its account exactly as a message id is, so two accounts on one
            // server share thread keys too. Unqualified, unfolding one account's conversation would
            // unfold the other's under it and the expansion query would be handed a key that means
            // two different threads.
            threadKey = GridlinkRowKey.encode(account?.id, email.threadId ?: email.id),
            attachmentPending = email.hasAttachment,
            automated = automated,
            section = if (automated && bundleAutomated) {
                GridlinkSection.AUTOMATED
            } else {
                section(email, zone, today)
            },
            // Left empty on purpose: the body is not in the list cache. The thread fetches it.
            body = "",
            // The one piece of body text a list fetch DOES return, and the only reason a search
            // result can show what it matched without opening the message. Null on IMAP rows and
            // on anything cached before the server sent one, which draws no preview line at all
            // rather than an empty one.
            preview = email.preview.orEmpty(),
            addressOverride = address.takeIf { it.isNotBlank() },
            sentTo = if (showRecipient) recipient(email) else null,
            // The keyword map minus $seen/$flagged/$draft, which are the read state, the star and
            // the draft badge and each already have their own field above. EmailKeywords is the
            // single place that decides what counts as a system keyword, for both protocols.
            tags = EmailKeywords.custom(email.keywords.filterValues { it }.keys),
        )
    }

    /**
     * The `To:` header as one drawable identity.
     *
     * ## 🔴 Why an empty `to` gives a blank name rather than falling back to the sender
     * Upstream's list falls back to the sender when a cached row carries no recipients, and here
     * that fallback is the bug: the sender in these two mailboxes is you, so the fallback prints
     * your own name, which is the exact thing this whole path exists to remove. A blank name draws
     * `(no recipient)`, which is *right* for an unaddressed draft and is "we do not know yet" for
     * the one other case that reaches it — a row cached before the v17 `recipientsJson` column
     * existed, which the folder's next refresh fills in. Saying nothing beats saying "Brandon".
     *
     * The `to` header is asked for on every list fetch (`Email/query` + `Email/get` both name it),
     * so this is populated for anything synced by this version.
     */
    private fun recipient(email: Email): GridlinkRecipient {
        val first = email.to.firstOrNull()
        val address = first?.email.orEmpty()
        return GridlinkRecipient(
            // Same rule as [sender]: the display name if there is one, else the address itself,
            // never the local part prettied up into a name nobody wrote.
            name = first?.display()?.takeIf { it.isNotBlank() }.orEmpty(),
            domain = address.substringAfterLast('@', "").lowercase(),
            others = (email.to.size - 1).coerceAtLeast(0),
        )
    }

    /**
     * Which day heading a message falls under.
     *
     * A message with no readable `receivedAt` sorts to [GridlinkSection.EARLIER] rather than to
     * today. Undated mail is far more often something the cache is confused about than something
     * that arrived this minute, and putting it under Today would place it above mail that genuinely
     * did arrive this minute.
     *
     * Mail dated in the FUTURE lands there too (a sender's clock is wrong, or the message was
     * scheduled). It is not today, and the timeline has no heading above Today to put it under, so
     * it goes where the stamp beside it already points: a date, rather than a claim about a day.
     */
    fun section(email: Email, zone: ZoneId, today: LocalDate): GridlinkSection {
        val date = MailDates.localDate(email.receivedAt, zone) ?: return GridlinkSection.EARLIER
        return when (date) {
            today -> GridlinkSection.TODAY
            today.minusDays(1) -> GridlinkSection.YESTERDAY
            else -> GridlinkSection.EARLIER
        }
    }

    /** True when [address]'s local part is one of the conventional no-reply forms. */
    fun isAutomated(address: String): Boolean {
        if (address.isBlank()) return false
        val local = address.substringBefore('@').lowercase()
        // Sub-addressed robots are still robots: `noreply+ticket-4821@` is one address per ticket
        // and every one of them would otherwise miss the list.
        val base = local.substringBefore('+')
        return base in AUTOMATED_LOCAL_PARTS
    }

    /**
     * The robots as one collapsed row.
     *
     * 🔴 The count is the count. [GridlinkBundle] can carry an unread total larger than the messages
     * it holds ([GridlinkBundle.phantomUnread]) because the sample's does, and it is exactly the
     * wrong thing to do here: the sample says "14 new" over six rows because the brief's table is a
     * sample of a morning, whereas this bundle holds every automated message in the window. A
     * phantom count on real mail would be a number with nothing behind it.
     */
    private fun bundle(robots: List<GridlinkMessage>, title: String): GridlinkBundle =
        GridlinkBundle(
            title = title,
            unreadCount = robots.count { it.unread },
            // Three names, in the order they appear (which is newest first), and no "+2 more". The
            // row is a summary of who is in there, not a manifest; the count above it is the number.
            senderSummary = robots.map { it.sender }.distinct().take(BUNDLE_SUMMARY_SENDERS)
                .joinToString(", "),
            messages = robots,
        )

    /** How many senders the collapsed bundle names. Three fits the row at the brief's type sizes. */
    private const val BUNDLE_SUMMARY_SENDERS = 3
}
