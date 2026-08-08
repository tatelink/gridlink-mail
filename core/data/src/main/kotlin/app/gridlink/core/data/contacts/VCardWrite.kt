package app.gridlink.core.data.contacts

import app.gridlink.core.jmap.model.ContactCardGroup

/**
 * The writing half of [VCard]: build a fresh card, or patch an existing one group-wise.
 *
 * ## 🔴 Patch the TEXT, never regenerate from the model
 * [ParsedContact] reads seven properties off a card that may carry forty — PHOTO, ADR, BDAY,
 * X-props, Apple's `item1.` groups. Rebuilding a card from the parsed model and uploading that
 * would silently delete everything the model does not know, on the first casual edit, on every
 * card. So [patch] works on the raw text: it removes the logical lines of exactly the property
 * names the edit touched, appends replacements before `END:VCARD`, and passes every other byte
 * through. This is the CardDAV analogue of a JMAP patch, where the same guarantee comes free.
 *
 * ## What "logical line" means here
 * RFC 6350 folds long lines across physical lines with a leading space/tab. A removal takes the
 * continuation lines with it, or the tail of a folded NOTE would survive as garbage the next
 * parser chokes on. New lines are written unfolded: the spec's 75-octet fold is a SHOULD, every
 * server this app targets accepts long lines, and folding is one more place to corrupt UTF-8.
 *
 * Line terminators: output uses CRLF when the source does (or for fresh builds, always), LF when
 * a source is LF-only, detected once. A mixed-terminator source comes out uniform, which is the
 * one byte-level change [patch] permits itself.
 */
object VCardWrite {

    /** RFC 6350 §3.4 TEXT escaping: backslash first, then newline, comma, semicolon. */
    fun escapeText(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> {} // a lone CR inside a value is a terminator fragment, not content
                ',' -> append("\\,")
                ';' -> append("\\;")
                else -> append(c)
            }
        }
    }

    /** A fresh VERSION:4.0 card for [edit], CRLF-terminated, ready to PUT as `<uid>.vcf`. */
    fun build(edit: ContactEdit, uid: String): String {
        val n = edit.normalized()
        val lines = buildList {
            add("BEGIN:VCARD")
            add("VERSION:4.0")
            add("UID:" + escapeText(uid))
            ContactCardGroup.entries.forEach { addAll(groupLines(it, n, v4 = true)) }
            add("END:VCARD")
        }
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    /**
     * Rewrite [raw] so the [touched] groups read as [edit] says and nothing else changes.
     * An empty [touched] returns [raw] untouched — the no-op save stays a no-op.
     */
    fun patch(raw: String, edit: ContactEdit, touched: Set<ContactCardGroup>): String {
        if (touched.isEmpty()) return raw
        val n = edit.normalized()
        val terminator = if ("\r\n" in raw) "\r\n" else "\n"
        // The one write that cares about the card's version: a 3.0 card gets its photo in the
        // 3.0 spelling (ENCODING=b) so a same-version reader elsewhere still shows it. Sniffed,
        // not parsed: VERSION is a bare property and the patch must not depend on the parser.
        val v4 = !raw.contains("VERSION:3.0", ignoreCase = true) &&
            !raw.contains("VERSION:2.1", ignoreCase = true)
        val removeNames = touched.flatMapTo(HashSet()) { propertyNames(it) }
        val replacementLines = ContactCardGroup.entries
            .filter { it in touched }
            .flatMap { groupLines(it, n, v4) }

        // Physical lines → logical lines (a leading space/tab continues the previous one), so a
        // removed property takes its continuations along. Blank lines and anything unparseable
        // ride through verbatim as their own "logical line" with no name.
        val logicals = ArrayList<MutableList<String>>()
        for (line in raw.split("\r\n", "\r", "\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && logicals.isNotEmpty()) {
                logicals.last().add(line)
            } else {
                logicals.add(mutableListOf(line))
            }
        }

        val out = ArrayList<List<String>>(logicals.size + replacementLines.size)
        var inserted = false
        for (logical in logicals) {
            val name = propertyName(logical.first())
            if (name in removeNames) continue
            if (name == "END" && !inserted) {
                // Before the card's END, so a replacement lands inside the card it belongs to.
                replacementLines.forEach { out.add(listOf(it)) }
                inserted = true
            }
            out.add(logical)
        }
        // An unterminated card (no END line) still gets its new lines; see VCard.parseAll, which
        // reads such a card rather than dropping it.
        if (!inserted) replacementLines.forEach { out.add(listOf(it)) }

        return out.flatten().joinToString(terminator)
    }

    /** The vCard property names a group owns — what a patch of that group removes. */
    private fun propertyNames(group: ContactCardGroup): Set<String> = when (group) {
        ContactCardGroup.NAME -> setOf("FN", "N")
        ContactCardGroup.ORGANIZATION -> setOf("ORG")
        ContactCardGroup.TITLE -> setOf("TITLE")
        ContactCardGroup.EMAILS -> setOf("EMAIL")
        ContactCardGroup.PHONES -> setOf("TEL")
        ContactCardGroup.NOTE -> setOf("NOTE")
        ContactCardGroup.PHOTO -> setOf("PHOTO")
        ContactCardGroup.CUSTOM -> setOf("X-GRIDLINK-FIELD")
    }

    /**
     * The lines a group writes for an (already normalized) edit. An empty group writes nothing,
     * which after the removal pass means the property is gone — how a vCard spells "cleared".
     * [v4] only matters to PHOTO, the one property whose inline spelling changed between versions.
     */
    private fun groupLines(group: ContactCardGroup, edit: ContactEdit, v4: Boolean): List<String> = when (group) {
        ContactCardGroup.NAME -> buildList {
            // FN always, even empty: it is mandatory in both vCard versions, and the live account
            // already holds legitimate cards whose FN is literally blank.
            add("FN:" + escapeText(edit.fullName))
            // No N on an organization card: ORG == FN is the whole signal isOrganization reads,
            // and a fake surname is exactly the exporter bug that rule exists to survive.
            if (!edit.isOrganization && (edit.family.isNotEmpty() || edit.given.isNotEmpty())) {
                add("N:" + escapeText(edit.family) + ";" + escapeText(edit.given) + ";;;")
            }
        }
        ContactCardGroup.ORGANIZATION ->
            edit.organizationValue?.let { listOf("ORG:" + escapeText(it)) }.orEmpty()
        ContactCardGroup.TITLE ->
            edit.title.takeIf { it.isNotEmpty() }?.let { listOf("TITLE:" + escapeText(it)) }.orEmpty()
        ContactCardGroup.EMAILS -> edit.emails.map { "EMAIL:" + escapeText(it) }
        ContactCardGroup.PHONES -> edit.phones.map { "TEL:" + escapeText(it) }
        ContactCardGroup.NOTE ->
            edit.note.takeIf { it.isNotEmpty() }?.let { listOf("NOTE:" + escapeText(it)) }.orEmpty()
        // Unescaped on purpose in both spellings: RFC 6350 §4.2's own PHOTO example carries its
        // data URI's `;` and `,` raw, and escaping them corrupts the base64 for other readers.
        ContactCardGroup.PHOTO -> edit.photo?.let { photo ->
            if (v4) {
                listOf("PHOTO:" + photo.dataUri)
            } else {
                val subtype = photo.mediaType.substringAfter('/').uppercase()
                listOf("PHOTO;ENCODING=b;TYPE=$subtype:" + photo.base64)
            }
        }.orEmpty()
        ContactCardGroup.CUSTOM -> edit.customFields.map {
            "X-GRIDLINK-FIELD:" + escapeText(it.label) + ";" + escapeText(it.value)
        }
    }

    /** The property name of a logical line's first physical line, `item1.`-style groups stripped. */
    private fun propertyName(first: String): String {
        val cut = first.indexOfFirst { it == ':' || it == ';' }
        if (cut < 0) return ""
        return first.substring(0, cut).substringAfterLast('.').trim().uppercase()
    }
}
