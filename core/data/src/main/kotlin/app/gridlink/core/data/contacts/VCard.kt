package app.gridlink.core.data.contacts

import app.gridlink.core.data.text.ContentLine
import app.gridlink.core.data.text.ContentLines
import app.gridlink.core.jmap.model.ContactCardCustomField
import app.gridlink.core.jmap.model.ContactCardPhoto

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
    /** The embedded PHOTO, when the card carries one inline. A remote-URI photo parses as null:
     *  nothing to show offline and nothing this app would re-upload. */
    val photo: ContactCardPhoto? = null,
    /** Gridlink's own X-GRIDLINK-FIELD properties: user-defined label/value pairs. */
    val customFields: List<ContactCardCustomField> = emptyList(),
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
     * Largest card payload we will read (4 MiB). A vCard is a few hundred bytes plus an optional
     * photo, and the photo is why this is not smaller: an iPhone-written card can carry close to
     * a megabyte of base64, and a cap that drops the CARD over a large photo loses a contact,
     * not a picture. Beyond this it is broken or hostile, and reading it buys nothing.
     */
    const val MAX_SOURCE_CHARS = 4 * 1024 * 1024

    /** Largest inline photo we will keep (base64 length ≈ 1.5 MB of image). Bigger parses as
     *  no photo — which the diff upstream treats as "leave the PHOTO lines alone", not "delete". */
    const val MAX_PHOTO_BASE64_CHARS = 2 * 1024 * 1024

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
            photo = photo(lines),
            customFields = lines.filter { it.name == "X-GRIDLINK-FIELD" }.mapNotNull { line ->
                val parts = ContentLines.splitStructured(line.value)
                val label = parts.getOrNull(0)?.let(ContentLines::unescapeText)?.trim().orEmpty()
                val value = parts.getOrNull(1)?.let(ContentLines::unescapeText)?.trim().orEmpty()
                ContactCardCustomField(label, value)
                    .takeIf { label.isNotEmpty() || value.isNotEmpty() }
            },
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

    /**
     * The card's inline PHOTO, in either of the spellings the live data mixes:
     * `PHOTO:data:image/jpeg;base64,…` (4.0) or `PHOTO;ENCODING=b;TYPE=JPEG:…` (3.0, where
     * `ENCODING=BASE64` is the 2.1 spelling of the same thing and also appears).
     */
    private fun photo(lines: List<ContentLine>): ContactCardPhoto? {
        val line = lines.firstOrNull { it.name == "PHOTO" } ?: return null
        val value = line.value.trim()
        if (value.startsWith("data:", ignoreCase = true)) {
            val comma = value.indexOf(',')
            if (comma < 0) return null
            val header = value.substring(5, comma)
            if (!header.contains("base64", ignoreCase = true)) return null
            val mediaType = header.substringBefore(';').trim().lowercase()
                .takeIf { it.isNotEmpty() } ?: "image/jpeg"
            return photoOf(mediaType, value.substring(comma + 1))
        }
        val encoding = line.param("ENCODING") ?: return null
        if (!encoding.equals("b", true) && !encoding.equals("base64", true)) return null
        // TYPE is a bare subtype in 3.0 (`TYPE=JPEG`); tolerate a full media type too.
        val mediaType = line.param("TYPE")?.lowercase()
            ?.let { if ('/' in it) it else "image/$it" } ?: "image/jpeg"
        return photoOf(mediaType, value)
    }

    private fun photoOf(mediaType: String, base64: String): ContactCardPhoto? {
        val cleaned = base64.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty() || cleaned.length > MAX_PHOTO_BASE64_CHARS) return null
        return try {
            // Decode once to validate: a corrupt PHOTO parses as no photo (which the diff treats
            // as "leave it alone"), rather than riding along until an image decoder or a server
            // rejects it somewhere the user can't see the cause.
            java.util.Base64.getDecoder().decode(cleaned)
            ContactCardPhoto(mediaType, cleaned)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun text(value: String): String? =
        ContentLines.unescapeText(value).trim().takeIf { it.isNotEmpty() }
}
