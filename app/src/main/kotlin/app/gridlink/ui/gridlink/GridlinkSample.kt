package app.gridlink.ui.gridlink

/**
 * The brief's real content, verbatim.
 *
 * 🔴 `docs/GRIDLINK-UI-BRIEF.md` §10 requires these exact senders and subject lines in every
 * mockup, and §9 bans invented sample content outright. They are long, ugly and repetitive on
 * purpose: a layout that only survives short subjects is not finished. Do not "clean these up",
 * do not shorten them to make a screenshot look better, and do not add extra rows to fill space.
 *
 * ⚠️ The one thing the brief does NOT give is addresses, and the identity bar is keyed on sender
 * domain, so the domains below are inferred from the sender names. They affect only which colour a
 * sender's bar gets. Swap them for the real ones once the app is talking to the live mailbox.
 *
 * ⚠️ One deliberate departure from §10: the brief's employer-branded HR sender was renamed to a
 * neutral "HR Benefits" at Tate's request, because seeing that brand in mockups was colliding
 * with unrelated work in other sessions. Subject lines are untouched, so the layout is still being
 * tested against the same real-world string lengths.
 */
object GridlinkSample {

    /** §10's ten, untouched. Everything below this list is filler; see [extraMessages]. */
    private val briefMessages: List<GridlinkMessage> = listOf(
        GridlinkMessage(
            id = "tally-hillcrest",
            sender = "Tallyman",
            domain = "tallyman.example",
            subject = "Daily Sales Summary 2043 HILLCREST 07/30",
            timestamp = "7:14 AM",
            unread = false,
            attachment = GridlinkAttachment("dss_1182_0730.pdf", "61 KB"),
            automated = true,
        ),
        GridlinkMessage(
            id = "pbi-refresh",
            sender = "Power BI Service",
            domain = "microsoft.com",
            subject = "Refresh failed: District7_P7_Rollup (dataset)",
            timestamp = "6:52 AM",
            unread = false,
            automated = true,
        ),
        GridlinkMessage(
            id = "verdant-cap",
            sender = "Verdant",
            domain = "verdantfs.example",
            subject = "ACTION REQUIRED: Corrective Action Plan due 08/04 Store 604",
            timestamp = "6:31 AM",
            unread = true,
            automated = true,
        ),
        GridlinkMessage(
            id = "jonah-dogs",
            sender = "Jonah",
            domain = "gridlink.me",
            subject = "did you feed the dogs",
            timestamp = "6:22 AM",
            unread = true,
            section = GridlinkSection.TODAY,
        ),
        GridlinkMessage(
            id = "hr-enrollment",
            sender = "HR Benefits",
            domain = "hrbenefits.example",
            subject = "Open Enrollment closes Friday, action needed for all salaried TMs",
            timestamp = "Yesterday",
            unread = false,
            section = GridlinkSection.YESTERDAY,
        ),
        GridlinkMessage(
            id = "tally-fernhill",
            sender = "Tallyman",
            domain = "tallyman.example",
            subject = "Labor Variance Exception Report 2096 FERNHILL RD Week 30",
            timestamp = "Yesterday",
            unread = false,
            automated = true,
        ),
        GridlinkMessage(
            id = "ridley-callout",
            sender = "M. Ridley",
            domain = "hrbenefits.example",
            subject = "Callout Saturday AM, need coverage 2071 Kirkwood",
            timestamp = "Yesterday",
            unread = true,
            section = GridlinkSection.YESTERDAY,
        ),
        GridlinkMessage(
            id = "northgate-fbc",
            sender = "Northgate Group Talent",
            domain = "northgategroup.example",
            subject = "Franchise Business Consultant, next steps and availability",
            timestamp = "Tue",
            // ⚠️ Read, on request. §10 has this one unread; it was flipped so there is a read row
            // sitting directly under the timeline's own heading where the difference between the
            // two states is easy to look at. Change it back the moment that stops being useful.
            unread = false,
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "verdant-pest",
            sender = "Verdant",
            domain = "verdantfs.example",
            subject = "Pest Sighting Report filed 2118 ELLSWORTH",
            timestamp = "Tue",
            unread = false,
            automated = true,
        ),
        GridlinkMessage(
            id = "pbi-scorecard",
            sender = "Power BI Service",
            domain = "microsoft.com",
            subject = "Your subscription: District 7 Weekly Scorecard",
            timestamp = "Mon",
            unread = false,
            automated = true,
        ),
    )

    /**
     * Filler. Invented, and knowingly so.
     *
     * 🔴 This block overrides brief §9's ban on invented sample content, on Tate's explicit
     * instruction ("create more fake emails so the list is longer"). The ban has a good reason
     * behind it and it still holds for [briefMessages]: a mockup dressed in lorem ipsum flatters
     * the layout and hides what real subject lines do to it. But ten rows do not fill a Fold, and a
     * list that cannot scroll cannot be judged for scrolling, which is the thing being worked on.
     *
     * Rules this filler follows so it stays useful rather than decorative:
     *  - Subjects are the same shape and length as the real ones, operations traffic with store
     *    numbers and dates. Several are deliberately long enough to ellipsize.
     *  - Senders reuse the existing domain vocabulary, so the identity-bar colours stay a small,
     *    repeating set rather than turning the list into a paint chart.
     *  - The automated additions stay Tallyman / Power BI / Verdant, because
     *    [reportsBundle]'s `senderSummary` names exactly those three and would otherwise lie.
     *  - No employer brand. See the class KDoc.
     *
     * Delete this whole list the moment the app is reading a live mailbox.
     */
    private val extraMessages: List<GridlinkMessage> = listOf(
        // TODAY
        GridlinkMessage(
            id = "fill-dl-truck",
            sender = "D. Loxwell",
            domain = "gridlink.me",
            subject = "Truck came up 3 cases short again, 2043 HILLCREST",
            timestamp = "9:41 AM",
            unread = true,
            section = GridlinkSection.TODAY,
        ),
        GridlinkMessage(
            id = "fill-scheduling",
            sender = "Scheduling",
            domain = "hrbenefits.example",
            subject = "Week 32 schedules are posted, please review before Thursday",
            timestamp = "9:02 AM",
            unread = false,
            section = GridlinkSection.TODAY,
        ),
        GridlinkMessage(
            id = "fill-sanivex",
            sender = "Sanivex Service",
            domain = "sanivex.example",
            subject = "Technician arriving between 1 and 4 PM for the dish machine",
            timestamp = "8:37 AM",
            unread = true,
            attachment = GridlinkAttachment("sanivex_wo_44120.pdf", "62 KB"),
            section = GridlinkSection.TODAY,
        ),
        GridlinkMessage(
            id = "fill-tperez",
            sender = "T. Perez",
            domain = "gridlink.me",
            subject = "Re: drive thru timer is reading 20 seconds fast",
            timestamp = "8:05 AM",
            unread = false,
            section = GridlinkSection.TODAY,
        ),
        GridlinkMessage(
            id = "fill-jonah-store",
            sender = "Jonah",
            domain = "gridlink.me",
            subject = "are we still doing the thing saturday",
            timestamp = "7:48 AM",
            unread = false,
            section = GridlinkSection.TODAY,
        ),
        GridlinkMessage(
            id = "fill-facilities",
            sender = "Facilities Dispatch",
            domain = "sitecare.example",
            subject = "Work order 88231 closed: walk-in freezer condenser 2096 FERNHILL RD",
            timestamp = "7:20 AM",
            unread = false,
            attachment = GridlinkAttachment("wo_88231_closure.pdf", "141 KB"),
            section = GridlinkSection.TODAY,
        ),
        // YESTERDAY
        GridlinkMessage(
            id = "fill-kbaxter",
            sender = "K. Baxter",
            domain = "gridlink.me",
            subject = "Two no-shows on close, wrote them both up",
            timestamp = "Yesterday",
            unread = true,
            section = GridlinkSection.YESTERDAY,
        ),
        GridlinkMessage(
            id = "fill-payroll",
            sender = "Payroll",
            domain = "hrbenefits.example",
            subject = "Punch corrections for period ending 07/26 are due by noon Monday",
            timestamp = "Yesterday",
            unread = false,
            section = GridlinkSection.YESTERDAY,
        ),
        GridlinkMessage(
            id = "fill-brightmar",
            sender = "Brightmar Regional",
            domain = "brightmar.example",
            subject = "Order confirmation 4471902, two substitutions on your standing order",
            timestamp = "Yesterday",
            unread = false,
            attachment = GridlinkAttachment("picksheet_4471902.pdf", "97 KB"),
            section = GridlinkSection.YESTERDAY,
        ),
        GridlinkMessage(
            id = "fill-training",
            sender = "Training Team",
            domain = "hrbenefits.example",
            subject = "Food safety recertification expires 08/15 for four of your TMs",
            timestamp = "Yesterday",
            unread = true,
            section = GridlinkSection.YESTERDAY,
        ),
        GridlinkMessage(
            id = "fill-amoore",
            sender = "A. Moore",
            domain = "gridlink.me",
            subject = "Can I swap Friday close for Sunday open",
            timestamp = "Yesterday",
            unread = false,
            section = GridlinkSection.YESTERDAY,
        ),
        // EARLIER
        GridlinkMessage(
            id = "fill-guest-relations",
            sender = "Guest Relations",
            domain = "sitecare.example",
            subject = "Guest complaint 2210447 assigned to you, response due within 48 hours",
            timestamp = "Tue",
            unread = true,
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "fill-dalton",
            sender = "Dalton Energy",
            domain = "dalton-energy.example",
            subject = "Your July statement is ready for account ending 7714",
            timestamp = "Tue",
            unread = false,
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "fill-rgorman",
            sender = "R. Gorman",
            domain = "gridlink.me",
            subject = "Numbers for the P7 review, let me know if you want it cut differently",
            timestamp = "Mon",
            unread = false,
            attachment = GridlinkAttachment("p7_review_by_store.xlsx", "412 KB"),
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "fill-insurance",
            sender = "Marden Halloway",
            domain = "mardenmma.example",
            subject = "Certificate of insurance renewal, signature required",
            timestamp = "Mon",
            unread = false,
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "fill-dhollis",
            sender = "D. Hollis",
            domain = "gridlink.me",
            subject = "Lobby TV is stuck on the setup screen again",
            timestamp = "Sun",
            unread = false,
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "fill-permit",
            sender = "Halesworth County",
            domain = "halesworthcounty.example",
            subject = "Health inspection score posted: 2118 ELLSWORTH, 97.5",
            timestamp = "Sun",
            unread = false,
            attachment = GridlinkAttachment("inspection_1206_0726.pdf", "203 KB"),
            section = GridlinkSection.EARLIER,
        ),
        // Automated, to give the bundle enough children to be worth expanding.
        GridlinkMessage(
            id = "fill-tally-kirkwood",
            sender = "Tallyman",
            domain = "tallyman.example",
            subject = "Daily Sales Summary 2071 KIRKWOOD 07/30",
            timestamp = "7:14 AM",
            unread = true,
            attachment = GridlinkAttachment("dss_1155_0730.pdf", "58 KB"),
            automated = true,
        ),
        GridlinkMessage(
            id = "fill-tally-overtime",
            sender = "Tallyman",
            domain = "tallyman.example",
            subject = "Overtime Threshold Alert, 6 TMs projected over 40 hours",
            timestamp = "Yesterday",
            unread = true,
            automated = true,
        ),
        GridlinkMessage(
            id = "fill-pbi-gateway",
            sender = "Power BI Service",
            domain = "microsoft.com",
            subject = "Data gateway offline: ASH-REPORTING-01",
            timestamp = "Yesterday",
            unread = true,
            automated = true,
        ),
        GridlinkMessage(
            id = "fill-verdant-followup",
            sender = "Verdant",
            domain = "verdantfs.example",
            subject = "Follow-up visit scheduled 08/07 Store 604",
            timestamp = "Mon",
            unread = false,
            automated = true,
        ),
    )

    /**
     * The brief's ten plus the filler, as one list. Order is timeline order within a section.
     *
     * The bodies are stitched on here rather than declared inline so the two lists above stay
     * readable against §10's table. 🔴 [GridlinkSampleBodies.bodyFor] throws on a miss, so a new row
     * added without a body fails on the next launch instead of opening to a blank panel.
     */
    val messages: List<GridlinkMessage> = (briefMessages + extraMessages).map { message ->
        message.copy(body = GridlinkSampleBodies.bodyFor(message.id))
    }

    /** Everything the user actually has to read, in timeline order. */
    val humanMessages: List<GridlinkMessage> = messages.filterNot { it.automated }

    /**
     * The robots, as one bundle.
     *
     * The count is 14 rather than the number of rows listed because the brief's own bundle mock
     * says "14 new" — the content table is a sample of a morning, not the whole of it. Keeping the
     * brief's number means the collapsed row reads honestly even though fewer children exist here;
     * the alternative (silently showing the short count) would understate exactly the problem this
     * component exists to solve.
     */
    val reportsBundle = GridlinkBundle(
        title = "Reports",
        unreadCount = 14,
        senderSummary = "Tallyman, Power BI, Verdant",
        messages = messages.filter { it.automated },
    )

    /**
     * Look one up by id, for the gallery's `--es open`.
     *
     * 🔴 Throws on a miss rather than returning null. A typo'd id would otherwise launch straight
     * into the list with no thread and no complaint, which looks exactly like the open transition
     * being broken. Same rule as every other gallery extra.
     */
    fun messageById(id: String): GridlinkMessage = messages.firstOrNull { it.id == id }
        ?: error(
            "No sample message '$id'. Known ids: " + messages.joinToString { it.id },
        )

    /**
     * Everything in the sample that came from [contact], for their contact card, in the order the
     * inbox lists it.
     *
     * ⚠️ Returns empty for a contact nobody has written from, which is most of the address book and
     * is not a failure. 47 people exist so the A-Z rail has something to scrub; about twenty of them
     * send mail. A card with no recent mail is the normal case, not the broken one, so this returns
     * a list rather than throwing the way [messageById] does.
     *
     * Includes the bundled robots. They are pulled out of the *timeline* because a morning of
     * automated reports buries the mail a human sent, and that reasoning is about the inbox. On Power
     * BI Service's own card, the reports are the entire point of the card.
     *
     * 🔴 Matched through [GridlinkSampleContacts.forSender], not by address, so this list and the
     * address printed above it can never disagree about who someone is.
     */
    fun messagesFrom(contact: GridlinkSampleContacts.GridlinkContact): List<GridlinkMessage> =
        messages.filter { GridlinkSampleContacts.forSender(it.sender, it.domain)?.id == contact.id }

    /**
     * This account's own domain: the one Tate and his colleagues send from.
     *
     * 🔴 Written down once because two screens now have to tell an internal counterparty from an
     * external one, and the wrong answer is invisible. [GridlinkEventScreen] uses it to decide that a
     * daily huddle is not an appointment "with gridlink.me"; without it, every internal event would
     * have listed nine colleagues under a heading claiming they were connected to it.
     */
    const val OWN_DOMAIN: String = "gridlink.me"

    /**
     * Everything in the sample sent from [domain], newest first, for [GridlinkEventScreen].
     *
     * ⚠️ Deliberately NOT the same rule as [messagesFrom]. That one resolves a *person* and is right
     * to, because a contact card is about one human. An event names an organisation and nothing
     * finer, so matching it to one contact would drop the other three people at that company. This
     * matches the domain and says so in the heading it feeds.
     */
    fun messagesFromDomain(domain: String): List<GridlinkMessage> =
        messages.filter { it.domain.equals(domain, ignoreCase = true) }
}

/** Which timeline heading a human message falls under. Bundled robots sit outside the timeline. */
enum class GridlinkSection(val label: String) {
    /** Only used by bundled senders, which are pulled out of the timeline entirely. */
    AUTOMATED("Automated"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    EARLIER("Earlier"),
}

data class GridlinkMessage(
    val id: String,
    val sender: String,
    /** Drives the identity bar colour. See `gridlinkSenderBarColor`. */
    val domain: String,
    val subject: String,
    /** Pre-formatted for the mock. The real list formats from a timestamp against "now". */
    val timestamp: String,
    val unread: Boolean = false,
    /**
     * 🔴 The attachment itself, not a boolean.
     *
     * It started as `hasAttachment: Boolean` and became this the moment the thread view needed a
     * file name to draw. Two fields would have been less churn and would also have made it possible
     * to declare a row with a paperclip and no file, which the list would show and the thread would
     * not, with nothing anywhere to catch it. [hasAttachment] is derived so the two can never
     * disagree.
     */
    val attachment: GridlinkAttachment? = null,
    /** True for machine-generated senders, which collapse into [GridlinkBundle]. */
    val automated: Boolean = false,
    val section: GridlinkSection = GridlinkSection.AUTOMATED,
    /**
     * HTML, filled in from [GridlinkSampleBodies] where [GridlinkSample.messages] is assembled.
     *
     * Empty by default only so the thirty-one declarations above stay readable against the brief's
     * §10 table. Nothing should ever construct a [GridlinkMessage] and leave this empty.
     */
    val body: String = "",
) {
    val hasAttachment: Boolean get() = attachment != null

    /**
     * ⚠️ Derived, and invented in the same way the domains are.
     *
     * The brief gives display names and no addresses. Robots get `no-reply@`, which is what they
     * almost always are, and people get their display name flattened. It exists because a thread
     * view that shows only "M. Ridley" hides the one thing you check a header for, which is whether
     * the sender is who the name claims. Replace it with the real header the moment JMAP is wired.
     *
     * 🔴 **The address book wins when it knows this sender.** Two screens now show an address for the
     * same counterparty (this header and their contact card), and deriving one while the card states
     * the other would have the app claim Dalton Energy is `dalton.energy@` here and `service@` two taps
     * away. Only one of those can be true, and the one a human wrote down is the one to trust. See
     * [GridlinkSampleContacts.forSender] for how the match is made and why it is not string equality.
     */
    val address: String
        get() = GridlinkSampleContacts.forSender(sender, domain)?.email
            ?: if (automated) {
                "no-reply@$domain"
            } else {
                sender.lowercase().replace(NON_ADDRESS_CHARS, ".").trim('.') + "@" + domain
            }

    private companion object {
        val NON_ADDRESS_CHARS = Regex("[^a-z0-9]+")
    }
}

data class GridlinkBundle(
    val title: String,
    val unreadCount: Int,
    val senderSummary: String,
    val messages: List<GridlinkMessage>,
) {
    /**
     * The unread this bundle claims beyond the children it actually holds.
     *
     * 🔴 Two separate screens have to agree about this number, so it lives here rather than being
     * recomputed at each of them. The inbox header counts it into "21 unread" and
     * [GridlinkSampleFolders.unreadIn] counts it into the folder tree's Inbox badge; if one of them
     * dropped it, the tab and the tree would sit on screen together disagreeing by ten.
     */
    val phantomUnread: Int
        get() = (unreadCount - messages.count { it.unread }).coerceAtLeast(0)
}
