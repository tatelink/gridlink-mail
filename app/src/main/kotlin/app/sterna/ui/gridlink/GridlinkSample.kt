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
 */
object GridlinkSample {

    val messages: List<GridlinkMessage> = listOf(
        GridlinkMessage(
            id = "tally-hillcrest",
            sender = "Tallyman",
            domain = "tallyman.example",
            subject = "Daily Sales Summary 2043 HILLCREST 07/30",
            timestamp = "7:14 AM",
            unread = false,
            hasAttachment = true,
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
            id = "larkfield-enrollment",
            sender = "Larkfield HR",
            domain = "larkfield.example",
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
            domain = "larkfield.example",
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
            unread = true,
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

    /** Everything the user actually has to read, in timeline order. */
    val humanMessages: List<GridlinkMessage> = messages.filterNot { it.automated }

    /**
     * The robots, as one bundle.
     *
     * The count is 14 rather than the six rows listed because the brief's own bundle mock says
     * "14 new" — the content table is a sample of a morning, not the whole of it. Keeping the
     * brief's number means the collapsed row reads honestly even though only six children exist
     * here; the alternative (silently showing 6) would understate exactly the problem this
     * component exists to solve.
     */
    val reportsBundle = GridlinkBundle(
        title = "Reports",
        unreadCount = 14,
        senderSummary = "Tallyman, Power BI, Verdant",
        messages = messages.filter { it.automated },
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
    val hasAttachment: Boolean = false,
    /** True for machine-generated senders, which collapse into [GridlinkBundle]. */
    val automated: Boolean = false,
    val section: GridlinkSection = GridlinkSection.AUTOMATED,
)

data class GridlinkBundle(
    val title: String,
    val unreadCount: Int,
    val senderSummary: String,
    val messages: List<GridlinkMessage>,
)
