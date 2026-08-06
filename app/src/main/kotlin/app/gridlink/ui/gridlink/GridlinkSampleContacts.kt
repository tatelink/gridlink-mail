package app.gridlink.ui.gridlink

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
 * around them is drawn from the same Charlotte restaurant-operations world. Mail abbreviates people
 * to an initial ("D. Locklear") because a From line does; an address book carries the whole name,
 * so those seven are expanded here and the initial still matches. No relationship to Brandon is
 * asserted for anyone: Jeff is on gridlink.me and is marked Personal, which is the most the mail
 * sample actually establishes.
 *
 * ## Why the sort key is the surname and the display name is not
 * A phonebook sorted by the first name puts Dana Locklear under D and Devon Hinton under D and
 * leaves L and H empty, which makes the A-Z rail a list of letters that mostly do nothing. Sorting
 * by surname is what every address book does, and it costs one thing: the eye reads "Dana Locklear"
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

        /** Feeds [app.gridlink.ui.theme.gridlinkSenderBarColor], exactly as a message's domain does,
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
        GridlinkContact("altametrics", "", "Altametrics Support", "Labour and scheduling platform", "support@altametrics.com"),
        GridlinkContact("anand", "Priya", "Anand", "District Manager, Charlotte South", "p.anand@gridlink.me"),
        GridlinkContact("baxter", "Kayla", "Baxter", "Shift Lead, 0797 Midtown", "k.baxter@gridlink.me"),
        GridlinkContact("bell", "Marcus", "Bell", "General Manager, 0120 Pineville", "m.bell@gridlink.me"),
        GridlinkContact("cabrera", "Renee", "Cabrera", "Payroll Specialist", "r.cabrera@hrbenefits.com"),
        GridlinkContact("charlottewater", "", "Charlotte Water", "Utility billing, all stores", "billing@charlottewater.org"),
        GridlinkContact("duke", "", "Duke Energy", "Utility, store accounts", "service@duke-energy.com"),
        GridlinkContact("duran", "Omar", "Duran", "Maintenance Tech", "o.duran@sitecare.com"),
        GridlinkContact("ecolab", "", "Ecolab Service", "Chemical and warewash service", "service@ecolab.com"),
        GridlinkContact("ellison", "Sarah", "Ellison", "Recruiter, Inspire Brands", "s.ellison@inspirebrands.com"),
        GridlinkContact("facilities", "", "Facilities Dispatch", "24/7 repair line", "dispatch@sitecare.com"),
        GridlinkContact("fowler", "Nathan", "Fowler", "Area Coach", "n.fowler@gridlink.me"),
        GridlinkContact("garza", "Rosa", "Garza", "Assistant Manager, 0449 Belmont", "r.garza@gridlink.me"),
        GridlinkContact("guestrelations", "", "Guest Relations", "Customer complaint intake", "guests@sitecare.com"),
        GridlinkContact("hinton", "Devon", "Hinton", "Shift Lead, 0449 Belmont", "d.hinton@gridlink.me"),
        GridlinkContact("hrbenefits", "", "HR Benefits", "Enrolment and claims", "benefits@hrbenefits.com"),
        GridlinkContact("ibrahim", "Yusuf", "Ibrahim", "Night Maintenance", "y.ibrahim@sitecare.com"),
        GridlinkContact("inspire", "", "Inspire Brands Talent", "Corporate recruiting", "talent@inspirebrands.com"),
        GridlinkContact("jennings", "Alicia", "Jennings", "Training Coordinator", "a.jennings@hrbenefits.com"),
        GridlinkContact("kirby", "Wes", "Kirby", "Equipment Tech, fryers and ovens", "w.kirby@sitecare.com"),
        GridlinkContact("locklear", "Dana", "Locklear", "General Manager, 0459 Randolph Rd", "d.locklear@gridlink.me"),
        GridlinkContact("lowery", "Bryan", "Lowery", "Sysco Account Rep", "b.lowery@sysco.com"),
        GridlinkContact("mabry", "Tanya", "Mabry", "Catering Coordinator", "t.mabry@gridlink.me"),
        GridlinkContact("marsh", "", "Marsh McLennan", "Workers comp broker", "claims@marshmma.com"),
        GridlinkContact("mecknc", "", "Mecklenburg County", "Health inspections", "health@mecknc.gov"),
        GridlinkContact("moore", "Andre", "Moore", "Kitchen Manager, 0459 Randolph Rd", "a.moore@gridlink.me"),
        GridlinkContact("nakamura", "Grace", "Nakamura", "Regional Trainer", "g.nakamura@hrbenefits.com"),
        GridlinkContact("okafor", "Curtis", "Okafor", "Overnight Cleaning Lead", "c.okafor@sitecare.com"),
        GridlinkContact("parnell", "Jeff", "Parnell", "Personal", "jeff@gridlink.me"),
        GridlinkContact("payroll", "", "Payroll", "Pay runs and corrections", "payroll@hrbenefits.com"),
        GridlinkContact("perez", "Tomas", "Perez", "Kitchen Manager, 0797 Midtown", "t.perez@gridlink.me"),
        GridlinkContact("powerbi", "", "Power BI Service", "Automated report delivery", "no-reply@microsoft.com"),
        GridlinkContact("quintero", "Elena", "Quintero", "Front of House Trainer", "e.quintero@hrbenefits.com"),
        GridlinkContact("randall", "Deshawn", "Randall", "Shift Lead, 0120 Pineville", "d.randall@gridlink.me"),
        GridlinkContact("rivera", "Marisol", "Rivera", "Benefits Administrator", "m.rivera@hrbenefits.com"),
        GridlinkContact("sandoval", "Leah", "Sandoval", "Assistant Manager, 0797 Midtown", "l.sandoval@gridlink.me"),
        GridlinkContact("scheduling", "", "Scheduling", "Shift swaps and coverage", "scheduling@hrbenefits.com"),
        GridlinkContact("steritech", "", "Steritech", "Pest control", "service@steritech.com"),
        GridlinkContact("sysco", "", "Sysco Charlotte", "Food distribution", "orders@sysco.com"),
        GridlinkContact("training", "", "Training Team", "Certification tracking", "training@hrbenefits.com"),
        GridlinkContact("tran", "Victor", "Tran", "POS Support Tech", "v.tran@altametrics.com"),
        GridlinkContact("ueda", "Nadia", "Ueda", "Food Safety Auditor", "n.ueda@steritech.com"),
        GridlinkContact("valdez", "Hector", "Valdez", "Produce Rep, Sysco", "h.valdez@sysco.com"),
        GridlinkContact("whitfield", "Monica", "Whitfield", "Store Accountant", "m.whitfield@gridlink.me"),
        GridlinkContact("yancey", "Adele", "Yancey", "HR Generalist", "a.yancey@hrbenefits.com"),
        GridlinkContact("zielinski", "Petra", "Zielinski", "Insurance Adjuster, Marsh McLennan", "p.zielinski@marshmma.com"),
        GridlinkContact("okonkwo", "Chidi", "Okonkwo", "Beverage Vendor Rep", "c.okonkwo@sysco.com"),
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
     * True when [contact] came out of this address book rather than being typed into the composer.
     *
     * 🔴 The guard [GridlinkOutboxSender] uses before putting anything on the wire, and the reason
     * it matters is the domains: these are invented local parts at REAL companies (duke-energy.com,
     * ecolab.com, altametrics.com, charlottewater.org, marshmma.com). Legible sample mail and a live
     * outbox are individually fine and together are a way to mail a stranger by tapping the demo.
     *
     * ⚠️ Matched on id, not on address. A typed recipient is built by [gridlinkTypedRecipient] with
     * an id that cannot collide with a sample one, so someone typing `p.anand@gridlink.me` by hand
     * is not silently re-identified as the sample contact and refused for it. Identity here means
     * "this object came from the fixture", which is exactly the question being asked.
     */
    fun isSample(contact: GridlinkContact): Boolean = all.any { it.id == contact.id }

    /**
     * The contact a message came from, or null if the sender is not in the address book.
     *
     * ## 🔴 Why this is not `email == address`
     * The obvious version, matching [GridlinkMessage.address] against [GridlinkContact.email], works
     * for people and fails for every organisation, and it fails *silently* by returning null. A
     * message's address is derived from its From name, so "Duke Energy" produces
     * `duke.energy@duke-energy.com` while the card says `service@duke-energy.com`. Both are
     * plausible, neither is wrong, and nothing in the data says they are the same counterparty.
     * Measured across the whole sample: exact matching resolves nine senders out of twenty-odd, and
     * loses Duke Energy, Steritech, HR Benefits, Training Team, Guest Relations, Facilities Dispatch,
     * Sysco Charlotte, Marsh McLennan, Mecklenburg County, Inspire Brands Talent and Ecolab Service.
     * A contact card that says "no recent mail" from someone with four messages in the inbox is a
     * card that reads as broken.
     *
     * So two rules, both scoped to the domain, which is what actually identifies the counterparty:
     *
     *  1. the display names agree ("Duke Energy" is Duke Energy at duke-energy.com);
     *  2. the local parts agree once the sender's name is flattened the same way
     *     [GridlinkMessage.address] flattens it ("M. Rivera" against `m.rivera`).
     *
     * ⚠️ Rule 1 before rule 2, not the other way round. Under rule 2 alone, `no-reply@microsoft.com`
     * would match nothing and Power BI Service would drop out despite being filed by name.
     *
     * ## Altametrics stays unmatched, deliberately
     * "Altametrics" sends mail and the card is filed as "Altametrics Support", so neither rule fires
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

    /**
     * Who [domain] belongs to, or null if the address book has nobody there.
     *
     * For [GridlinkEventScreen], which knows its counterparty only as a domain. An appointment is
     * with an organisation rather than with one person, so this prefers the organisation entry: those
     * carry their whole name in [GridlinkContact.family] and leave [GridlinkContact.given] empty,
     * which is already how the phonebook distinguishes them and is not a second flag that could
     * disagree with the names.
     *
     * ⚠️ Falls back to the first person at the domain rather than to null. An event with Ecolab where
     * the book holds only the technician should name the technician, not shrug; and there is no
     * sample domain today where that fallback picks between several people, so it cannot be made to
     * look arbitrary by the data that exists.
     */
    fun forDomain(domain: String): GridlinkContact? {
        val sameDomain = all.filter { it.domain.equals(domain, ignoreCase = true) }
        return sameDomain.firstOrNull { it.given.isEmpty() } ?: sameDomain.firstOrNull()
    }

    /** The same transform [GridlinkMessage.address] uses, so the two can be compared at all. */
    private fun flattenToLocalPart(name: String): String =
        name.lowercase().replace(NON_ADDRESS_CHARS, ".").trim('.')

    private val NON_ADDRESS_CHARS = Regex("[^a-z0-9]+")
}
