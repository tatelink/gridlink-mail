package app.sterna.ui.gridlink

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
            attachment = GridlinkAttachment("dss_0449_0730.pdf", "61 KB"),
            automated = true,
        ),
        GridlinkMessage(
            id = "pbi-refresh",
            sender = "Power BI Service",
            domain = "microsoft.com",
            subject = "Refresh failed: Area51_P7_Rollup (dataset)",
            timestamp = "6:52 AM",
            unread = false,
            automated = true,
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
            sender = "Jeff",
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
            attachment = GridlinkAttachment("ecolab_wo_44120.pdf", "62 KB"),
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
            sender = "Jeff",
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
            attachment = GridlinkAttachment("picksheet_4471902.pdf", "97 KB"),
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
            attachment = GridlinkAttachment("p7_review_by_store.xlsx", "412 KB"),
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
            attachment = GridlinkAttachment("inspection_0797_0726.pdf", "203 KB"),
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
            attachment = GridlinkAttachment("dss_0120_0730.pdf", "58 KB"),
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
     * view that shows only "M. Rivera" hides the one thing you check a header for, which is whether
     * the sender is who the name claims. Replace it with the real header the moment JMAP is wired.
     */
    val address: String
        get() = if (automated) {
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
)
