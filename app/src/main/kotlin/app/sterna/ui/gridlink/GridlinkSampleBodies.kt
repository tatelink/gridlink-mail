package app.sterna.ui.gridlink

/**
 * The body of every sample message, as HTML.
 *
 * ## Why these are here and not in [GridlinkSample]
 * That file is the brief's §10 table, and its whole value is that you can read it against the table
 * and see they match. Thirty-one bodies inlined into it would bury the ten lines that actually have
 * to be checked. The split is by kind: [GridlinkSample] holds what a row shows, this holds prose.
 *
 * ## ⚠️ These are invented, and the §10 senders and subjects are not
 * The brief gives senders, subjects and times, and gives no bodies at all. A thread view cannot be
 * built without them, so they are written here to fit the subject lines they belong to. Same
 * standing as the inferred domains in [GridlinkSample]: replace them the moment the app is reading a
 * live mailbox, and do not let one drift away from the subject line above it.
 *
 * ## 🔴 The safe tag set is smaller than HTML
 * `AnnotatedString.fromHtml` goes through `HtmlCompat`, which produces a `Spanned`, and Compose then
 * maps only the spans it has an equivalent for. Bold, italic, underline, strikethrough, sub, sup,
 * font size, foreground colour and links all survive. **`BulletSpan` and `QuoteSpan` do not**, so
 * `<ul><li>` renders as unmarked lines and `<blockquote>` renders as ordinary text, in both cases
 * with no error anywhere. Bullets below are therefore literal `&#8226;` characters with `<br>`, and
 * nothing uses `<ul>`, `<ol>` or `<blockquote>`.
 *
 * Tables and images are not supported at all. That is a real limit on what this renderer can show
 * and it is why none of these bodies is a marketing email: those need a WebView with remote content
 * blocked, which is a separate job with a privacy decision inside it (tracking pixels), not a tag to
 * add to this list.
 *
 * ## 🔴 No colours in the markup
 * Not one `<font color>` and not one inline style. The palette owns colour, and a body that hard
 * codes `#000000` because it looked right in Day is invisible the moment the app is in Night. The
 * markup here describes structure and emphasis only, and [GridlinkThreadScreen] paints it.
 */
internal object GridlinkSampleBodies {

    /**
     * 🔴 Throws rather than returning a placeholder. A new sample row added without a body would
     * otherwise open to a blank panel that looks like a rendering bug, and would sit there until
     * someone happened to tap that particular row. This fails on the first launch after the row is
     * added, which is when it is cheap to fix.
     */
    fun bodyFor(id: String): String = bodies[id] ?: error(
        "No sample body for message '$id'. Every row in GridlinkSample needs one: add it to " +
            "GridlinkSampleBodies.bodies.",
    )

    private val bodies: Map<String, String> = mapOf(
        // -------------------------------------------------------------------------------------
        // The brief's ten
        // -------------------------------------------------------------------------------------
        "alta-belmont" to (
            "<p><b>Daily Sales Summary</b><br>" +
                "Store 0449 BELMONT &middot; Business date 07/30</p>" +
                "<p>Net sales <b>\$14,208.63</b><br>" +
                "Transactions 1,043<br>" +
                "Average check \$13.62<br>" +
                "Labour 24.1% of net, 0.6 points over plan</p>" +
                "<p>Day part detail and the hourly break-out are attached. Figures are provisional " +
                "until the 4:00 AM poll completes.</p>" +
                "<p>This mailbox is not monitored. Raise reporting issues through the Altametrics " +
                "support portal.</p>"
            ),
        "pbi-refresh" to (
            "<p><b>Refresh failed</b></p>" +
                "<p>Dataset: Area51_P7_Rollup<br>" +
                "Workspace: Area 51 Operations<br>" +
                "Started: 07/30 6:47 AM<br>" +
                "Failed: 07/30 6:52 AM</p>" +
                "<p>Error: <i>The gateway CLT-REPORTING-01 did not respond within the configured " +
                "timeout. Data source: SQL Server, ALTA-PROD.</i></p>" +
                "<p>Two of three retries have been used. The next scheduled refresh is 07/31 at " +
                "6:45 AM.</p>" +
                "<p><a href=\"https://app.powerbi.com/\">Open in Power BI</a></p>"
            ),
        "steritech-cap" to (
            "<p><b>ACTION REQUIRED</b></p>" +
                "<p>A Corrective Action Plan is outstanding for <b>Store 456</b> following the " +
                "inspection on 07/24. It is due <b>08/04</b>.</p>" +
                "<p>Open findings:</p>" +
                "<p>&#8226; Walk-in cooler gasket torn, unit 2<br>" +
                "&#8226; Sanitiser concentration below range at the three-compartment sink<br>" +
                "&#8226; Hand sink at the prep line blocked by a rack</p>" +
                "<p>Submit the completed plan through the Steritech portal. Plans not received by " +
                "the due date escalate to your District Manager.</p>"
            ),
        "jeff-dogs" to (
            "<p>did you feed the dogs</p>" +
                "<p>im not doing this again like last time</p>"
            ),
        "hr-enrollment" to (
            "<p>Open Enrollment for the 2027 plan year closes <b>Friday at 11:59 PM</b>. All " +
                "salaried team members must complete an election, including anyone waiving " +
                "coverage.</p>" +
                "<p>What changed this year:</p>" +
                "<p>&#8226; The HSA employer match moves to 4% of contributions<br>" +
                "&#8226; Vision moves to a two-tier plan<br>" +
                "&#8226; Dependent verification is now required at election, not after</p>" +
                "<p>Elections not completed by the deadline default to <i>no coverage</i> for the " +
                "full plan year. There is no grace period and no qualifying event for missing the " +
                "window.</p>" +
                "<p><a href=\"https://benefits.hrbenefits.com/\">Complete your election</a></p>"
            ),
        "alta-randolph" to (
            "<p><b>Labor Variance Exception Report</b><br>" +
                "Store 0459 RANDOLPH RD &middot; Week 30</p>" +
                "<p>Scheduled 612.0 hours<br>" +
                "Actual 648.5 hours<br>" +
                "Variance <b>+36.5 hours</b>, 6.0% over</p>" +
                "<p>Largest contributors: Saturday close (+9.25), Sunday open (+7.50), Thursday " +
                "mid (+6.00).</p>" +
                "<p>Exceptions over 5% require a written explanation in the labour module before " +
                "Friday.</p>"
            ),
        "rivera-callout" to (
            "<p>Morning,</p>" +
                "<p>Perez called out for Saturday AM at 120 Pineville. That leaves us one short on " +
                "the front counter for the 6 to 2.</p>" +
                "<p>I can cover until 10 but I have the district call at 10:30. Baxter said she " +
                "would take the rest of it if we clear the overtime, she is at 34 hours " +
                "already.</p>" +
                "<p>Let me know either way tonight and I will post it.</p>" +
                "<p>Rivera</p>"
            ),
        "inspire-fbc" to (
            "<p>Hi Brandon,</p>" +
                "<p>Thanks for the call on Tuesday. The team would like to move you to the next " +
                "stage for the <b>Franchise Business Consultant</b> opening covering the " +
                "Carolinas.</p>" +
                "<p>Next steps are a panel with the Regional Director and two current FBCs, about " +
                "ninety minutes, and a short written exercise on multi-unit labour planning sent a " +
                "day beforehand.</p>" +
                "<p>Could you send two or three windows that work in the next fortnight? Mornings " +
                "are easier on our side.</p>" +
                "<p>Best,<br>Sarah Ellison<br>Talent Acquisition</p>"
            ),
        "steritech-pest" to (
            "<p><b>Pest Sighting Report filed</b><br>" +
                "Store 0797 MIDTOWN &middot; Reported 07/28 9:12 PM</p>" +
                "<p>Type: German cockroach, single adult<br>" +
                "Location: Dry storage, behind the shelving on the north wall<br>" +
                "Reported by: K. Baxter, Shift Lead</p>" +
                "<p>A service visit has been requested. No corrective action plan is required for a " +
                "single sighting, but a second report inside thirty days opens one " +
                "automatically.</p>"
            ),
        "pbi-scorecard" to (
            "<p>Your subscription <b>Area 51 Weekly Scorecard</b> ran on Monday at 6:00 AM.</p>" +
                "<p>Top line for week 30:</p>" +
                "<p>&#8226; Net sales \$214,880, up 2.1% on week 29<br>" +
                "&#8226; Labour 23.8% of net, 0.3 points under plan<br>" +
                "&#8226; Drive thru average 214 seconds, 14 over target<br>" +
                "&#8226; Two stores below the 95 service threshold</p>" +
                "<p><a href=\"https://app.powerbi.com/\">View the full scorecard</a></p>"
            ),

        // -------------------------------------------------------------------------------------
        // Filler, matching GridlinkSample.extraMessages
        // -------------------------------------------------------------------------------------
        "fill-dl-truck" to (
            "<p>Truck came up three cases short again at 0449 BELMONT. Two cases of the 8 inch " +
                "tortillas and one of the 5 pound cheese.</p>" +
                "<p>Third week running. I have photos of the seal and the manifest if you want " +
                "them for the claim.</p>"
            ),
        "fill-scheduling" to (
            "<p>Week 32 schedules are posted for all six stores.</p>" +
                "<p>Please review your store before <b>Thursday</b>. Anything not disputed by then " +
                "locks, and changes after that need a manager override.</p>"
            ),
        "fill-ecolab" to (
            "<p>A technician is scheduled for your dish machine between <b>1 and 4 PM</b> " +
                "today.</p>" +
                "<p>The work order covers the rinse temperature fault reported on 07/28 and a full " +
                "descale. Expect the machine to be out of service for about ninety minutes.</p>" +
                "<p>The service sheet is attached. Please have someone available to sign at " +
                "completion.</p>"
            ),
        "fill-tperez" to (
            "<p>Checked it this morning. The timer is reading about twenty seconds fast against " +
                "the stopwatch, same on both lanes, so it is the board and not the loop.</p>" +
                "<p>I put a ticket in with the vendor. Until they come out, treat the board number " +
                "as optimistic.</p>"
            ),
        "fill-jeff-store" to (
            "<p>are we still doing the thing saturday</p>" +
                "<p>if we are i need to know before friday because of the other thing</p>"
            ),
        "fill-facilities" to (
            "<p><b>Work order 88231 closed</b><br>" +
                "0459 RANDOLPH RD &middot; Walk-in freezer condenser</p>" +
                "<p>Condenser fan motor replaced and the coil cleaned. Box pulled down to -2F " +
                "within forty minutes of the repair and held.</p>" +
                "<p>Labour 2.5 hours, parts \$318.40. The closure report is attached.</p>"
            ),
        "fill-kbaxter" to (
            "<p>Two no shows on close last night, Delgado and the new hire whose name I still " +
                "cannot spell. Wrote them both up, copies are in the binder.</p>" +
                "<p>We got out at 12:40 with three people. Not doing that again.</p>"
            ),
        "fill-payroll" to (
            "<p>Punch corrections for the period ending <b>07/26</b> are due by <b>noon " +
                "Monday</b>.</p>" +
                "<p>Nine open exceptions across your stores: six missed clock outs, two unpaid " +
                "breaks under thirty minutes, one duplicate punch.</p>" +
                "<p>Anything not corrected by the deadline pays as punched and comes out of the " +
                "following period.</p>"
            ),
        "fill-sysco" to (
            "<p><b>Order confirmation 4471902</b><br>" +
                "Delivery Thursday, window 4:00 to 7:00 AM</p>" +
                "<p>Two substitutions on your standing order:</p>" +
                "<p>&#8226; Bacon, 18/22 slice, substituted 14/18 slice, same case price<br>" +
                "&#8226; Pickle chips, 5 gallon, substituted 4 gallon, price adjusted</p>" +
                "<p>The full pick sheet is attached. Substitutions can be refused at the door.</p>"
            ),
        "fill-training" to (
            "<p>Food safety recertification expires <b>08/15</b> for four of your team " +
                "members.</p>" +
                "<p>&#8226; M. Bell, 0120 Pineville<br>" +
                "&#8226; K. Baxter, 0797 Midtown<br>" +
                "&#8226; A. Moore, 0449 Belmont<br>" +
                "&#8226; D. Hinton, 0459 Randolph Rd</p>" +
                "<p>The course runs about four hours and can be split. Anyone lapsed cannot be " +
                "scheduled as the certified manager on duty.</p>"
            ),
        "fill-amoore" to (
            "<p>Can I swap Friday close for Sunday open? Hinton said he would take the close if it " +
                "is alright with you.</p>" +
                "<p>It is for my sister's thing, I would not ask otherwise.</p>"
            ),
        "fill-guest-relations" to (
            "<p><b>Guest complaint 2210447</b> has been assigned to you. A response is due within " +
                "<b>48 hours</b>.</p>" +
                "<p>Summary: guest reports a twenty six minute wait in the drive thru on 07/27 at " +
                "approximately 7:40 PM, order incorrect on arrival, no receipt offered.</p>" +
                "<p>Store: 0449 BELMONT. Contact preference: email.</p>" +
                "<p>Responses go through the Guest Relations portal, not by replying here.</p>"
            ),
        "fill-duke" to (
            "<p>Your July statement is ready for the account ending <b>7714</b>.</p>" +
                "<p>Amount due \$2,914.66<br>" +
                "Due date 08/18</p>" +
                "<p>Usage is up 11% on July last year, which tracks the temperatures rather than " +
                "anything at the meter.</p>" +
                "<p><a href=\"https://www.duke-energy.com/\">View your statement</a></p>"
            ),
        "fill-rgarza" to (
            "<p>Numbers for the P7 review are attached. I cut it by store and then by day part, " +
                "which is how Anand asked for it last time.</p>" +
                "<p>Two things worth a look before Monday: Pineville's late night is carrying the " +
                "whole period, and Midtown's waste is up for the third week without a matching " +
                "sales move.</p>" +
                "<p>Let me know if you want it cut differently, it is about ten minutes to " +
                "redo.</p>"
            ),
        "fill-insurance" to (
            "<p>The certificate of insurance for the 2027 policy year is ready and requires your " +
                "signature.</p>" +
                "<p>Coverage is unchanged. The general liability limit stays at \$1M per occurrence " +
                "and \$2M aggregate, and the additional insured schedule now lists all six " +
                "locations.</p>" +
                "<p>Please sign by <b>08/08</b> so the certificate issues before the current one " +
                "lapses.</p>"
            ),
        "fill-dhinton" to (
            "<p>Lobby TV is stuck on the setup screen again. Same as last month.</p>" +
                "<p>I pulled the power for a minute and it came back to the same screen. Do you " +
                "want me to leave it off or keep trying?</p>"
            ),
        "fill-permit" to (
            "<p><b>Health inspection score posted</b><br>" +
                "0797 MIDTOWN &middot; Inspected 07/26</p>" +
                "<p>Score <b>97.5</b>, grade A.</p>" +
                "<p>Two deductions: a thermometer missing from the reach-in at the prep line, and " +
                "one uncovered container in the walk-in. Both were corrected during the " +
                "inspection.</p>" +
                "<p>The signed report is attached and the score is public as of today.</p>"
            ),
        "fill-alta-pineville" to (
            "<p><b>Daily Sales Summary</b><br>" +
                "Store 0120 PINEVILLE &middot; Business date 07/30</p>" +
                "<p>Net sales <b>\$11,764.19</b><br>" +
                "Transactions 902<br>" +
                "Average check \$13.04<br>" +
                "Labour 25.8% of net, 1.3 points over plan</p>" +
                "<p>Late night carried the day again at 22% of net. Full detail attached.</p>" +
                "<p>This mailbox is not monitored.</p>"
            ),
        "fill-alta-overtime" to (
            "<p><b>Overtime Threshold Alert</b></p>" +
                "<p>Six team members are projected over 40 hours for the current week, based on " +
                "punches through Thursday close.</p>" +
                "<p>&#8226; K. Baxter, 44.25 projected<br>" +
                "&#8226; T. Perez, 43.00<br>" +
                "&#8226; A. Moore, 42.50<br>" +
                "&#8226; D. Hinton, 41.75<br>" +
                "&#8226; J. Delgado, 41.25<br>" +
                "&#8226; R. Salas, 40.50</p>" +
                "<p>Adjust the remaining shifts in the labour module or approve the overtime before " +
                "the period closes.</p>"
            ),
        "fill-pbi-gateway" to (
            "<p><b>Data gateway offline</b></p>" +
                "<p>Gateway: CLT-REPORTING-01<br>" +
                "Last heartbeat: 07/30 11:04 PM<br>" +
                "Affected datasets: 7</p>" +
                "<p>Scheduled refreshes against this gateway will fail until it reconnects. No " +
                "action is taken automatically.</p>"
            ),
        "fill-steritech-followup" to (
            "<p>A follow-up visit has been scheduled for <b>08/07</b> at <b>Store 456</b> to verify " +
                "the corrective actions from the 07/24 inspection.</p>" +
                "<p>The visit is unannounced within the business day. Please make sure the walk-in " +
                "gasket has been replaced and the sanitiser log is current for the full period.</p>"
            ),
    )
}
