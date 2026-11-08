package app.gridlink.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.data.contacts.ContactPayload
import app.gridlink.core.data.dav.DavRepository
import app.gridlink.core.data.dav.DavWriteOutcome
import app.gridlink.core.jmap.model.ContactCardPhoto

/**
 * Reads raw contacts back out of Android's contacts provider in the shape the uploader compares,
 * for one Android account. Shared by the uploader and by [ContactsMirror], which takes the local
 * base hashes with it after each publish so both sides read a contact the same way.
 */
class ContactsProviderReader(private val resolver: ContentResolver, private val account: Account) {

    /** The raw contact's editable fields, as the provider holds them now. */
    fun read(rawId: Long): NativeContact {
        var contact = NativeContact()
        val emails = ArrayList<String>()
        val phones = ArrayList<String>()
        val addresses = ArrayList<String>()
        fun take(c: Cursor) {
            when (c.str(ContactsContract.Data.MIMETYPE)) {
                StructuredName.CONTENT_ITEM_TYPE -> contact = contact.copy(
                    displayName = c.str(StructuredName.DISPLAY_NAME).orEmpty(),
                    given = c.str(StructuredName.GIVEN_NAME).orEmpty(),
                    family = c.str(StructuredName.FAMILY_NAME).orEmpty(),
                )
                Organization.CONTENT_ITEM_TYPE -> contact = contact.copy(
                    company = c.str(Organization.COMPANY).orEmpty(),
                    title = c.str(Organization.TITLE).orEmpty(),
                )
                Note.CONTENT_ITEM_TYPE -> contact = contact.copy(note = c.str(Note.NOTE).orEmpty())
                Photo.CONTENT_ITEM_TYPE -> contact = contact.copy(
                    photoHash = NativeContactDiff.photoHash(c.blob(Photo.PHOTO)),
                )
                Email.CONTENT_ITEM_TYPE -> c.str(Email.ADDRESS)?.let { emails += it }
                Phone.CONTENT_ITEM_TYPE -> c.str(Phone.NUMBER)?.let { phones += it }
                StructuredPostal.CONTENT_ITEM_TYPE ->
                    c.str(StructuredPostal.FORMATTED_ADDRESS)?.let { addresses += it }
            }
        }
        resolver.query(
            syncUri(ContactsContract.Data.CONTENT_URI, account),
            arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4,
                ContactsContract.Data.DATA15,
            ),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawId.toString()),
            ContactsContract.Data._ID,
        )?.use { c -> while (c.moveToNext()) take(c) }
        return contact.copy(emails = emails, phones = phones, addresses = addresses)
    }

    /**
     * The raw contact's photo, encoded for the server: the full-size display photo when the
     * provider kept one, else the thumbnail in the data row.
     */
    fun photo(rawId: Long): ContactCardPhoto? {
        val display = Uri.withAppendedPath(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
            RawContacts.DisplayPhoto.CONTENT_DIRECTORY,
        )
        val bytes = runCatching {
            resolver.openAssetFileDescriptor(display, "r")?.use { it.createInputStream().readBytes() }
        }.getOrNull() ?: thumbnail(rawId)
        return bytes?.takeIf { it.isNotEmpty() }
            ?.let { ContactCardPhoto(mediaType = "image/jpeg", base64 = Base64.encodeToString(it, Base64.NO_WRAP)) }
    }

    private fun thumbnail(rawId: Long): ByteArray? = resolver.query(
        syncUri(ContactsContract.Data.CONTENT_URI, account),
        arrayOf(Photo.PHOTO),
        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
        arrayOf(rawId.toString(), Photo.CONTENT_ITEM_TYPE),
        null,
    )?.use { if (it.moveToFirst()) it.blob(Photo.PHOTO) else null }

    companion object {
        /** See [CalendarMirror] for why the sync-adapter flag is not optional. */
        fun syncUri(uri: Uri, account: Account): Uri = uri.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
            .build()

        internal fun Cursor.str(column: String): String? =
            getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

        internal fun Cursor.blob(column: String): ByteArray? =
            getColumnIndexOrThrow(column).let { if (isNull(it)) null else getBlob(it) }
    }
}

/**
 * [ContactsUploader.Local] over Android's contacts provider, for one mail account's raw contacts.
 *
 * Column use on each raw contact: `SOURCE_ID` is the server identity, `SYNC1` the server
 * fingerprint, `SYNC2` and `SYNC3` the server and local base hashes, `SYNC4` the UID a native
 * create goes up under.
 */
class ProviderContactsLocal(
    private val resolver: ContentResolver,
    private val account: Account,
    accountId: String,
) : ContactsUploader.Local {

    private val prefix = SystemMirror.prefix(accountId)
    private val reader = ContactsProviderReader(resolver, account)
    private val rawContacts = ContactsProviderReader.syncUri(RawContacts.CONTENT_URI, account)

    override fun pending(includeNew: Boolean): List<ContactsUploader.Row> {
        val owned = if (includeNew) {
            "(${RawContacts.SOURCE_ID} LIKE ? OR ${RawContacts.SOURCE_ID} IS NULL)"
        } else {
            "${RawContacts.SOURCE_ID} LIKE ?"
        }
        val out = ArrayList<ContactsUploader.Row>()
        resolver.query(
            rawContacts,
            arrayOf(
                RawContacts._ID,
                RawContacts.SOURCE_ID,
                RawContacts.DELETED,
                RawContacts.SYNC2,
                RawContacts.SYNC3,
                RawContacts.SYNC4,
            ),
            "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ? AND " +
                "(${RawContacts.DIRTY} = 1 OR ${RawContacts.DELETED} = 1) AND $owned",
            arrayOf(account.name, account.type, "$prefix%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val deleted = c.getInt(2) == 1
                out += ContactsUploader.Row(
                    id = id,
                    sourceId = c.getString(1),
                    uid = c.str(RawContacts.SYNC4),
                    deleted = deleted,
                    serverBase = c.str(RawContacts.SYNC2),
                    localBase = c.str(RawContacts.SYNC3),
                    contact = if (deleted) NativeContact() else reader.read(id),
                )
            }
        }
        return out
    }

    override fun photo(id: Long): ContactCardPhoto? = reader.photo(id)

    override fun setUid(id: Long, uid: String) = update(id, ContentValues().apply { put(RawContacts.SYNC4, uid) })

    override fun link(id: Long, sourceId: String) {
        // Two rows for one card would be a duplicate in the system app; see ProviderCalendarLocal.
        val other = resolver.query(
            rawContacts,
            arrayOf(RawContacts._ID),
            "${RawContacts.SOURCE_ID} = ? AND ${RawContacts._ID} != ?",
            arrayOf(sourceId, id.toString()),
            null,
        )?.use { it.moveToFirst() } == true
        if (other) {
            remove(id)
            return
        }
        update(
            id,
            ContentValues().apply {
                put(RawContacts.SOURCE_ID, sourceId)
                put(RawContacts.SYNC1, "")
                put(RawContacts.DIRTY, 0)
            },
        )
    }

    override fun resetToServer(id: Long) = update(
        id,
        ContentValues().apply {
            put(RawContacts.SYNC1, "")
            put(RawContacts.DIRTY, 0)
        },
    )

    override fun clean(id: Long) = update(id, ContentValues().apply { put(RawContacts.DIRTY, 0) })

    override fun remove(id: Long) {
        resolver.delete(ContentUris.withAppendedId(rawContacts, id), null, null)
    }

    private fun update(id: Long, values: ContentValues) {
        resolver.update(ContentUris.withAppendedId(rawContacts, id), values, null, null)
    }

    private fun Cursor.str(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
}

/** [ContactsUploader.Remote] over [DavRepository], which routes each write to CardDAV or JMAP. */
class RepositoryContactsRemote(
    private val repository: DavRepository,
    private val accountId: String,
) : ContactsUploader.Remote {

    override suspend fun cards(): List<ContactsUploader.ServerCard> =
        repository.allContacts(accountId).mapNotNull { row ->
            val parsed = ContactPayload.parse(row) ?: return@mapNotNull null
            ContactsUploader.ServerCard(href = row.href, uid = parsed.uid ?: row.uid, edit = ContactEdit.from(parsed))
        }

    override suspend fun create(uid: String, edit: ContactEdit): DavWriteOutcome =
        repository.createContact(accountId, edit, uid)

    override suspend fun update(href: String, edit: ContactEdit): DavWriteOutcome =
        repository.updateContact(accountId, href, edit)

    override suspend fun delete(href: String): DavWriteOutcome = repository.deleteContact(accountId, href)
}
