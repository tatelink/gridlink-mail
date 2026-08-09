package app.gridlink.ui.gridlink

/**
 * What is inside each mailbox, and how a list outside the inbox orders it.
 *
 * ## 🔴 One pool of mail, seen from several angles
 * Every message in [GridlinkSample] is inbox mail, and there are thirty-one of them. Filling
 * seventeen folders with mail that is exclusively theirs would mean inventing another forty
 * messages, which brief §9 bans and which Tate did not ask for. So the folders below are **views
 * over the one pool**: a message can appear in Store 604 and in Verdant, because it is a Verdant
 * message about Store 604, and it is still in the Inbox as well.
 *
 * That is not how a JMAP account works and it is not pretending to be. It is how a set of saved
 * searches works, and it is the honest way to show a folder screen real mail without writing
 * fiction. When `Email/query` is wired up, this whole object goes and the folder id becomes a
 * `inMailbox` filter.
 *
 * ## ⚠️ Membership is a list of ids, not a rule
 * A matcher over subject lines was the obvious shape and it is worse: "Store 604" appears in two
 * subjects and in one body, "2071 KIRKWOOD" is written as "2071 Kirkwood" by the one human who
 * mentions it, and a regex that quietly missed that message would look exactly like a folder that
 * genuinely had one item in it. Written out, every membership can be read against §10's table in a
 * few seconds, and the `init` below fails on a typo instead of quietly dropping a row.
 *
 * ## 🔴 Unread counts are DERIVED from this, never declared
 * The tree used to carry hand-written unread numbers, which was fine while a folder was a label you
 * could not open. It stopped being fine the moment tapping one shows the mail: "Junk 4" opening onto
 * an empty panel, or "Store 604 3" onto a list with two dots in it, is the app contradicting itself
 * one tap apart. [unreadIn] is what [GridlinkSampleTree.mailboxes] fills its counts from, so the
 * badge cannot disagree with what is behind it.
 *
 * ⚠️ The visible cost is that Drafts, Sent and Junk carry no badge. Junk has nothing in it, and the
 * other two have rows that are not unread: mail you wrote is never unread mail, and the drawer's
 * "4 unsent" is counting a different noun on a different surface. A number that cannot be backed by
 * rows is not worth keeping.
 */
object GridlinkSampleFolders {

    /**
     * Folder id to the ids of the mail inside it. Anything absent is an empty folder.
     *
     * Each list is a claim about the mail, and each one is traceable to §10's own content:
     *  - the store folders hold the mail that names that store, by number or by name;
     *  - Ops itself holds the reporting that spans stores rather than naming one;
     *  - the vendor folders hold that vendor's mail, which is why two of them overlap the stores;
     *  - People is the colleagues, which is exactly the `gridlink.me` senders;
     *  - Receipts is the statements, orders and filings, the things you keep rather than answer.
     *
     * ⚠️ Most of the sample is in none of these. HR, scheduling, payroll, the recruiter and the guest
     * complaint sit in the Inbox and nowhere else, because that is where mail actually lives: a
     * folder tree describes the minority of mail somebody bothered to file.
     */
    private val contents: Map<String, List<String>> = mapOf(
        "ops" to listOf(
            "fill-pbi-gateway",
            "fill-tally-overtime",
            "pbi-refresh",
            "fill-rgorman",
            "fill-tperez",
            "pbi-scorecard",
        ),
        "ops-604" to listOf("fill-sanivex", "verdant-cap", "fill-verdant-followup"),
        "ops-hillcrest" to listOf("fill-dl-truck", "tally-hillcrest"),
        "ops-kirkwood" to listOf("fill-tally-kirkwood", "ridley-callout"),
        "ops-ellsworth" to listOf("verdant-pest", "fill-permit"),
        "ops-fernhill" to listOf("fill-facilities", "tally-fernhill"),
        "vendors" to listOf("fill-dalton", "fill-insurance"),
        "vendor-sanivex" to listOf("fill-sanivex"),
        "vendor-verdant" to listOf("verdant-cap", "verdant-pest", "fill-verdant-followup"),
        "vendor-brightmar" to listOf("fill-brightmar"),
        "people" to listOf(
            "fill-dl-truck",
            "fill-tperez",
            "fill-jonah-store",
            "jonah-dogs",
            "fill-kbaxter",
            "fill-amoore",
            "fill-rgorman",
            "fill-dhollis",
        ),
        "receipts" to listOf("fill-brightmar", "fill-dalton", "fill-insurance", "fill-permit"),
    )

    /**
     * Fails on the first launch if a membership above names a message that does not exist.
     *
     * 🔴 Eager, in an `init`, rather than left to [GridlinkSample.messageById] to throw when the
     * folder is opened. A typo in a folder nobody photographs would otherwise sit there until
     * somebody tapped it, which is the one moment it is guaranteed to be in front of Tate.
     */
    init {
        contents.forEach { (folderId, ids) ->
            ids.forEach { id ->
                requireNotNull(GridlinkSample.messages.firstOrNull { it.id == id }) {
                    "Folder '$folderId' names message '$id', which is not in GridlinkSample."
                }
            }
        }
    }

    /**
     * The mail in [folderId], newest first.
     *
     * The Inbox is every message rather than a list of thirty-one ids, because it genuinely is all of
     * them and writing them out again would be a second copy of §10 to keep in step with the first.
     */
    fun messagesIn(folderId: String): List<GridlinkMessage> {
        val pool = when (folderId) {
            "inbox" -> GridlinkSample.messages
            // 🔴 Its own list, not a membership in [contents]. Every other folder here is a view
            // over the inbox pool, and Drafts is the one mailbox that cannot be: mail you have not
            // sent is not mail that arrived, so its rows exist nowhere else. See
            // [GridlinkSample.draftMessages] for why they are held apart rather than filtered out.
            "drafts" -> GridlinkSample.draftMessages
            // Same reasoning as Drafts, one step later: mail you sent is not mail that arrived, so
            // it cannot be a view over the inbox pool either. See [GridlinkSample.sentMessages].
            "sent" -> GridlinkSample.sentMessages
            else -> {
                val ids = contents[folderId] ?: return emptyList()
                GridlinkSample.messages.filter { it.id in ids }
            }
        }
        return pool.sortedWith(compareBy({ it.dayRank() }, { it.minutesBeforeMidnight() }))
    }

    /**
     * How many unread are in [folderId], as the tree's badge.
     *
     * 🔴 The Inbox adds the bundle's phantom unread. [GridlinkSample.reportsBundle] declares more
     * unread than it has children, because §5's mock says "14 new" against a sample of a morning, and
     * the inbox screen's own header counts that surplus in. Leaving it out here would put "11" on the
     * folder tree beside a tab reading "21 unread", which is the exact disagreement this function
     * exists to prevent.
     */
    fun unreadIn(folderId: String): Int {
        val own = messagesIn(folderId).count { it.unread }
        return if (folderId == "inbox") own + GridlinkSample.reportsBundle.phantomUnread else own
    }

}

/**
 * Which heading a message sits under in a folder list.
 *
 * 🔴 A folder has no bundle, so [GridlinkSection.AUTOMATED] is not a heading anything can sit under
 * here. §5's bundle is an *inbox* decision, made because a morning of robots buries the mail a human
 * sent; inside Vendors > Verdant every message is a robot, and collapsing them would turn the whole
 * folder into a single row that has to be opened before it says anything.
 *
 * A human message keeps its declared [GridlinkMessage.section], so the same message can never sit
 * under one heading in the inbox and a different one here. A robot has no real section, so its day is
 * read off the timestamp, which is the only chronological fact the sample carries.
 */
fun GridlinkMessage.gridlinkFolderSection(): GridlinkSection = when {
    !automated && section != GridlinkSection.AUTOMATED -> section
    dayRank() == 0 -> GridlinkSection.TODAY
    dayRank() == 1 -> GridlinkSection.YESTERDAY
    else -> GridlinkSection.EARLIER
}

/**
 * The pre-formatted day tokens the sample uses, most recent first.
 *
 * ⚠️ A list rather than date arithmetic, and it is tied to [GridlinkSampleTree.TODAY] being a
 * Thursday: this is simply that week walking backwards. Computing it would mean reading
 * [GridlinkSampleTree.TODAY] from code that [GridlinkSampleTree] calls while it is still building its
 * own mailbox list, which is an initialisation order nothing here should be resting on. Move the two
 * together, or neither.
 */
private val DAY_ORDER = listOf("Yesterday", "Wed", "Tue", "Mon", "Sun", "Sat", "Fri")

/** 0 for today, then one per day back. An unknown token sorts to the end rather than to the top. */
private fun GridlinkMessage.dayRank(): Int {
    if (timestamp.endsWith("AM") || timestamp.endsWith("PM") || timestamp == "Just now") return 0
    val index = DAY_ORDER.indexOfFirst { timestamp.startsWith(it) }
    return if (index < 0) DAY_ORDER.size + 1 else index + 1
}

/**
 * Where in today a message landed, counted backwards so that later reads as smaller.
 *
 * Zero for anything not from today, which leaves those rows in declaration order within their own
 * day. That is not laziness, it is all the sample can support: "Tue" is not a time.
 */
private fun GridlinkMessage.minutesBeforeMidnight(): Int {
    if (dayRank() != 0) return 0
    val match = CLOCK.find(timestamp) ?: return 0
    val (rawHour, minute, meridiem) = match.destructured
    val hour = rawHour.toInt() % 12 + if (meridiem == "PM") 12 else 0
    return 24 * 60 - (hour * 60 + minute.toInt())
}

private val CLOCK = Regex("""(\d{1,2}):(\d{2})\s*(AM|PM)""")
