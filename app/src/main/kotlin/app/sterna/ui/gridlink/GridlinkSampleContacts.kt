package app.sterna.ui.gridlink

/**
 * The address book the Contacts tab renders.
 *
 * ## Why this exists at all, given §9
 * 🔴 Brief §9 bans invented sample content, and this file is 47 invented people. That override is
 * deliberate and it is the same one [GridlinkSample.extraMessages] already took, for the same
 * reason: the feature under test cannot be seen without the data. An A-Z index with a scrubber is
 * *entirely* a scrolling mechanism, and the mail sample supplies about twenty senders clustered on
 * a handful of letters. Scrubbing that is indistinguishable from scrolling it, so nothing about the
 * thing being built would actually be exercised.
 *
 * What it is NOT is a fresh cast. Every sender that appears in [GridlinkSample] appears here, with
 * the same name, the same domain and a role consistent with the mail it sends, and everyone added
 * around them is drawn from the same regional restaurant-operations world. Mail abbreviates people
 * to an initial ("D. Loxwell") because a From line does; an address book carries the whole name,
 * so those seven are expanded here and the initial still matches. No relationship to Tate is
 * asserted for anyone: Jonah is on gridlink.me and is marked Personal, which is the most the mail
 * sample actually establishes.
 *
 * ## Why the sort key is the surname and the display name is not
 * A phonebook sorted by the first name puts Dara Loxwell under D and Darius Hollis under D and
 * leaves L and H empty, which makes the A-Z rail a list of letters that mostly do nothing. Sorting
 * by surname is what every address book does, and it costs one thing: the eye reads "Dara Loxwell"
 * under the letter L and has to be told why. So [GridlinkContact.family] renders SemiBold and the
 * given name does not, and the heavy word is always the letter's word. An organisation has no given
 * name, so the whole line is the sort key and the whole line is heavy.
 *
 * ## X is empty on purpose
 * ⚠️ Twenty-six letters, twenty-five of them populated. A rail that can only ever land on a letter
 * that has rows is a rail whose empty-letter handling is unreachable, and unreachable code in a
 * prototype is code that gets found by real data instead of by me. X stays empty so the fallback
 * (scrub to the nearest populated letter above) can be seen on the emulator.
 */
object GridlinkSampleContacts {

    /**
     * One entry.
     *
     * [family] is both the surname and the sort key, so an organisation puts its whole name there
     * and leaves [given] empty rather than carrying a separate "is this a person" flag that could
     * disagree with the names.
     */
    data class GridlinkContact(
        val id: String,
        val given: String,
        val family: String,
        val role: String,
        val email: String,
    ) {
        val organization: Boolean get() = given.isEmpty()

        val displayName: String get() = if (organization) family else "$given $family"

        /** Feeds [app.sterna.ui.theme.gridlinkSenderBarColor], exactly as a message's domain does,
         *  so a contact and their mail carry the same identity colour. */
        val domain: String get() = email.substringAfter('@')

        /** The section this lands in. Uppercased once here so nothing downstream has to remember. */
        val letter: Char get() = family.first().uppercaseChar()
    }

    /** A letter and everyone filed under it. Empty letters are absent, not present-and-empty. */
    data class GridlinkContactSection(
        val letter: Char,
        val contacts: List<GridlinkContact>,
    )

    val all: List<GridlinkContact> = listOf(
        GridlinkContact("tallyman", "", "Tallyman Support", "Labour and scheduling platform", "support@tallyman.example"),
        GridlinkContact("ashby", "Paloma", "Ashby", "District Manager, Southgate", "p.ashby@gridlink.me"),
        GridlinkContact("baxter", "Kayla", "Baxter", "Shift Lead, 2118 Ellsworth", "k.baxter@gridlink.me"),
        GridlinkContact("bexley", "Malcolm", "Bexley", "General Manager, 2071 Kirkwood", "m.bexley@gridlink.me"),
        GridlinkContact("cabrera", "Renee", "Cabrera", "Payroll Specialist", "r.cabrera@hrbenefits.example"),
        GridlinkContact("riverbendwater", "", "Riverbend Water", "Utility billing, all stores", "billing@riverbendwater.example"),
        GridlinkContact("dalton", "", "Dalton Energy", "Utility, store accounts", "service@dalton-energy.example"),
        GridlinkContact("duran", "Omar", "Duran", "Maintenance Tech", "o.duran@sitecare.example"),
        GridlinkContact("sanivex", "", "Sanivex Service", "Chemical and warewash service", "service@sanivex.example"),
        GridlinkContact("everly", "Sonia", "Everly", "Recruiter, Northgate Group", "s.everly@northgategroup.example"),
        GridlinkContact("facilities", "", "Facilities Dispatch", "24/7 repair line", "dispatch@sitecare.example"),
        GridlinkContact("fowler", "Nathan", "Fowler", "District Lead", "n.fowler@gridlink.me"),
        GridlinkContact("gorman", "Rhea", "Gorman", "Assistant Manager, 2043 Hillcrest", "r.gorman@gridlink.me"),
        GridlinkContact("guestrelations", "", "Guest Relations", "Customer complaint intake", "guests@sitecare.example"),
        GridlinkContact("hollis", "Darius", "Hollis", "Shift Lead, 2043 Hillcrest", "d.hollis@gridlink.me"),
        GridlinkContact("hrbenefits", "", "HR Benefits", "Enrolment and claims", "benefits@hrbenefits.example"),
        GridlinkContact("ibrahim", "Yusuf", "Ibrahim", "Night Maintenance", "y.ibrahim@sitecare.example"),
        GridlinkContact("northgate", "", "Northgate Group Talent", "Corporate recruiting", "talent@northgategroup.example"),
        GridlinkContact("jennings", "Alicia", "Jennings", "Training Coordinator", "a.jennings@hrbenefits.example"),
        GridlinkContact("kirby", "Wes", "Kirby", "Equipment Tech, fryers and ovens", "w.kirby@sitecare.example"),
        GridlinkContact("loxwell", "Dara", "Loxwell", "General Manager, 2096 Fernhill Rd", "d.loxwell@gridlink.me"),
        GridlinkContact("ludlow", "Brennan", "Ludlow", "Brightmar Account Rep", "b.ludlow@brightmar.example"),
        GridlinkContact("maddox", "Thea", "Maddox", "Catering Coordinator", "t.maddox@gridlink.me"),
        GridlinkContact("marden", "", "Marden Halloway", "Workers comp broker", "claims@mardenmma.example"),
        GridlinkContact("halesworth", "", "Halesworth County", "Health inspections", "health@halesworthcounty.example"),
        GridlinkContact("moore", "Andre", "Moore", "Kitchen Manager, 2096 Fernhill Rd", "a.moore@gridlink.me"),
        GridlinkContact("nakamura", "Grace", "Nakamura", "Regional Trainer", "g.nakamura@hrbenefits.example"),
        GridlinkContact("okafor", "Curtis", "Okafor", "Overnight Cleaning Lead", "c.okafor@sitecare.example"),
        GridlinkContact("harker", "Jonah", "Harker", "Personal", "jonah@gridlink.me"),
        GridlinkContact("payroll", "", "Payroll", "Pay runs and corrections", "payroll@hrbenefits.example"),
        GridlinkContact("perez", "Tomas", "Perez", "Kitchen Manager, 2118 Ellsworth", "t.perez@gridlink.me"),
        GridlinkContact("powerbi", "", "Power BI Service", "Automated report delivery", "no-reply@microsoft.com"),
        GridlinkContact("quintero", "Elena", "Quintero", "Front of House Trainer", "e.quintero@hrbenefits.example"),
        GridlinkContact("randall", "Deshawn", "Randall", "Shift Lead, 2071 Kirkwood", "d.randall@gridlink.me"),
        GridlinkContact("ridley", "Miriam", "Ridley", "Benefits Administrator", "m.ridley@hrbenefits.example"),
        GridlinkContact("sandoval", "Leah", "Sandoval", "Assistant Manager, 2118 Ellsworth", "l.sandoval@gridlink.me"),
        GridlinkContact("scheduling", "", "Scheduling", "Shift swaps and coverage", "scheduling@hrbenefits.example"),
        GridlinkContact("verdant", "", "Verdant", "Pest control", "service@verdantfs.example"),
        GridlinkContact("brightmar", "", "Brightmar Regional", "Food distribution", "orders@brightmar.example"),
        GridlinkContact("training", "", "Training Team", "Certification tracking", "training@hrbenefits.example"),
        GridlinkContact("tran", "Victor", "Tran", "POS Support Tech", "v.tran@tallyman.example"),
        GridlinkContact("ueda", "Nadia", "Ueda", "Food Safety Auditor", "n.ueda@verdantfs.example"),
        GridlinkContact("valdez", "Hector", "Valdez", "Produce Rep, Brightmar", "h.valdez@brightmar.example"),
        GridlinkContact("whitfield", "Monica", "Whitfield", "Store Accountant", "m.whitfield@gridlink.me"),
        GridlinkContact("yancey", "Adele", "Yancey", "HR Generalist", "a.yancey@hrbenefits.example"),
        GridlinkContact("zielinski", "Petra", "Zielinski", "Insurance Adjuster, Marden Halloway", "p.zielinski@mardenmma.example"),
        GridlinkContact("okonkwo", "Chidi", "Okonkwo", "Beverage Vendor Rep", "c.okonkwo@brightmar.example"),
    ).sortedWith(compareBy({ it.family.lowercase() }, { it.given.lowercase() }))

    /** [all], grouped. Computed once: the list is a constant, so regrouping it per recomposition
     *  would be work done every frame of a scrub to produce the same answer. */
    val sections: List<GridlinkContactSection> = all
        .groupBy { it.letter }
        .toSortedMap()
        .map { (letter, contacts) -> GridlinkContactSection(letter, contacts) }

    /** The rail always draws all twenty-six, populated or not. See the note on X. */
    val alphabet: List<Char> = ('A'..'Z').toList()

    /** By id, or null. Used by the harness, which is handed an id off the command line. */
    fun byId(id: String): GridlinkContact? = all.firstOrNull { it.id == id }

    /**
     * The contact a message came from, or null if the sender is not in the address book.
     *
     * ## 🔴 Why this is not `email == address`
     * The obvious version, matching [GridlinkMessage.address] against [GridlinkContact.email], works
     * for people and fails for every organisation, and it fails *silently* by returning null. A
     * message's address is derived from its From name, so "Dalton Energy" produces
     * `dalton.energy@dalton-energy.example` while the card says `service@dalton-energy.example`. Both are
     * plausible, neither is wrong, and nothing in the data says they are the same counterparty.
     * Measured across the whole sample: exact matching resolves nine senders out of twenty-odd, and
     * loses Dalton Energy, Verdant, HR Benefits, Training Team, Guest Relations, Facilities Dispatch,
     * Brightmar Regional, Marden Halloway, Halesworth County, Northgate Group Talent and Sanivex Service.
     * A contact card that says "no recent mail" from someone with four messages in the inbox is a
     * card that reads as broken.
     *
     * So two rules, both scoped to the domain, which is what actually identifies the counterparty:
     *
     *  1. the display names agree ("Dalton Energy" is Dalton Energy at dalton-energy.example);
     *  2. the local parts agree once the sender's name is flattened the same way
     *     [GridlinkMessage.address] flattens it ("M. Ridley" against `m.rivera`).
     *
     * ⚠️ Rule 1 before rule 2, not the other way round. Under rule 2 alone, `no-reply@microsoft.com`
     * would match nothing and Power BI Service would drop out despite being filed by name.
     *
     * ## Tallyman stays unmatched, deliberately
     * "Tallyman" sends mail and the card is filed as "Tallyman Support", so neither rule fires
     * and its card shows no recent mail. The fix would be renaming the contact or giving it a second
     * address, and both make the phonebook worse to make one screen look better: an address book
     * entry whose address is a support alias nobody writes to is not an improvement. One unresolved
     * counterparty out of twenty is also the honest picture of what name matching does against real
     * mail, and it keeps the "no recent mail" state reachable on a card that is otherwise populated.
     */
    fun forSender(sender: String, domain: String): GridlinkContact? {
        val sameDomain = all.filter { it.domain.equals(domain, ignoreCase = true) }
        return sameDomain.firstOrNull { it.displayName.equals(sender, ignoreCase = true) }
            ?: sameDomain.firstOrNull {
                it.email.substringBefore('@').equals(flattenToLocalPart(sender), ignoreCase = true)
            }
    }

    /** The same transform [GridlinkMessage.address] uses, so the two can be compared at all. */
    private fun flattenToLocalPart(name: String): String =
        name.lowercase().replace(NON_ADDRESS_CHARS, ".").trim('.')

    private val NON_ADDRESS_CHARS = Regex("[^a-z0-9]+")
}
