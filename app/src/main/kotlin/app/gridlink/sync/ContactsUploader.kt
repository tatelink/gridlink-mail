package app.gridlink.sync

import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.data.dav.DavWriteOutcome
import app.gridlink.core.jmap.model.ContactCardPhoto
import java.util.UUID

/**
 * Sends what the user changed in the system Contacts app back to the server: the contacts twin of
 * [CalendarUploader], with the same conflict rule and the same no-duplicates rule (read its note).
 *
 * The one difference is the base. A contact's two sides do not spell it the same way, so each raw
 * contact keeps two sets of hashes ([NativeContactDiff]): what the provider held and what the
 * server held, both as of the last publish. The user's change is the provider compared with the
 * first; the server's change is the fresh card compared with the second.
 *
 * ## What cannot be sent
 * A change to a postal address is recognised but has no field to go to (Gridlink's contact model
 * reads addresses and does not write them). It is reported in the log and the row is republished
 * from the server, so the system app shows what the server really holds.
 *
 * @param includeNew whether rows with no server identity yet are this mail account's to create.
 *   Two mail accounts can share one Android account (a linked sub-account), and a contact added in
 *   the system app belongs to that Android account, not to either of them; only the first takes it.
 */
class ContactsUploader(
    private val accountId: String,
    private val local: Local,
    private val remote: Remote,
    private val includeNew: Boolean,
    private val newUid: () -> String = { UUID.randomUUID().toString() },
) {

    /** One raw contact with something to send: DIRTY, DELETED, or both. */
    data class Row(
        val id: Long,
        val sourceId: String?,
        /** The UID a create goes up under, written on the row before the first try. */
        val uid: String?,
        val deleted: Boolean,
        val localBase: String?,
        val serverBase: String?,
        val contact: NativeContact,
    )

    /** One card in the cache, already in the contact form's shape. */
    data class ServerCard(val href: String, val uid: String?, val edit: ContactEdit)

    /** The server side. The repository resyncs the cache on its own after a 412. */
    interface Remote {
        suspend fun cards(): List<ServerCard>
        suspend fun create(uid: String, edit: ContactEdit): DavWriteOutcome
        suspend fun update(href: String, edit: ContactEdit): DavWriteOutcome
        suspend fun delete(href: String): DavWriteOutcome
    }

    /** The provider side, written as the sync adapter so nothing here marks a row dirty again. */
    interface Local {
        fun pending(includeNew: Boolean): List<Row>

        /** The stored photo, encoded for the server; null for none. */
        fun photo(id: Long): ContactCardPhoto?
        fun setUid(id: Long, uid: String)

        /** Give a new row its server identity; removes it instead when another row has that one. */
        fun link(id: Long, sourceId: String)

        /** Mark clean and due for a rewrite from the server copy on the publish that follows. */
        fun resetToServer(id: Long)

        /** Mark clean and leave as it is: nothing the server needs was changed. */
        fun clean(id: Long)
        fun remove(id: Long)
    }

    private val prefix = SystemMirror.prefix(accountId)
    private var cache: Map<String, ServerCard> = emptyMap()

    suspend fun run(): CalendarUploader.Result {
        val rows = local.pending(includeNew)
        if (rows.isEmpty()) return CalendarUploader.Result()
        reload()
        var result = CalendarUploader.Result()
        for (row in rows) {
            val href = row.sourceId?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            result += when {
                row.deleted -> delete(row, href)
                href != null -> edit(row, href, retried = false)
                else -> create(row)
            }
        }
        return result
    }

    private suspend fun reload() {
        cache = remote.cards().associateBy { it.href }
    }

    private suspend fun delete(row: Row, href: String?): CalendarUploader.Result {
        // Never reached the server, or already gone from it: nothing to send.
        if (href == null || href !in cache) {
            local.remove(row.id)
            return CalendarUploader.Result()
        }
        val outcome = remote.delete(href)
        return when {
            outcome.error == null -> {
                local.remove(row.id)
                CalendarUploader.Result(uploaded = 1)
            }
            // Changed on the server since: it wins, and comes back on the publish.
            outcome.conflict -> {
                local.remove(row.id)
                CalendarUploader.Result(conflicts = 1)
            }
            else -> CalendarUploader.Result(failures = 1)
        }
    }

    @Suppress("ReturnCount") // One exit per way an edit resolves, as in CalendarUploader.edit.
    private suspend fun edit(row: Row, href: String, retried: Boolean): CalendarUploader.Result {
        val server = cache[href]
        if (server == null) {
            // Deleted on the server while edited here: the server wins.
            local.remove(row.id)
            return CalendarUploader.Result(conflicts = 1)
        }
        val localTouched = NativeContactDiff.changed(
            NativeContactDiff.decode(row.localBase),
            NativeContactDiff.localHashes(row.contact),
        )
        val sendable = localTouched.filterNotTo(LinkedHashSet()) { NativeContactDiff.unsendable(it) }
        if (sendable.isEmpty()) {
            if (localTouched.isEmpty()) {
                local.clean(row.id)
                return CalendarUploader.Result()
            }
            // Only an address changed, which has no way to the server. See the class note.
            local.resetToServer(row.id)
            return CalendarUploader.Result(conflicts = 1)
        }
        val serverTouched = NativeContactDiff.changed(
            NativeContactDiff.decode(row.serverBase),
            NativeContactDiff.serverHashes(server.edit),
        )
        if (sendable.any { it in serverTouched }) {
            local.resetToServer(row.id)
            return CalendarUploader.Result(conflicts = 1)
        }
        val merged = NativeContactDiff.merge(server.edit, row.contact.toEdit(), sendable) { local.photo(row.id) }
        val outcome = remote.update(href, merged)
        return when {
            outcome.error == null -> {
                local.resetToServer(row.id)
                CalendarUploader.Result(uploaded = 1)
            }
            outcome.conflict && !retried -> {
                reload()
                edit(row, href, retried = true)
            }
            else -> CalendarUploader.Result(failures = 1)
        }
    }

    private suspend fun create(row: Row): CalendarUploader.Result {
        val uid = row.uid?.takeIf { it.isNotBlank() } ?: newUid().also { local.setUid(row.id, it) }
        if (adopt(row, uid)) return CalendarUploader.Result()
        val edit = row.contact.toEdit().copy(photo = local.photo(row.id))
        val outcome = remote.create(uid, edit)
        if (outcome.error != null) {
            // The answer may be what was lost: look once before leaving it for the next pass.
            reload()
            return if (adopt(row, uid)) CalendarUploader.Result(uploaded = 1) else CalendarUploader.Result(failures = 1)
        }
        val href = outcome.href ?: return CalendarUploader.Result(failures = 1)
        local.link(row.id, SystemMirror.sourceId(accountId, href))
        return CalendarUploader.Result(uploaded = 1)
    }

    private fun adopt(row: Row, uid: String): Boolean {
        val existing = cache.values.firstOrNull { it.uid == uid } ?: return false
        local.link(row.id, SystemMirror.sourceId(accountId, existing.href))
        return true
    }
}
