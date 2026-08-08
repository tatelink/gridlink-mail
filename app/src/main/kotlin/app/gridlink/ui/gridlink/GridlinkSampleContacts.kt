package app.gridlink.ui.gridlink

import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.jmap.model.ContactCardCustomField
import app.gridlink.core.jmap.model.ContactCardPhoto

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
        /** Every address on the card, in its order. Fixtures leave this empty; see [allEmails]. */
        val emails: List<String> = emptyList(),
        val phones: List<String> = emptyList(),
        /** A person's employer. Blank on an organization card — there, [family] IS the company. */
        val company: String = "",
        val jobTitle: String = "",
        val note: String = "",
        /** The card's photograph, already encoded for the wire. Null means the card has none,
         *  and the viewer renders nothing in its place — a photo is data, not decoration. */
        val photo: ContactCardPhoto? = null,
        /** User-defined labelled values, rendered after the built-in details. */
        val customFields: List<ContactCardCustomField> = emptyList(),
        /**
         * The exact form seed for editing, when this row came off a real card. It is the SAME
         * derivation [app.gridlink.core.data.dav.DavRepository.updateContact] diffs against, which
         * is what makes an opened-untouched-saved card a no-op on the wire; deriving a seed from
         * the display fields instead would make that same save spuriously read as a name change on
         * any card whose display name was promoted (an `ORG == FN` company, a fallback surname).
         */
        val edit: ContactEdit? = null,
    ) {
        val organization: Boolean get() = given.isEmpty()

        val displayName: String get() = if (organization) family else "$given $family"

        /** Feeds [app.gridlink.ui.theme.gridlinkSenderBarColor], exactly as a message's domain does,
         *  so a contact and their mail carry the same identity colour. */
        val domain: String get() = email.substringAfter('@')

        /** The section this lands in. Uppercased once here so nothing downstream has to remember. */
        val letter: Char get() = family.first().uppercaseChar()

        /** What the card actually lists, with the fixtures' single [email] as the one-line case. */
        val allEmails: List<String>
            get() = emails.ifEmpty { listOfNotNull(email.takeIf { it.isNotBlank() }) }

        /**
         * What the edit form opens with: [edit] verbatim for a real card, else the honest
         * reconstruction from display fields that fixtures and typed recipients can offer. The
         * fixture [role] rides in as the title because that is what every fixture role is.
         */
        val editSeed: ContactEdit
            get() = edit ?: ContactEdit(
                given = given,
                family = family,
                company = company,
                title = jobTitle.ifEmpty { role },
                emails = allEmails,
                phones = phones,
                note = note,
                photo = photo,
                customFields = customFields,
            )
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
        // The one fixture with a photo and custom fields, so both render paths are visible on the
        // emulator without a live account. §9's override note above covers this too: the photo is
        // a generated abstract landscape, not a person's face pretending to be Miriam.
        GridlinkContact(
            "ridley", "Miriam", "Ridley", "Benefits Administrator", "m.ridley@hrbenefits.example",
            photo = ContactCardPhoto("image/jpeg", SAMPLE_PHOTO_BASE64),
            customFields = listOf(
                ContactCardCustomField("Office", "Highgate, Suite 240"),
                ContactCardCustomField("Case portal ID", "HRB-4417"),
            ),
        ),
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
     * True when [contact] came out of this address book rather than being typed into the composer.
     *
     * 🔴 The guard [GridlinkOutboxSender] uses before putting anything on the wire, and the reason
     * it matters is the domains: these are invented local parts at invented companies (dalton-energy.example,
     * sanivex.example, tallyman.example, riverbendwater.example, mardenmma.example). Legible sample mail and a live
     * outbox are individually fine and together are a way to mail a stranger by tapping the demo.
     *
     * ⚠️ Matched on id, not on address. A typed recipient is built by [gridlinkTypedRecipient] with
     * an id that cannot collide with a sample one, so someone typing `p.ashby@gridlink.me` by hand
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

    /**
     * Who [domain] belongs to, or null if the address book has nobody there.
     *
     * For [GridlinkEventScreen], which knows its counterparty only as a domain. An appointment is
     * with an organisation rather than with one person, so this prefers the organisation entry: those
     * carry their whole name in [GridlinkContact.family] and leave [GridlinkContact.given] empty,
     * which is already how the phonebook distinguishes them and is not a second flag that could
     * disagree with the names.
     *
     * ⚠️ Falls back to the first person at the domain rather than to null. An event with Sanivex where
     * the book holds only the technician should name the technician, not shrug; and there is no
     * sample domain today where that fallback picks between several people, so it cannot be made to
     * look arbitrary by the data that exists.
     */
    fun forDomain(domain: String): GridlinkContact? {
        val sameDomain = all.filter { it.domain.equals(domain, ignoreCase = true) }
        return sameDomain.firstOrNull { it.given.isEmpty() } ?: sameDomain.firstOrNull()
    }

    /** A 160×160 generated JPEG (hills under a pale sun), ~1.5 KB decoded. */
    private const val SAMPLE_PHOTO_BASE64 =
        "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"

    /** The same transform [GridlinkMessage.address] uses, so the two can be compared at all. */
    private fun flattenToLocalPart(name: String): String =
        name.lowercase().replace(NON_ADDRESS_CHARS, ".").trim('.')

    private val NON_ADDRESS_CHARS = Regex("[^a-z0-9]+")
}
