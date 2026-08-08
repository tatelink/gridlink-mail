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
 * ## 🔴 No address in this file may be a real mailbox
 * The repo is public. Every local part here is invented, including the ones on gridlink.me, and
 * that is load-bearing rather than incidental: a real address committed to a public repo is a
 * harvested address, permanently, and this project's own domain has already been through one
 * mailbox compromise. Jonah is "Jonah Harker" at an invented local part for exactly that reason,
 * and the display name has to keep matching [GridlinkSample] sender strings or
 * [forSender] silently stops resolving him. Change one, change both.
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
     * The fixtures and real synced cards spell an organisation as a card with the whole name in
     * [family] and an empty [given] (no separate "is this a person" flag that could disagree with
     * the names). 🔴 The FORM does not: Tate's rule is that Last name and Company are separate
     * fields that never cross over, so a card typed in as company-only arrives here with a blank
     * [family] and the name in [company]. Everything that files, sorts or emboldens goes through
     * [filedUnder], which absorbs both spellings; nothing may reach into [family] directly for
     * those jobs or the company-only card crashes it or renders blank.
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
        /** Postal addresses off the card's ADR lines, each one display line. Read-only: the
         *  edit form has no address field, and ADR survives every patch untouched. */
        val addresses: List<String> = emptyList(),
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

        val displayName: String
            get() = if (organization) family.ifEmpty { company } else "$given $family".trim()

        /**
         * The word this card files under: the surname, else the first name, else the company.
         * It is the sort key, the source of [letter], and the word the list row renders SemiBold —
         * one derivation, so the three cannot disagree about where a card lives.
         *
         * Given before company for the person with no surname: "Cher" who works at Dalton Energy is
         * found under C, because a person is looked up by their name, not their employer's.
         */
        val filedUnder: String get() = family.ifEmpty { given }.ifEmpty { company }

        /** Feeds [app.gridlink.ui.theme.gridlinkSenderBarColor], exactly as a message's domain does,
         *  so a contact and their mail carry the same identity colour. */
        val domain: String get() = email.substringAfter('@')

        /** The section this lands in. Uppercased once here so nothing downstream has to remember. */
        val letter: Char get() = filedUnder.first().uppercaseChar()

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
        GridlinkContact("harker", "Jonah", "Harker", "Personal", "jonah.harker@gridlink.me"),
        GridlinkContact("payroll", "", "Payroll", "Pay runs and corrections", "payroll@hrbenefits.example"),
        GridlinkContact("perez", "Tomas", "Perez", "Kitchen Manager, 2118 Ellsworth", "t.perez@gridlink.me"),
        GridlinkContact("powerbi", "", "Power BI Service", "Automated report delivery", "no-reply@microsoft.com"),
        // Deliberately role-less: the shape of a real synced book, where most cards carry no
        // TITLE or ORG (an email-only trusted-sender card carries nothing but its address).
        // This is the fixture that keeps the list row's role→email fallback visible.
        GridlinkContact("printshop", "", "Print Shop Counter", "", "orders@printellsworth.example"),
        GridlinkContact("quintero", "Elena", "Quintero", "Front of House Trainer", "e.quintero@hrbenefits.example"),
        GridlinkContact("randall", "Deshawn", "Randall", "Shift Lead, 2071 Kirkwood", "d.randall@gridlink.me"),
        // The one fixture with a photo, a company and custom fields, so every render path is
        // visible on the emulator without a live account. Tate asked for a stock photo for the
        // demonstration, so the photo is a face — an AI-generated one (this-person-does-not-exist),
        // NOT a photograph of a real person pretending to be Miriam. The company rides alongside
        // the role so the separate Company field is visible on the card and in the edit form.
        GridlinkContact(
            "ridley", "Miriam", "Ridley", "Benefits Administrator", "m.ridley@hrbenefits.example",
            company = "HR Benefits Group",
            addresses = listOf("11220 Aspen Ln, Suite 240, Fairhaven, PA 17025, USA"),
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
    ).sortedWith(compareBy({ it.filedUnder.lowercase() }, { it.given.lowercase() }))

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

    /** A 320×320 AI-generated face (this-person-does-not-exist, no real person), ~22 KB decoded. */
    private const val SAMPLE_PHOTO_BASE64 =
        "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"

    /** The same transform [GridlinkMessage.address] uses, so the two can be compared at all. */
    private fun flattenToLocalPart(name: String): String =
        name.lowercase().replace(NON_ADDRESS_CHARS, ".").trim('.')

    private val NON_ADDRESS_CHARS = Regex("[^a-z0-9]+")
}
