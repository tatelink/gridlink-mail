package app.gridlink.ui.gridlink

import android.content.Context
import android.net.Uri
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.data.contacts.ParsedContact
import app.gridlink.core.data.contacts.VCard
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A file this big is not an address book. Refused before it can take the app's memory with it. */
private const val MAX_IMPORT_BYTES = 64 * 1024 * 1024

/** A server that refused this many in a row will refuse the rest: an account with no address book. */
internal const val CONTACT_IMPORT_MAX_FAILURES_IN_A_ROW = 3

private const val QP_ESCAPE_LENGTH = 3
private const val QP_RADIX = 16

/** What a .vcf file holds once it has been read against the address book it is going into. */
internal data class ContactImportPlan(
    val edits: List<ContactEdit>,
    val duplicates: Int,
    val unreadable: Int,
)

@Throws(IOException::class)
internal fun readContactFile(context: Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The file could not be opened.")
    return input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_IMPORT_BYTES) throw IOException("That file is too large to import.")
            out.write(buffer, 0, n)
        }
        out.toString(Charsets.UTF_8.name())
    }
}

/**
 * One card at a time rather than [VCard.parseAll] over the file: its size cap is per source, and an
 * address book with photos passes that cap long before it runs out of contacts.
 *
 * Duplicates go by address, or by name for a card with no address, so importing the same file twice
 * adds nothing.
 */
internal fun planContactImport(
    raw: String,
    existingEmails: Set<String>,
    existingNames: Set<String>,
): ContactImportPlan {
    val emails = existingEmails.mapTo(HashSet()) { it.lowercase() }
    val names = existingNames.mapTo(HashSet()) { it.lowercase() }
    val edits = ArrayList<ContactEdit>()
    var duplicates = 0
    var unreadable = 0
    for (card in vCardsIn(raw)) {
        val edit = VCard.parse(decodeQuotedPrintableLines(card))?.toEdit()
        val cardEmails = edit?.emails.orEmpty().map { it.lowercase() }
        val name = edit?.let { "${it.given} ${it.family}".trim().lowercase() }.orEmpty()
        val seen = if (cardEmails.isNotEmpty()) cardEmails.any { it in emails } else name.isNotEmpty() && name in names
        when {
            edit == null || edit.isEmptyCard() -> unreadable++
            seen -> duplicates++
            else -> {
                emails += cardEmails
                if (name.isNotEmpty()) names += name
                edits += edit
            }
        }
    }
    return ContactImportPlan(edits, duplicates, unreadable)
}

internal fun contactImportSummary(imported: Int, plan: ContactImportPlan, failed: Int, firstError: String?): String =
    buildList {
        add("Imported $imported.")
        if (plan.duplicates > 0) add("${plan.duplicates} already in contacts.")
        if (plan.unreadable > 0) add("${plan.unreadable} unreadable.")
        if (failed > 0) add("$failed not saved: ${firstError ?: "the server refused them."}")
    }.joinToString(" ")

private fun vCardsIn(raw: String): List<String> {
    val cards = ArrayList<String>()
    var from = 0
    while (true) {
        val begin = raw.indexOf("BEGIN:VCARD", from, ignoreCase = true)
        if (begin < 0) return cards
        val end = raw.indexOf("END:VCARD", begin, ignoreCase = true)
        if (end < 0) return cards
        from = end + "END:VCARD".length
        cards += raw.substring(begin, from)
    }
}

/**
 * Phones export vCard 2.1, which writes any non-ASCII name as `N;ENCODING=QUOTED-PRINTABLE:=C3=BC`
 * and folds long values with a trailing `=`. This is the one way a device's own export reaches this
 * app, so those lines are turned into plain UTF-8 here rather than arriving as `=C3=BC` in a name.
 */
internal fun decodeQuotedPrintableLines(card: String): String {
    val lines = card.split("\r\n", "\n")
    val out = ArrayList<String>(lines.size)
    var i = 0
    while (i < lines.size) {
        val line = lines[i++]
        val colon = line.indexOf(':')
        val head = if (colon > 0) line.substring(0, colon) else ""
        if (!head.contains("QUOTED-PRINTABLE", ignoreCase = true)) {
            out += line
            continue
        }
        var value = line.substring(colon + 1)
        while (value.endsWith("=") && i < lines.size) value = value.dropLast(1) + lines[i++]
        val params = head.split(';').filterNot {
            it.contains("QUOTED-PRINTABLE", ignoreCase = true) || it.startsWith("CHARSET=", ignoreCase = true)
        }
        out += params.joinToString(";") + ":" + quotedPrintable(value)
    }
    return out.joinToString("\r\n")
}

private fun quotedPrintable(value: String): String {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        val hex = if (value[i] == '=') {
            value.substring(i + 1, minOf(i + QP_ESCAPE_LENGTH, value.length)).toIntOrNull(QP_RADIX)
        } else {
            null
        }
        if (hex != null) {
            bytes.write(hex)
            i += QP_ESCAPE_LENGTH
        } else {
            bytes.write(value[i].toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private fun ParsedContact.toEdit(): ContactEdit {
    val nameless = given.isBlank() && family.isBlank()
    return ContactEdit(
        given = if (nameless) formattedName.orEmpty() else given,
        family = family,
        company = organization.orEmpty(),
        title = title.orEmpty(),
        emails = emails,
        phones = phones,
        note = note.orEmpty(),
        photo = photo,
    ).normalized()
}

private fun ContactEdit.isEmptyCard(): Boolean =
    given.isEmpty() && family.isEmpty() && company.isEmpty() && emails.isEmpty() && phones.isEmpty()
