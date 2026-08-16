package app.gridlink.sync

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import app.gridlink.core.data.contacts.ContactPayload
import app.gridlink.core.data.contacts.ParsedContact
import app.gridlink.core.data.db.AddressBookContactEntity

/**
 * Publishes one mail account's cached CardDAV cards into Android's contacts provider, so they
 * reach caller ID, the dialler, and every app's people picker.
 *
 * ## 🔴 One direction, and the provider is told so
 * Each raw contact is written with `RAW_CONTACT_IS_READ_ONLY`, which is how the system Contacts app
 * knows not to offer an editor over these rows. Without it the user gets an edit screen that
 * appears to work and whose changes this mirror overwrites on its next pass. Editing lives in
 * Gridlink, where it reaches the server.
 *
 * ## Where the fields come from
 * The parsed columns on [AddressBookContactEntity] are the address book's *list* view: a name, a
 * sort key, one email. The mirror needs the whole card, so it re-parses [AddressBookContactEntity.raw]
 * through [ContactPayload], which picks the reader the row's own format calls for (a `.vcf` from
 * CardDAV, a JSContact Card from JMAP). That is the same entry point the contact detail screen
 * uses, so what lands in the system is what Gridlink itself shows, field for field.
 *
 * ## ⚠️ Phone numbers arrive untyped
 * [ParsedContact.phones] keeps the numbers and drops the vCard's `TYPE=` parameters, so every
 * number is written as `TYPE_OTHER`. Caller ID and dialling are unaffected; what the user loses is
 * the word "mobile" or "work" beside the number in the system Contacts app. Fixing it means
 * teaching the vCard parser to keep the types, which is a change to shared code the address book
 * has no use for.
 */
class ContactsMirror(private val resolver: ContentResolver) {

    /** Reconcile the provider to [contacts]. Returns the number of cards written. */
    fun publish(account: Account, accountId: String, contacts: List<AddressBookContactEntity>): Int {
        ensureVisible(account)
        val prefix = SystemMirror.prefix(accountId)
        val existing = existingRawContacts(account, prefix)

        val ops = ArrayList<ContentProviderOperation>()
        var written = 0
        val seen = HashSet<String>()
        for (contact in contacts) {
            val sourceId = SystemMirror.sourceId(accountId, contact.href)
            seen += sourceId
            val fingerprint = SystemMirror.fingerprint(contact.etag, contact.raw)
            val row = existing[sourceId]
            if (row != null && row.fingerprint == fingerprint) continue
            // Changed cards are removed and rewritten rather than diffed field by field. A vCard
            // has no stable identity for "the second email address", so a diff would come down to
            // matching values against each other, which is what a rewrite does anyway.
            if (row != null) ops += ContentProviderOperation.newDelete(rawContactUri(account, row.id)).build()
            // A card that will not parse leaves nothing behind: the delete above still stands, so
            // the provider is not left showing the previous version of something the server changed.
            ContactPayload.parse(contact)?.let { parsed ->
                appendInsert(ops, account, sourceId, fingerprint, parsed, contact)
                written++
            }
        }

        for ((sourceId, row) in existing) {
            if (sourceId !in seen) {
                ops += ContentProviderOperation.newDelete(rawContactUri(account, row.id)).build()
            }
        }

        apply(ops)
        return written
    }

    private fun appendInsert(
        ops: ArrayList<ContentProviderOperation>,
        account: Account,
        sourceId: String,
        fingerprint: String,
        parsed: ParsedContact,
        contact: AddressBookContactEntity,
    ) {
        val anchor = ops.size
        ops += ContentProviderOperation.newInsert(syncUri(ContactsContract.RawContacts.CONTENT_URI, account))
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(ContactsContract.RawContacts.SOURCE_ID, sourceId)
            .withValue(ContactsContract.RawContacts.SYNC1, fingerprint)
            // 🔴 The read-only declaration. See the class note.
            .withValue(ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY, 1)
            .build()

        fun data(mimeType: String, build: ContentProviderOperation.Builder.() -> Unit) {
            ops += ContentProviderOperation.newInsert(syncUri(ContactsContract.Data.CONTENT_URI, account))
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, anchor)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
                .apply(build)
                .build()
        }

        data(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
            withValue(
                ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                contact.displayName.ifBlank { parsed.fileAsFamily },
            )
            // An organization card carries no person name: writing the company into GIVEN_NAME is
            // the exporter bug ParsedContact.isOrganization exists to undo, and it would file
            // "Redoak Foodservice" under F here too.
            if (!parsed.isOrganization) {
                parsed.given.takeIf { it.isNotBlank() }
                    ?.let { withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, it) }
                parsed.family.takeIf { it.isNotBlank() }
                    ?.let { withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, it) }
            }
        }

        val company = parsed.organization?.takeIf { it.isNotBlank() }
        val title = parsed.title?.takeIf { it.isNotBlank() }
        if (company != null || title != null) {
            data(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE) {
                withValue(
                    ContactsContract.CommonDataKinds.Organization.TYPE,
                    ContactsContract.CommonDataKinds.Organization.TYPE_WORK,
                )
                company?.let { withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, it) }
                title?.let { withValue(ContactsContract.CommonDataKinds.Organization.TITLE, it) }
            }
        }

        parsed.emails.forEach { address ->
            data(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) {
                withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, address)
                withValue(
                    ContactsContract.CommonDataKinds.Email.TYPE,
                    ContactsContract.CommonDataKinds.Email.TYPE_OTHER,
                )
            }
        }

        // Untyped on purpose; see the class note.
        parsed.phones.forEach { number ->
            data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
                withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_OTHER,
                )
            }
        }

        parsed.addresses.forEach { line ->
            data(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE) {
                withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, line)
                withValue(
                    ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
                    ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER,
                )
            }
        }

        parsed.note?.takeIf { it.isNotBlank() }?.let { note ->
            data(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE) {
                withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
            }
        }

        photoBytes(parsed)?.let { bytes ->
            data(ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE) {
                withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
            }
        }
    }

    /**
     * Decoded card photo, or null when there is none or it is too big to hand over.
     *
     * The provider takes the bytes across a Binder transaction inside the same batch as everything
     * else, so an oversized portrait does not fail its own row, it fails the whole batch and takes
     * several hundred healthy contacts with it. Dropping the picture is the smaller loss.
     */
    private fun photoBytes(parsed: ParsedContact): ByteArray? {
        val encoded = parsed.photo?.base64?.takeIf { it.isNotBlank() } ?: return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size > MAX_PHOTO_BYTES) {
            Log.d(TAG, "skipping ${bytes.size}-byte contact photo")
            return null
        }
        return bytes
    }

    /**
     * Make the account's contacts visible without belonging to a group.
     *
     * 🔴 Not optional. A contact in no group is hidden by default in the system Contacts app, so
     * without this row the mirror appears to do nothing at all: the data is there, the account is
     * there, and the user sees an empty list.
     */
    private fun ensureVisible(account: Account) {
        val values = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
            put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
        }
        runCatching { resolver.insert(ContactsContract.Settings.CONTENT_URI, values) }
            .onFailure { Log.d(TAG, "could not set ungrouped visibility", it) }
    }

    private data class RawRow(val id: Long, val fingerprint: String)

    private fun existingRawContacts(account: Account, prefix: String): Map<String, RawRow> {
        val out = HashMap<String, RawRow>()
        val projection = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.SOURCE_ID,
            ContactsContract.RawContacts.SYNC1,
        )
        resolver.query(
            syncUri(ContactsContract.RawContacts.CONTENT_URI, account),
            projection,
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                "${ContactsContract.RawContacts.SOURCE_ID} LIKE ?",
            arrayOf(account.name, account.type, "$prefix%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(1) ?: continue
                out[sourceId] = RawRow(cursor.getLong(0), cursor.getString(2).orEmpty())
            }
        }
        return out
    }

    /**
     * Run the operations in chunks.
     *
     * 🔴 One `applyBatch` for a whole address book throws `TransactionTooLargeException`: a few
     * hundred cards is several thousand operations and every one of them crosses a Binder. Chunking
     * also means a card the provider rejects costs its chunk and not the sync, which is why the
     * failure is logged and the loop continues.
     *
     * ⚠️ The chunk boundary must not split a card, or the data rows land with a back reference to
     * an insert that is not in their batch. Hence the walk below, which closes a chunk only on a
     * raw-contact boundary.
     */
    private fun apply(ops: List<ContentProviderOperation>) {
        if (ops.isEmpty()) return
        var chunk = ArrayList<ContentProviderOperation>()
        for (op in ops) {
            val startsCard = op.uri.path?.contains(RAW_CONTACTS_PATH) == true
            if (startsCard && chunk.size >= CHUNK) {
                flush(chunk)
                chunk = ArrayList()
            }
            chunk += op
        }
        flush(chunk)
    }

    private fun flush(chunk: ArrayList<ContentProviderOperation>) {
        if (chunk.isEmpty()) return
        runCatching { resolver.applyBatch(ContactsContract.AUTHORITY, chunk) }
            .onFailure { Log.w(TAG, "contact batch of ${chunk.size} failed", it) }
    }

    private fun rawContactUri(account: Account, id: Long): Uri =
        syncUri(ContactsContract.RawContacts.CONTENT_URI, account)
            .buildUpon()
            .appendPath(id.toString())
            .build()

    /** See [CalendarMirror] for why the sync-adapter flag is not optional. */
    private fun syncUri(uri: Uri, account: Account): Uri = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    private companion object {
        const val TAG = "GridlinkSync"
        const val CHUNK = 300
        const val RAW_CONTACTS_PATH = "raw_contacts"
        const val MAX_PHOTO_BYTES = 512 * 1024
    }
}
