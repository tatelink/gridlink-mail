package app.gridlink.core.data.contacts

import app.gridlink.core.data.text.ContentLine
import app.gridlink.core.data.text.ContentLines

/**
 * One parsed vCard, reduced to the fields the address book renders.
 *
 * The three `fileAs*` values are the important part of this type: they are the ones guaranteed to be
 * usable, and they exist because the raw fields are not. See [fileAsFamily].
 */
data class ParsedContact(
    /** The card's own UID if it has one. Not the sync identity: that is the DAV href. */
    val uid: String?,
    val formattedName: String?,
    /** Surname exactly as `N` gave it, which may be blank, wrong, or absent. */
    val family: String,
    /** Given name exactly as `N` gave it. */
    val given: String,
    val organization: String?,
    val title: String?,
    val emails: List<String>,
    /** Every TEL on the card, preferred number first, in the wire's own formatting. */
    val phones: List<String> = emptyList(),
    /** The card's NOTE, unescaped. Free text; may span lines. */
    val note: String? = null,
) {

    /**
     * True when this card is a company rather than a person.
     *
     * 🔴 Derived from `ORG == FN`, not from a missing given name, and that is not a guess. The
     * migrated data runs `FN:Redoak Foodservice` / `N:Foodservice;Redoak;;;` / `ORG:Redoak
     * Foodservice;`: the exporter split a company name down the middle into a fake surname and a
     * fake first name. Trusting `N` there files Redoak Foodservice under F, next to nothing else it
     * belongs with. Eleven of the account's 113 cards do this, and every one of them is a company.
     */
    val isOrganization: Boolean
        get() {
            val org = organization?.takeIf { it.isNotBlank() } ?: return false
            val fn = formattedName?.takeIf { it.isNotBlank() } ?: return false
            return org.equals(fn, ignoreCase = true)
        }

    /**
     * The name this card files and sorts under, guaranteed non-blank.
     *
     * 🔴 The guarantee is the whole point. The UI takes `family.first()` to pick an A-Z section, so
     * a blank surname is not a cosmetic problem, it is a crash on the first card that has one. The
     * account has both cases: five cards with an empty `N` surname, and an entire address book
     * ("Trusted Senders", which Stalwart keeps as real CardDAV) whose eight cards carry nothing but
     * an EMAIL and a literally empty `FN;DERIVED=TRUE:`.
     *
     * So the chain runs company name → surname → full name → e-mail local part → `?`, and every rung
     * of it is reached by real data on this one account.
     */
    val fileAsFamily: String
        get() {
            if (isOrganization) return organization!!.trim()
            family.trim().takeIf { it.isNotEmpty() }?.let { return it }
            organization?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            formattedName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            emails.firstOrNull()?.substringBefore('@')?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            return "?"
        }

    /**
     * The given name to render beside [fileAsFamily], or empty when this card files as a whole name.
     *
     * Empty is meaningful downstream: the address book renders a card with no given name as one
     * heavy line, because the whole line is the sort key.
     */
    val fileAsGiven: String
        get() = if (isOrganization || family.isBlank()) "" else given.trim()

    /**
     * The one-line description under the name: a job title, else the company, else nothing.
     *
     * ⚠️ Often nothing. 80 of the account's 113 cards have no TITLE and 62 have no ORG, so an empty
     * subtitle is the ordinary case here and not a sign the card failed to load.
     */
    val role: String
        get() {
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            if (!isOrganization) {
                organization?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }

    /** The address to write to, or empty. 79 of 113 real cards have none. */
    val primaryEmail: String get() = emails.firstOrNull().orEmpty()
}

/**
 * A small, dependency-free vCard reader (RFC 6350 and the 3.0 that preceded it).
 *
 * It reads what the address book shows and ignores everything else, which is most of the card. The
 * live data is a mix: 110 cards at VERSION:3.0 written by `gromox-oxvcard` through Sabre, and 8 at
 * VERSION:4.0 written by Stalwart itself. Nothing here branches on the version, because none of the
 * properties it reads changed between them.
 *
 * Like the calendar reader it is deliberately total: malformed input yields a card with missing
 * fields or no card at all, never an exception.
 */
object VCard {

    /**
     * Largest card payload we will read (1 MiB). A vCard is a few hundred bytes plus an optional
     * photo; a megabyte of it is either broken or hostile, and reading it buys nothing.
     */
    const val MAX_SOURCE_CHARS = 1024 * 1024

    /** Parse the first card in [raw], or null if there is nothing usable. */
    fun parse(raw: String?): ParsedContact? = parseAll(raw).firstOrNull()

    /**
     * Parse every card in [raw].
     *
     * A CardDAV resource holds one card, so in practice this returns zero or one. It reads a stream
     * anyway because nothing in the format forbids more and a reader that quietly drops the second
     * one is worse than a reader that handles it.
     */
    fun parseAll(raw: String?): List<ParsedContact> {
        if (raw.isNullOrBlank()) return emptyList()
        if (raw.length > MAX_SOURCE_CHARS) return emptyList()
        return try {
            val out = ArrayList<ParsedContact>()
            var current: MutableList<ContentLine>? = null
            for (line in ContentLines.parseAll(raw)) {
                when {
                    line.name == "BEGIN" && line.value.trim().equals("VCARD", true) ->
                        current = ArrayList()
                    line.name == "END" && line.value.trim().equals("VCARD", true) -> {
                        current?.let { build(it)?.let(out::add) }
                        current = null
                    }
                    else -> current?.add(line)
                }
            }
            // An unterminated card still has everything we read; dropping it would lose a whole
            // contact over a missing END line.
            current?.let { build(it)?.let(out::add) }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun build(lines: List<ContentLine>): ParsedContact? {
        fun first(name: String) = lines.firstOrNull { it.name == name }

        val n = first("N")?.let { ContentLines.splitStructured(it.value) }.orEmpty()
        val fn = first("FN")?.let { text(it.value) }

        val card = ParsedContact(
            uid = first("UID")?.value?.trim()?.takeIf { it.isNotEmpty() },
            formattedName = fn,
            family = n.getOrNull(0)?.let(ContentLines::unescapeText)?.trim().orEmpty(),
            given = n.getOrNull(1)?.let(ContentLines::unescapeText)?.trim().orEmpty(),
            // ORG is structured (`ORG:Redoak Foodservice;` is a name plus an empty unit), so the
            // company is the first component and the trailing semicolon is not part of it.
            organization = first("ORG")
                ?.let { ContentLines.splitStructured(it.value).firstOrNull() }
                ?.let(ContentLines::unescapeText)?.trim()?.takeIf { it.isNotEmpty() },
            title = first("TITLE")?.let { text(it.value) },
            emails = emails(lines),
            phones = phones(lines),
            note = first("NOTE")?.let { text(it.value) },
        )

        // A card with nothing to file under and nothing to show is not a contact, it is noise.
        val hasAnything = card.formattedName != null || card.family.isNotEmpty() ||
            card.given.isNotEmpty() || card.organization != null || card.emails.isNotEmpty()
        return card.takeIf { hasAnything }
    }

    /**
     * Every EMAIL on the card, preferred address first.
     *
     * `TYPE=PREF` (3.0) and `PREF=1` (4.0) both mean the same thing and both appear in the wild, so
     * both promote. Order is otherwise as written, because the exporter's order is the only
     * preference signal a card without PREF carries.
     */
    private fun emails(lines: List<ContentLine>): List<String> {
        val all = lines.filter { it.name == "EMAIL" }
        val addresses = all.map { line ->
            val preferred = line.paramValues("TYPE").any { it.equals("PREF", true) } ||
                line.param("PREF") != null
            preferred to line.value.trim().removePrefix("mailto:").trim()
        }
        return addresses
            .filter { it.second.isNotEmpty() }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()
    }

    /**
     * Every TEL on the card, preferred number first — the same promotion rule as [emails],
     * because the same two PREF spellings appear on the same account's cards. A `tel:` URI
     * prefix (4.0's `TEL;VALUE=uri`) is stripped; the number itself is kept as written,
     * because "+1 859-803-2727" formatted by a human reads better than any renormalisation.
     */
    private fun phones(lines: List<ContentLine>): List<String> {
        val all = lines.filter { it.name == "TEL" }
        val numbers = all.map { line ->
            val preferred = line.paramValues("TYPE").any { it.equals("PREF", true) } ||
                line.param("PREF") != null
            preferred to line.value.trim().removePrefix("tel:").trim()
        }
        return numbers
            .filter { it.second.isNotEmpty() }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()
    }

    private fun text(value: String): String? =
        ContentLines.unescapeText(value).trim().takeIf { it.isNotEmpty() }
}
