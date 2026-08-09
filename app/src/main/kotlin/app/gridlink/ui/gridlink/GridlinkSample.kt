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
 * neutral "HR Benefits" at Brandon's request, because seeing that brand in mockups was colliding
 * with unrelated work in other sessions. Subject lines are untouched, so the layout is still being
 * tested against the same real-world string lengths.
 */
object GridlinkSample {

    /** §10's ten, untouched. Everything below this list is filler; see [extraMessages]. */
    private val briefMessages: List<GridlinkMessage> = listOf(
        GridlinkMessage(
            id = "alta-belmont",
            sender = "Altametrics",
            domain = "altametrics.com",
            subject = "Daily Sales Summary 0449 BELMONT 07/30",
            timestamp = "7:14 AM",
            unread = false,
            attachments = listOf(GridlinkAttachment("dss_0449_0730.pdf", "61 KB")),
            automated = true,
            // The three sample senders below carry the three unsubscribe methods, one each, so the
            // gallery draws all three confirmation sentences without a network or a real newsletter.
            // 🔴 The other automated messages deliberately carry NONE: "automated" and "can be
            // unsubscribed from" are different facts, and the gallery should show that they are.
            // This one is mailto-only — the method that opens a draft and sends nothing by itself.
            unsubscribe = GridlinkUnsubscribe(
                mailto = "mailto:dss-unsub+0449@altametrics.com?subject=Unsubscribe%200449",
            ),
        ),
        GridlinkMessage(
            id = "pbi-refresh",
            sender = "Power BI Service",
            domain = "microsoft.com",
            subject = "Refresh failed: Area51_P7_Rollup (dataset)",
            timestamp = "6:52 AM",
            unread = false,
            automated = true,
            // A web address with no one-click promise: opens their page, unsubscribes nothing itself.
            unsubscribe = GridlinkUnsubscribe(
                httpUrl = "https://powerbi.microsoft.com/unsubscribe?t=9f2c",
            ),
        ),
        GridlinkMessage(
            id = "steritech-cap",
            sender = "Steritech",
            domain = "steritech.com",
            subject = "ACTION REQUIRED: Corrective Action Plan due 08/04 Store 456",
            timestamp = "6:31 AM",
            unread = true,
            automated = true,
        ),
        GridlinkMessage(
            id = "jeff-dogs",
            sender = "Jeff Harlan",
            domain = "gridlink.me",
            subject = "did you feed the dogs",
            timestamp = "6:22 AM",
            unread = true,
            section = GridlinkSection.TODAY,
        ),
        GridlinkMessage(
            id = "hr-enrollment",
            sender = "HR Benefits",
            domain = "hrbenefits.com",
            subject = "Open Enrollment closes Friday, action needed for all salaried TMs",
            timestamp = "Yesterday",
            unread = false,
            section = GridlinkSection.YESTERDAY,
        ),
        GridlinkMessage(
            id = "alta-randolph",
            sender = "Altametrics",
            domain = "altametrics.com",
            subject = "Labor Variance Exception Report 0459 RANDOLPH RD Week 30",
            timestamp = "Yesterday",
            unread = false,
            automated = true,
        ),
        GridlinkMessage(
            id = "rivera-callout",
            sender = "M. Rivera",
            domain = "hrbenefits.com",
            subject = "Callout Saturday AM, need coverage 120 Pineville",
            timestamp = "Yesterday",
            unread = true,
            section = GridlinkSection.YESTERDAY,
        ),
        GridlinkMessage(
            id = "inspire-fbc",
            sender = "Inspire Brands Talent",
            domain = "inspirebrands.com",
            subject = "Franchise Business Consultant, next steps and availability",
            timestamp = "Tue",
            // ⚠️ Read, on request. §10 has this one unread; it was flipped so there is a read row
            // sitting directly under the timeline's own heading where the difference between the
            // two states is easy to look at. Change it back the moment that stops being useful.
            unread = false,
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "steritech-pest",
            sender = "Steritech",
            domain = "steritech.com",
            subject = "Pest Sighting Report filed 0797 MIDTOWN",
            timestamp = "Tue",
            unread = false,
            automated = true,
        ),
        GridlinkMessage(
            id = "pbi-scorecard",
            sender = "Power BI Service",
            domain = "microsoft.com",
            subject = "Your subscription: Area 51 Weekly Scorecard",
            timestamp = "Mon",
            unread = false,
            automated = true,
            // One-click: the only method that sends something the moment the dialog is confirmed.
            unsubscribe = GridlinkUnsubscribe(
                httpUrl = "https://powerbi.microsoft.com/unsubscribe?t=41ab",
                mailto = "mailto:unsub-41ab@powerbi.microsoft.com",
                oneClick = true,
            ),
        ),
    )

    /**
     * Filler. Invented, and knowingly so.
     *
     * 🔴 This block overrides brief §9's ban on invented sample content, on Brandon's explicit
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
     *  - The automated additions stay Altametrics / Power BI / Steritech, because
     *    [reportsBundle]'s `senderSummary` names exactly those three and would otherwise lie.
     *  - No employer brand. See the class KDoc.
     *
     * Delete this whole list the moment the app is reading a live mailbox.
     */
    private val extraMessages: List<GridlinkMessage> = listOf(
        // TODAY
        GridlinkMessage(
            id = "fill-dl-truck",
            sender = "D. Locklear",
            domain = "gridlink.me",
            subject = "Truck came up 3 cases short again, 0449 BELMONT",
            timestamp = "9:41 AM",
            unread = true,
            section = GridlinkSection.TODAY,
        ),
        GridlinkMessage(
            id = "fill-scheduling",
            sender = "Scheduling",
            domain = "hrbenefits.com",
            subject = "Week 32 schedules are posted, please review before Thursday",
            timestamp = "9:02 AM",
            unread = false,
            section = GridlinkSection.TODAY,
        ),
        GridlinkMessage(
            id = "fill-ecolab",
            sender = "Ecolab Service",
            domain = "ecolab.com",
            subject = "Technician arriving between 1 and 4 PM for the dish machine",
            timestamp = "8:37 AM",
            unread = true,
            attachments = listOf(GridlinkAttachment("ecolab_wo_44120.pdf", "62 KB")),
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
            id = "fill-jeff-store",
            sender = "Jeff Harlan",
            domain = "gridlink.me",
            subject = "are we still doing the thing saturday",
            timestamp = "7:48 AM",
            unread = false,
            section = GridlinkSection.TODAY,
        ),
        GridlinkMessage(
            id = "fill-facilities",
            sender = "Facilities Dispatch",
            domain = "sitecare.com",
            subject = "Work order 88231 closed: walk-in freezer condenser 0459 RANDOLPH RD",
            timestamp = "7:20 AM",
            unread = false,
            attachments = listOf(GridlinkAttachment("wo_88231_closure.pdf", "141 KB")),
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
            domain = "hrbenefits.com",
            subject = "Punch corrections for period ending 07/26 are due by noon Monday",
            timestamp = "Yesterday",
            unread = false,
            section = GridlinkSection.YESTERDAY,
        ),
        GridlinkMessage(
            id = "fill-sysco",
            sender = "Sysco Charlotte",
            domain = "sysco.com",
            subject = "Order confirmation 4471902, two substitutions on your standing order",
            timestamp = "Yesterday",
            unread = false,
            attachments = listOf(GridlinkAttachment("picksheet_4471902.pdf", "97 KB")),
            section = GridlinkSection.YESTERDAY,
        ),
        GridlinkMessage(
            id = "fill-training",
            sender = "Training Team",
            domain = "hrbenefits.com",
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
            domain = "sitecare.com",
            subject = "Guest complaint 2210447 assigned to you, response due within 48 hours",
            timestamp = "Tue",
            unread = true,
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "fill-duke",
            sender = "Duke Energy",
            domain = "duke-energy.com",
            subject = "Your July statement is ready for account ending 7714",
            timestamp = "Tue",
            unread = false,
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "fill-rgarza",
            sender = "R. Garza",
            domain = "gridlink.me",
            subject = "Numbers for the P7 review, let me know if you want it cut differently",
            timestamp = "Mon",
            unread = false,
            attachments = listOf(GridlinkAttachment("p7_review_by_store.xlsx", "412 KB")),
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "fill-insurance",
            sender = "Marsh McLennan",
            domain = "marshmma.com",
            subject = "Certificate of insurance renewal, signature required",
            timestamp = "Mon",
            unread = false,
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "fill-dhinton",
            sender = "D. Hinton",
            domain = "gridlink.me",
            subject = "Lobby TV is stuck on the setup screen again",
            timestamp = "Sun",
            unread = false,
            section = GridlinkSection.EARLIER,
        ),
        GridlinkMessage(
            id = "fill-permit",
            sender = "Mecklenburg County",
            domain = "mecknc.gov",
            subject = "Health inspection score posted: 0797 MIDTOWN, 97.5",
            timestamp = "Sun",
            unread = false,
            attachments = listOf(GridlinkAttachment("inspection_0797_0726.pdf", "203 KB")),
            section = GridlinkSection.EARLIER,
        ),
        // Automated, to give the bundle enough children to be worth expanding.
        GridlinkMessage(
            id = "fill-alta-pineville",
            sender = "Altametrics",
            domain = "altametrics.com",
            subject = "Daily Sales Summary 0120 PINEVILLE 07/30",
            timestamp = "7:14 AM",
            unread = true,
            attachments = listOf(GridlinkAttachment("dss_0120_0730.pdf", "58 KB")),
            automated = true,
        ),
        GridlinkMessage(
            id = "fill-alta-overtime",
            sender = "Altametrics",
            domain = "altametrics.com",
            subject = "Overtime Threshold Alert, 6 TMs projected over 40 hours",
            timestamp = "Yesterday",
            unread = true,
            automated = true,
        ),
        GridlinkMessage(
            id = "fill-pbi-gateway",
            sender = "Power BI Service",
            domain = "microsoft.com",
            subject = "Data gateway offline: CLT-REPORTING-01",
            timestamp = "Yesterday",
            unread = true,
            automated = true,
        ),
        GridlinkMessage(
            id = "fill-steritech-followup",
            sender = "Steritech",
            domain = "steritech.com",
            subject = "Follow-up visit scheduled 08/07 Store 456",
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

    /**
     * Four unfinished messages, which is what the drawer's Drafts row has always claimed.
     *
     * 🔴 Deliberately NOT part of [messages], and that is the whole reason this is a second list
     * rather than four more rows in [extraMessages]. [GridlinkSampleFolders.messagesIn] treats the
     * Inbox as *every message in the pool*, so a draft added to that list would be filed as mail
     * that arrived, in the one folder where mail you have not sent yet must never appear.
     *
     * ⚠️ [GridlinkMessage.sender] is still the account itself, because that is what the real
     * message says: on a draft, `from` is you. What changed is what the ROW draws. Each of these
     * carries a [GridlinkMessage.sentTo], and the fix that put it there went in the mapper, so the
     * live Drafts and Sent lists and this sample now say the same thing (his call, 2026-08-09).
     *
     * 🔴 `draft-no-subject` is deliberately left unaddressed as well, so the sample exercises the
     * one row that has no recipient to name. It draws `(no recipient)`, which is the state it is
     * actually in, and it is the case that would otherwise silently fall back to printing your own
     * name again. A fixture set where every draft is neatly addressed does not test this.
     *
     * One row carries `(no subject)`, spelled the way [GridlinkMailMapping.Labels] spells it: a
     * draft with nothing in the subject field is the commonest draft there is, and a list that has
     * only ever been photographed with four tidy subject lines has not been tested against it.
     */
    val draftMessages: List<GridlinkMessage> = listOf(
        GridlinkMessage(
            id = "draft-sysco-credit",
            sender = GRIDLINK_SAMPLE_ACCOUNT,
            domain = "gridlink.me",
            subject = "Credit request, truck short 3 cases 0449 BELMONT 07/30",
            timestamp = "10:44 AM",
            section = GridlinkSection.TODAY,
            sentTo = GridlinkRecipient("Sysco Charlotte", "sysco.com"),
        ),
        GridlinkMessage(
            id = "draft-cap-456",
            sender = GRIDLINK_SAMPLE_ACCOUNT,
            domain = "gridlink.me",
            subject = "Corrective Action Plan, Store 456, walk-in gasket and prep sink",
            timestamp = "10:12 AM",
            section = GridlinkSection.TODAY,
            // The one multi-recipient row, so the "(+2)" form is on screen in the gallery: a CAP
            // goes back to the inspector with the store and the area director copied.
            sentTo = GridlinkRecipient("Steritech", "steritech.com", others = 2),
        ),
        GridlinkMessage(
            id = "draft-tperez-overtime",
            sender = GRIDLINK_SAMPLE_ACCOUNT,
            domain = "gridlink.me",
            subject = "Re: Overtime projection is over plan for period 7",
            timestamp = "Yesterday",
            section = GridlinkSection.YESTERDAY,
            sentTo = GridlinkRecipient("T. Perez", "gridlink.me"),
        ),
        GridlinkMessage(
            id = "draft-no-subject",
            sender = GRIDLINK_SAMPLE_ACCOUNT,
            domain = "gridlink.me",
            subject = "(no subject)",
            timestamp = "Wed",
            section = GridlinkSection.EARLIER,
            // 🔴 An EMPTY recipient, not a missing one. Leaving this null is not the same state:
            // null means "not an outgoing folder" and the row falls back to [sender], which on a
            // draft is you, which is the bug this whole field exists to kill. The mapper never
            // hands back null in Drafts or Sent, so a fixture that does is showing the gallery a
            // row the live app cannot produce.
            sentTo = GridlinkRecipient(""),
        ),
    ).map { message ->
        message.copy(body = GridlinkSampleBodies.bodyFor(message.id))
    }

    /**
     * Sent mail, held apart from the inbox pool for the same reason [draftMessages] is: mail you
     * sent is not mail that arrived, so these rows exist nowhere else in the sample.
     *
     * 🔴 It exists so the outgoing row can be PHOTOGRAPHED. Every other folder in the gallery is a
     * view over §10's inbox, and Sent was the one screen with nothing in it, which left the real
     * mailbox as the only place to see the change — and a screenshot of that publishes Brandon's
     * actual correspondents. Three rows is enough to show the three cases: an outside vendor, a
     * colleague on the internal domain, and a message with more recipients than the row can name.
     *
     * All three are read, and none carries [GridlinkMessage.unread]. Sent mail you have not read is
     * not a state that exists, and a badge on Sent would be a number with nothing behind it.
     */
    val sentMessages: List<GridlinkMessage> = listOf(
        GridlinkMessage(
            id = "sent-duke-meter",
            sender = GRIDLINK_SAMPLE_ACCOUNT,
            domain = "gridlink.me",
            subject = "Re: Your July statement is ready for account ending 7714",
            timestamp = "9:20 AM",
            section = GridlinkSection.TODAY,
            sentTo = GridlinkRecipient("Duke Energy", "duke-energy.com"),
        ),
        GridlinkMessage(
            id = "sent-kbaxter-schedule",
            sender = GRIDLINK_SAMPLE_ACCOUNT,
            domain = "gridlink.me",
            subject = "Re: Period 8 schedule, approved with one change",
            timestamp = "Yesterday",
            section = GridlinkSection.YESTERDAY,
            sentTo = GridlinkRecipient("K. Baxter", "gridlink.me"),
        ),
        GridlinkMessage(
            id = "sent-alta-recap",
            sender = GRIDLINK_SAMPLE_ACCOUNT,
            domain = "gridlink.me",
            subject = "Weekly recap, all five stores, week ending 08/02",
            timestamp = "Mon",
            section = GridlinkSection.EARLIER,
            attachments = listOf(GridlinkAttachment("recap-wk-0802.pdf", "204 KB")),
            // Four recipients, so the row draws "(+3)" beside the first name. This is the case a
            // joined-and-ellipsised list would silently swallow.
            sentTo = GridlinkRecipient("A. Moore", "gridlink.me", others = 3),
        ),
    ).map { message ->
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
        senderSummary = "Altametrics, Power BI, Steritech",
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
     * This account's own domain: the one Brandon and his colleagues send from.
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
     * 🔴 The attachments themselves, not a boolean.
     *
     * It started as `hasAttachment: Boolean` and became a file the moment the thread view needed a
     * name to draw, then a list the moment real mail arrived carrying more than one: a message with
     * three attachments that shows one and says nothing about the other two is the model lying by
     * omission. [hasAttachment] is derived so the paperclip and the chips can never disagree.
     */
    val attachments: List<GridlinkAttachment> = emptyList(),
    /**
     * The message carries an attachment whose name and size are not known yet.
     *
     * 🔴 The one legitimate way to get a paperclip with [attachments] empty, and it exists because
     * the cache genuinely knows one fact and not the other: a list fetch asks the server for
     * `hasAttachment` and nothing else, and the file itself only arrives with the body. Left out,
     * real mail would either lose the paperclip it has earned or gain a made-up file name to
     * justify it, and the second of those is the thing the note above exists to prevent.
     *
     * So the row shows the clip and the thread lists nothing until the body lands, which is
     * exactly what is known at each point. Sample data never sets this: it has both facts.
     */
    val attachmentPending: Boolean = false,
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
    /**
     * The sender's real address, off the message's own From header.
     *
     * Null for sample data, which has no headers and falls back to the derivation below. Real mail
     * always sets it, and that is the whole point of the field: the derived form is a guess that
     * happens to be right about robots and is frequently wrong about people, and a header is not
     * something to guess at when the message is sitting right there carrying one.
     */
    val addressOverride: String? = null,
    /**
     * True when [body] is the message's plain-text part rather than HTML.
     *
     * 🔴 The two are rendered differently and neither treatment is right for the other. Plain text
     * is reflowed (RFC 3676 soft breaks, or a newsletter arrives with the sender's 72-column line
     * endings baked in) and painted in the app's own colours. HTML carries its own design, so in a
     * dark theme the whole page is inverted instead. Handing one over as the other is not a
     * near-miss, it is a body that looks broken.
     *
     * False for sample data, which is HTML by construction.
     */
    val bodyIsPlainText: Boolean = false,
    /**
     * `cid:` → `data:` for the images the body references inline, so a signature logo or an embedded
     * screenshot draws without going near the network.
     *
     * ⚠️ Not a cache and not an attachment list. These parts arrive with the body, they are already
     * on the device, and they are deliberately exempt from the remote-content block: nothing about
     * showing them tells the sender anything. [GridlinkMailViewModel.attachmentOf] skips the same
     * parts for the opposite reason, so a tracking pixel never grows into a paperclip.
     */
    val inlineImages: Map<String, String> = emptyMap(),
    /**
     * Who the mail went TO, set only in the mailboxes where the sender is you.
     *
     * 🔴 Null is the switch, and null is the normal case. An Inbox row has no recipient worth
     * drawing (it is you), while every row of Sent and Drafts otherwise shows your own name, which
     * turns the loudest line in the list into one column of identical text exactly where the fact
     * you are scanning for should be.
     *
     * ⚠️ [sender], [domain] and [address] deliberately keep pointing at the From header even on
     * these rows, and must not be overloaded to carry the recipient instead: the thread header, the
     * remote-image allowlist and the unsubscribe dialog all read them, and all three mean the
     * sender. This is an extra field precisely so those keep working.
     */
    val sentTo: GridlinkRecipient? = null,
    /**
     * How to unsubscribe from this sender, off the message's own `List-Unsubscribe` header, or null
     * when there is no way to — which is most mail, and which is what hides the action.
     *
     * ⚠️ Arrives with the BODY, not with the row, exactly like [inlineImages] and [bodyIsPlainText]:
     * the header comes back on the single-message fetch, so a row in the list has it null until the
     * message is opened. That is the right shape for the action it drives, which cannot be reached
     * without opening the message first. See [GridlinkUnsubscribe] and the merge in `GridlinkRoot`.
     *
     * 🔴 This replaced [automated] as the signal for the Unsubscribe row. [automated] is a guess off
     * the local part of the address, and it was both too generous (offering to unsubscribe from a
     * no-reply notification with no unsubscribe address anywhere on it) and too mean (hiding the
     * action on a newsletter from a named human). It still decides the bundle, which is a question
     * about how mail is grouped and not about what can be done to it.
     */
    val unsubscribe: GridlinkUnsubscribe? = null,
) {
    val hasAttachment: Boolean get() = attachments.isNotEmpty() || attachmentPending

    /**
     * ⚠️ Derived, and invented in the same way the domains are.
     *
     * The brief gives display names and no addresses. Robots get `no-reply@`, which is what they
     * almost always are, and people get their display name flattened. It exists because a thread
     * view that shows only "M. Rivera" hides the one thing you check a header for, which is whether
     * the sender is who the name claims. Replace it with the real header the moment JMAP is wired.
     *
     * 🔴 **The address book wins when it knows this sender.** Two screens now show an address for the
     * same counterparty (this header and their contact card), and deriving one while the card states
     * the other would have the app claim Duke Energy is `duke.energy@` here and `service@` two taps
     * away. Only one of those can be true, and the one a human wrote down is the one to trust. See
     * [GridlinkSampleContacts.forSender] for how the match is made and why it is not string equality.
     */
    val address: String
        get() = addressOverride
            ?: GridlinkSampleContacts.forSender(sender, domain)?.email
            ?: if (automated) {
                "no-reply@$domain"
            } else {
                sender.lowercase().replace(NON_ADDRESS_CHARS, ".").trim('.') + "@" + domain
            }

    private companion object {
        val NON_ADDRESS_CHARS = Regex("[^a-z0-9]+")
    }
}

/**
 * The one recipient an outgoing row draws, plus how many others there were.
 *
 * ## Why one name and a count, and not the joined list
 * The row is a single line at [GridlinkDimens.messageRowHeight] and shares it with the timestamp, so
 * a joined list of five recipients is one name and an ellipsis: the same information as one name,
 * minus the fact that there were five. The count is the part that survives being cut off, so it is
 * the part that gets its own field.
 *
 * ## Why a blank [name] is a state and not a bug
 * A draft you have written but not addressed genuinely has no recipient, and it is the one row where
 * falling back to the sender would print your own name again, which is the whole thing this field
 * exists to stop. So [line] says so plainly instead.
 *
 * ⚠️ Its English is hard-coded, like every other label in this package, and for the same reason
 * given at [GridlinkMailMapping.Labels]: the package implements a written design brief and its
 * wording is part of that brief. When `ui.gridlink` is localised these three strings move with the
 * four gathered there.
 */
data class GridlinkRecipient(
    /** Display name if the message carried one, else the bare address. Blank = unaddressed. */
    val name: String,
    /** Drives the identity bar colour on outgoing rows, the way `domain` does on incoming ones. */
    val domain: String = "",
    /** Recipients beyond [name]. Zero for the ordinary one-recipient message. */
    val others: Int = 0,
) {
    /** Line 1 of the row. */
    val line: String
        get() = when {
            name.isBlank() -> "(no recipient)"
            others > 0 -> "To $name (+$others)"
            else -> "To $name"
        }

    /**
     * A usable address for this recipient, or blank when there is nothing to build one from.
     *
     * Real mail usually needs no derivation at all: [name] comes from the `To:` header's display
     * name, which already falls back to the bare address when the sender wrote none. The derived
     * form is for the sample, whose fixtures carry display names only, and it is the same invention
     * [GridlinkMessage.address] makes for the same reason. It exists so that tapping a Drafts row
     * opens a composer with the recipient the row just named, rather than an empty To field one tap
     * away from a line reading "To Sysco Charlotte".
     */
    val address: String
        get() = when {
            name.contains('@') -> name
            name.isBlank() || domain.isBlank() -> ""
            else -> name.lowercase().replace(NON_ADDRESS_CHARS, ".").trim('.') + "@" + domain
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
