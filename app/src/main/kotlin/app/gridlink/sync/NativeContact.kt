package app.gridlink.sync

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.jmap.model.ContactCardGroup
import app.gridlink.core.jmap.model.ContactCardPhoto
import java.security.MessageDigest

/**
 * One raw contact as Android's contacts provider holds it: the fields the system Contacts app can
 * edit and Gridlink can send back.
 *
 * Read from the provider, never built from the server's card, because the provider rewrites what
 * it is given (it splits a lone display name into a given and a family name, for one). The base
 * hashes this is compared against are taken from the provider the same way, after each publish,
 * so an untouched contact always compares equal to its base however the provider spelled it.
 *
 * @param photoHash a hash of the stored photo, or null for none. The bytes themselves are read
 *   only when an upload needs them.
 * @param addresses read so a change to one can be recognised and reported; there is no way to send
 *   it (Gridlink's contact model has no address field to write).
 * @param emailKinds each address's provider type (`Email.TYPE_WORK` and so on); a missing entry
 *   reads as `TYPE_OTHER`, which is what the provider means by no type.
 * @param phoneKinds each number's provider type (`Phone.TYPE_MOBILE` and so on), likewise.
 */
data class NativeContact(
    val given: String = "",
    val family: String = "",
    val displayName: String = "",
    val company: String = "",
    val title: String = "",
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val note: String = "",
    val addresses: List<String> = emptyList(),
    val photoHash: String? = null,
    val emailKinds: Map<String, Int> = emptyMap(),
    val phoneKinds: Map<String, Int> = emptyMap(),
) {

    /** Every address with its kind words, stated for all of them: the provider always has a type. */
    private val emailTypes: Map<String, Set<String>>
        get() = emails.associateWith { NativeContactTypes.emailWords(emailKind(it)) }

    /** Every number with its kind words, as [emailTypes]. */
    private val phoneTypes: Map<String, Set<String>>
        get() = phones.associateWith { NativeContactTypes.phoneWords(phoneKind(it)) }

    /** The provider type of one address, `TYPE_OTHER` when none was read. */
    fun emailKind(address: String): Int = emailKinds[address] ?: Email.TYPE_OTHER

    /** The provider type of one number, `TYPE_OTHER` when none was read. */
    fun phoneKind(number: String): Int = phoneKinds[number] ?: Phone.TYPE_OTHER

    /**
     * True when this is an organization card: a company and no person name besides it. The
     * provider fills a given and a family name in from the display name on its own, so "no person
     * name" also covers a name that only spells the company again (Gridlink's `ORG == FN` rule).
     */
    val isOrganization: Boolean
        get() {
            val org = company.trim().takeIf { it.isNotEmpty() } ?: return false
            val person = listOf(given.trim(), family.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            return person.isEmpty() || person == org || displayName.trim() == org
        }

    /** The contact form's view of this contact. The photo is the caller's to add. */
    fun toEdit(): ContactEdit = if (isOrganization) {
        ContactEdit(
            family = company.trim(),
            title = title,
            emails = emails,
            phones = phones,
            note = note,
            emailTypes = emailTypes,
            phoneTypes = phoneTypes,
        )
    } else if (given.isBlank() && family.isBlank()) {
        // Only a display name: Gridlink spells that as a family name alone, as it reads one back.
        ContactEdit(
            family = displayName,
            company = company,
            title = title,
            emails = emails,
            phones = phones,
            note = note,
            emailTypes = emailTypes,
            phoneTypes = phoneTypes,
        )
    } else {
        ContactEdit(
            given = given,
            family = family,
            company = company,
            title = title,
            emails = emails,
            phones = phones,
            note = note,
            emailTypes = emailTypes,
            phoneTypes = phoneTypes,
        )
    }
}

/**
 * The per-group hashes the contacts uploader keeps in each raw contact, one set for each side.
 *
 * Two sets because the two sides do not spell a contact the same way (see [NativeContact]): the
 * local set is taken from the provider and tells what the user changed there, the server set is
 * taken from the card and tells what changed on the server since. Each is only ever compared with
 * a later copy from its own side.
 *
 * NAME and ORGANIZATION are one unit on both sides. An organization card's name IS its company,
 * so a change to either can be a change to both, and keeping them apart would let a merge put a
 * stale half of the pair back.
 */
object NativeContactDiff {

    private const val NAME = "N"
    private const val TITLE = "T"
    private const val EMAILS = "E"
    private const val PHONES = "P"
    private const val NOTE = "O"
    private const val PHOTO = "F"
    private const val ADDRESSES = "A"
    private const val CUSTOM = "C"

    /** Which [ContactCardGroup]s each local group writes. Addresses write none: unsupported. */
    private val LOCAL_TO_CARD = mapOf(
        NAME to setOf(ContactCardGroup.NAME, ContactCardGroup.ORGANIZATION),
        TITLE to setOf(ContactCardGroup.TITLE),
        EMAILS to setOf(ContactCardGroup.EMAILS),
        PHONES to setOf(ContactCardGroup.PHONES),
        NOTE to setOf(ContactCardGroup.NOTE),
        PHOTO to setOf(ContactCardGroup.PHOTO),
        ADDRESSES to emptySet(),
    )

    private const val HASH_BYTES = 6

    fun localHashes(contact: NativeContact): Map<String, String> = mapOf(
        NAME to hash(contact.given.trim(), contact.family.trim(), contact.displayName.trim(), contact.company.trim()),
        TITLE to hash(contact.title.trim()),
        // The provider's type number, not the words it maps to: a type with no vCard word (Callback,
        // a custom label) is still a change the user made, and sending it puts the server's
        // truth back on screen instead of leaving the row showing something the server never got.
        EMAILS to hashAll(contact.emails.map { it.trim().lowercase() + "|" + contact.emailKind(it) }),
        PHONES to hashAll(contact.phones.map { digits(it) + "|" + contact.phoneKind(it) }),
        NOTE to hash(contact.note.trim()),
        PHOTO to hash(contact.photoHash.orEmpty()),
        ADDRESSES to hashAll(contact.addresses.map { it.trim() }),
    )

    fun serverHashes(edit: ContactEdit): Map<String, String> {
        val n = edit.normalized()
        return mapOf(
            NAME to hash(n.given, n.family, n.organizationValue.orEmpty()),
            TITLE to hash(n.title),
            EMAILS to hashAll(n.emails.map { it.lowercase() + "|" + words(n.emailTypes[it]) }),
            PHONES to hashAll(n.phones.map { digits(it) + "|" + words(n.phoneTypes[it]) }),
            NOTE to hash(n.note),
            PHOTO to hash(n.photo?.base64.orEmpty()),
            CUSTOM to hashList(n.customFields.map { "${it.label}=${it.value}" }),
        )
    }

    /** The groups whose hash differs from [base]; every group when there is no base. */
    fun changed(base: Map<String, String>?, now: Map<String, String>): Set<String> =
        now.keys.filterTo(LinkedHashSet()) { base?.get(it) != now[it] }

    /** True when [group] has no field Gridlink can write (a change there cannot be sent). */
    fun unsendable(group: String): Boolean = LOCAL_TO_CARD[group].isNullOrEmpty()

    /**
     * [server] with the local groups in [touched] taken from [local]. [photo] is the local photo,
     * already encoded, used only when the photo is one of them.
     */
    fun merge(
        server: ContactEdit,
        local: ContactEdit,
        touched: Set<String>,
        photo: () -> ContactCardPhoto?,
    ): ContactEdit {
        var out = server
        if (NAME in touched) out = out.copy(given = local.given, family = local.family, company = local.company)
        if (TITLE in touched) out = out.copy(title = local.title)
        if (EMAILS in touched) {
            out = out.copy(
                emails = local.emails,
                emailTypes = NativeContactTypes.keepServerWords(
                    TypedValues(local.emails, local.emailTypes),
                    TypedValues(server.emails, server.emailTypes),
                    key = { it.trim().lowercase() },
                    kind = NativeContactTypes::emailKind,
                ),
            )
        }
        if (PHONES in touched) {
            out = out.copy(
                phones = local.phones,
                phoneTypes = NativeContactTypes.keepServerWords(
                    TypedValues(local.phones, local.phoneTypes),
                    TypedValues(server.phones, server.phoneTypes),
                    key = ::digits,
                    kind = NativeContactTypes::phoneKind,
                ),
            )
        }
        if (NOTE in touched) out = out.copy(note = local.note)
        if (PHOTO in touched) out = out.copy(photo = photo())
        return out
    }

    fun encode(hashes: Map<String, String>): String =
        hashes.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun decode(text: String?): Map<String, String>? {
        if (text.isNullOrBlank()) return null
        return text.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
        }.toMap().takeIf { it.isNotEmpty() }
    }

    /** A hash of raw photo bytes, for [NativeContact.photoHash]. */
    fun photoHash(bytes: ByteArray?): String? = bytes?.takeIf { it.isNotEmpty() }?.let { hex(sha(it)) }

    /** Phone numbers compare by their digits, so the provider's own formatting is not an edit. */
    private fun digits(number: String): String = number.filter { it.isDigit() || it == '+' }

    /** Kind words in a stable spelling for a hash. */
    private fun words(types: Set<String>?): String = types.orEmpty().sorted().joinToString(",")

    /** Order and blanks ignored: the provider keeps its own row order. */
    private fun hashAll(values: List<String>): String =
        hashList(values.filter { it.isNotEmpty() }.sorted())

    private fun hash(vararg parts: String): String = hashList(parts.asList())

    private fun hashList(parts: List<String>): String =
        hex(sha(parts.joinToString("\u0001").toByteArray(Charsets.UTF_8)))

    private fun sha(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(HASH_BYTES)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** A list of addresses or numbers with each one's kind words, as [ContactEdit] carries them. */
data class TypedValues(val values: List<String>, val types: Map<String, Set<String>>)

/**
 * The translation between vCard kind words ([app.gridlink.core.jmap.model.ContactTypes]) and the
 * contacts provider's type numbers, in both directions.
 *
 * The provider has one number per row where a card has a set of words, so the mapping is lossy
 * one way: `TEL;TYPE=work,voice` shows as Work and would come back as just `work`. That loss never
 * reaches the server unprompted, because [keepServerWords] puts the card's own words back on any
 * value whose type the user did not change.
 *
 * A provider type with no vCard word (Callback, Radio, a custom label) maps to no words, which is
 * how the server then holds it.
 */
object NativeContactTypes {

    private const val HOME = "home"
    private const val WORK = "work"
    private const val CELL = "cell"
    private const val FAX = "fax"
    private const val PAGER = "pager"
    private const val MAIN = "main"
    private const val TEXTPHONE = "textphone"
    private const val CAR = "car"
    private const val ISDN = "isdn"

    /** Kinds that pair with work (first) or not (second), checked in this order. */
    private val PAIRED = listOf(
        CELL to (Phone.TYPE_WORK_MOBILE to Phone.TYPE_MOBILE),
        PAGER to (Phone.TYPE_WORK_PAGER to Phone.TYPE_PAGER),
        MAIN to (Phone.TYPE_COMPANY_MAIN to Phone.TYPE_MAIN),
    )

    /** Kinds that stand alone, checked after [PAIRED] and before home or work. */
    private val SINGLE = listOf(
        TEXTPHONE to Phone.TYPE_TTY_TDD,
        CAR to Phone.TYPE_CAR,
        ISDN to Phone.TYPE_ISDN,
    )

    /** The provider's type for a card's phone words. */
    fun phoneKind(words: Set<String>): Int {
        val work = WORK in words
        if (FAX in words) {
            return when {
                work -> Phone.TYPE_FAX_WORK
                HOME in words -> Phone.TYPE_FAX_HOME
                else -> Phone.TYPE_OTHER_FAX
            }
        }
        PAIRED.firstOrNull { it.first in words }?.let { (_, kinds) -> return if (work) kinds.first else kinds.second }
        SINGLE.firstOrNull { it.first in words }?.let { return it.second }
        return when {
            HOME in words -> Phone.TYPE_HOME
            work -> Phone.TYPE_WORK
            else -> Phone.TYPE_OTHER
        }
    }

    /** The card words for a provider phone type. */
    fun phoneWords(kind: Int): Set<String> = PHONE_WORDS[kind].orEmpty()

    /** The provider's type for a card's email words. */
    fun emailKind(words: Set<String>): Int = when {
        WORK in words -> Email.TYPE_WORK
        HOME in words -> Email.TYPE_HOME
        else -> Email.TYPE_OTHER
    }

    /** The card words for a provider email type. */
    fun emailWords(kind: Int): Set<String> = when (kind) {
        Email.TYPE_HOME -> setOf(HOME)
        Email.TYPE_WORK -> setOf(WORK)
        else -> emptySet()
    }

    /**
     * The words to send for [local]'s values: the server card's own words for a value it already
     * holds when the provider shows the same type for both, else the provider's. Values match by
     * [key] (an email ignoring case, a number by its digits).
     *
     * This is what stops a one-way loss becoming a server change: a `TYPE=work,voice` number shows
     * as Work, and when the user adds a second number the first goes back up as `work,voice`, not
     * as the `work` the provider can spell.
     */
    fun keepServerWords(
        local: TypedValues,
        server: TypedValues,
        key: (String) -> String,
        kind: (Set<String>) -> Int,
    ): Map<String, Set<String>> {
        val serverByKey = HashMap<String, Set<String>>()
        server.values.forEach { value -> serverByKey.putIfAbsent(key(value), server.types[value].orEmpty()) }
        return local.values.associateWith { value ->
            val mine = local.types[value].orEmpty()
            val theirs = serverByKey[key(value)]
            if (theirs != null && kind(theirs) == kind(mine)) theirs else mine
        }
    }

    private val PHONE_WORDS = mapOf(
        Phone.TYPE_HOME to setOf(HOME),
        Phone.TYPE_MOBILE to setOf(CELL),
        Phone.TYPE_WORK to setOf(WORK),
        Phone.TYPE_FAX_WORK to setOf(WORK, FAX),
        Phone.TYPE_FAX_HOME to setOf(HOME, FAX),
        Phone.TYPE_OTHER_FAX to setOf(FAX),
        Phone.TYPE_PAGER to setOf(PAGER),
        Phone.TYPE_WORK_PAGER to setOf(WORK, PAGER),
        Phone.TYPE_WORK_MOBILE to setOf(WORK, CELL),
        Phone.TYPE_MAIN to setOf(MAIN),
        Phone.TYPE_COMPANY_MAIN to setOf(WORK, MAIN),
        Phone.TYPE_TTY_TDD to setOf(TEXTPHONE),
        Phone.TYPE_CAR to setOf(CAR),
        Phone.TYPE_ISDN to setOf(ISDN),
    )
}
