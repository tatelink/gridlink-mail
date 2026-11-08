package app.gridlink.sync

import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.data.dav.DavWriteOutcome
import app.gridlink.core.jmap.model.ContactCardPhoto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload half of the two-way contacts mirror: what an edit, create or delete in the system
 * Contacts app turns into on the server, and what happens when the server changed the same card.
 */
class ContactsUploaderTest {

    private val account = "acc"

    /** The card as the server held it at the last publish. */
    private val serverBase = ContactEdit(
        given = "Ada",
        family = "Lovelace",
        company = "Engines Ltd",
        emails = listOf("ada@example.com"),
        phones = listOf("+15550100"),
        note = "met at the fair",
    )

    /** The same card as the provider held it after that publish: its own spelling of the phone. */
    private val localBase = NativeContact(
        given = "Ada",
        family = "Lovelace",
        displayName = "Ada Lovelace",
        company = "Engines Ltd",
        emails = listOf("ada@example.com"),
        phones = listOf("+1 555-0100"),
        note = "met at the fair",
        addresses = listOf("1 Main St"),
    )

    private fun row(
        id: Long = 1,
        href: String? = "c1.vcf",
        contact: NativeContact = localBase,
        deleted: Boolean = false,
        uid: String? = null,
        bases: Boolean = true,
    ) = ContactsUploader.Row(
        id = id,
        sourceId = href?.let { SystemMirror.sourceId(account, it) },
        uid = uid,
        deleted = deleted,
        localBase = if (bases) NativeContactDiff.encode(NativeContactDiff.localHashes(localBase)) else null,
        serverBase = if (bases) NativeContactDiff.encode(NativeContactDiff.serverHashes(serverBase)) else null,
        contact = contact,
    )

    private fun card(href: String = "c1.vcf", uid: String = "u1", edit: ContactEdit = serverBase) =
        ContactsUploader.ServerCard(href, uid, edit)

    private class FakeLocal(private val rows: List<ContactsUploader.Row>) : ContactsUploader.Local {
        val uids = mutableMapOf<Long, String>()
        val links = mutableMapOf<Long, String>()
        val reset = mutableListOf<Long>()
        val cleaned = mutableListOf<Long>()
        val removed = mutableListOf<Long>()
        var askedIncludeNew: Boolean? = null
        var photo: ContactCardPhoto? = null
        var photoReads = 0

        override fun pending(includeNew: Boolean): List<ContactsUploader.Row> {
            askedIncludeNew = includeNew
            return rows.filter { includeNew || it.sourceId != null }
        }
        override fun photo(id: Long): ContactCardPhoto? {
            photoReads++
            return photo
        }
        override fun setUid(id: Long, uid: String) {
            uids[id] = uid
        }
        override fun link(id: Long, sourceId: String) {
            links[id] = sourceId
        }
        override fun resetToServer(id: Long) {
            reset += id
        }
        override fun clean(id: Long) {
            cleaned += id
        }
        override fun remove(id: Long) {
            removed += id
        }
    }

    private class FakeRemote(var cache: List<ContactsUploader.ServerCard>) : ContactsUploader.Remote {
        val updates = mutableListOf<Pair<String, ContactEdit>>()
        val creates = mutableListOf<Pair<String, ContactEdit>>()
        val deletes = mutableListOf<String>()

        /** Answers given in order; once exhausted every write succeeds. */
        val answers = ArrayDeque<DavWriteOutcome>()

        /** What the cache becomes when a write is answered (a 412 resync, or a lost answer). */
        var afterAnswer: List<ContactsUploader.ServerCard>? = null

        private fun answer(href: String): DavWriteOutcome {
            val next = answers.removeFirstOrNull() ?: return DavWriteOutcome(href = href)
            afterAnswer?.let { cache = it }
            return next
        }

        override suspend fun cards() = cache
        override suspend fun create(uid: String, edit: ContactEdit): DavWriteOutcome {
            creates += uid to edit
            return answer("new-$uid.vcf")
        }
        override suspend fun update(href: String, edit: ContactEdit): DavWriteOutcome {
            updates += href to edit
            return answer(href)
        }
        override suspend fun delete(href: String): DavWriteOutcome {
            deletes += href
            return answer(href)
        }
    }

    private val conflict = DavWriteOutcome(error = "changed on the server", conflict = true)

    private fun uploader(local: FakeLocal, remote: FakeRemote, includeNew: Boolean = true) =
        ContactsUploader(account, local, remote, includeNew, newUid = { "fresh" })

    // ------------------------------------------------------------------ edits

    @Test
    fun `an edited phone number is sent with the rest of the server card kept`() = runTest {
        val local = FakeLocal(listOf(row(contact = localBase.copy(phones = listOf("+1 555-0199")))))
        val remote = FakeRemote(listOf(card()))

        val result = uploader(local, remote).run()

        assertEquals(1, result.uploaded)
        assertEquals(listOf("+1 555-0199"), remote.updates.single().second.phones)
        assertEquals(serverBase.copy(phones = listOf("+1 555-0199")), remote.updates.single().second)
        assertEquals(listOf(1L), local.reset)
    }

    @Test
    fun `an untouched row is cleaned and nothing is sent`() = runTest {
        val local = FakeLocal(listOf(row()))
        val remote = FakeRemote(listOf(card()))

        val result = uploader(local, remote).run()

        assertEquals(CalendarUploader.Result(), result)
        assertTrue(remote.updates.isEmpty())
        assertEquals(listOf(1L), local.cleaned)
        assertTrue(local.reset.isEmpty())
    }

    @Test
    fun `the provider's own phone formatting is not an edit`() {
        val a = NativeContactDiff.localHashes(localBase)
        val b = NativeContactDiff.localHashes(localBase.copy(phones = listOf("+15550100")))
        assertEquals(a, b)
    }

    @Test
    fun `a server edit to another field survives beside the local one`() = runTest {
        val serverNow = serverBase.copy(note = "now a client")
        val local = FakeLocal(listOf(row(contact = localBase.copy(emails = listOf("ada@engines.example")))))
        val remote = FakeRemote(listOf(card(edit = serverNow)))

        val result = uploader(local, remote).run()

        assertEquals(1, result.uploaded)
        val sent = remote.updates.single().second
        assertEquals("now a client", sent.note)
        assertEquals(listOf("ada@engines.example"), sent.emails)
    }

    @Test
    fun `both sides changing the same field lets the server win`() = runTest {
        val local = FakeLocal(listOf(row(contact = localBase.copy(note = "local note"))))
        val remote = FakeRemote(listOf(card(edit = serverBase.copy(note = "server note"))))

        val result = uploader(local, remote).run()

        assertEquals(1, result.conflicts)
        assertTrue(remote.updates.isEmpty())
        assertEquals(listOf(1L), local.reset)
    }

    @Test
    fun `a company change on the server overlaps a local name change`() = runTest {
        val local = FakeLocal(listOf(row(contact = localBase.copy(family = "King"))))
        val remote = FakeRemote(listOf(card(edit = serverBase.copy(company = "Analytical Co"))))

        val result = uploader(local, remote).run()

        assertEquals(1, result.conflicts)
        assertTrue(remote.updates.isEmpty())
    }

    @Test
    fun `a 412 is decided again against the resynced copy and sent when it no longer overlaps`() = runTest {
        val local = FakeLocal(listOf(row(contact = localBase.copy(title = "Countess"))))
        val remote = FakeRemote(listOf(card())).apply {
            answers += conflict
            afterAnswer = listOf(card(edit = serverBase.copy(note = "edited meanwhile")))
        }

        val result = uploader(local, remote).run()

        assertEquals(1, result.uploaded)
        assertEquals(2, remote.updates.size)
        val second = remote.updates.last().second
        assertEquals("Countess", second.title)
        assertEquals("edited meanwhile", second.note)
        assertEquals(listOf(1L), local.reset)
    }

    @Test
    fun `a 412 over the same field lets the server win`() = runTest {
        val local = FakeLocal(listOf(row(contact = localBase.copy(title = "Countess"))))
        val remote = FakeRemote(listOf(card())).apply {
            answers += conflict
            afterAnswer = listOf(card(edit = serverBase.copy(title = "Mathematician")))
        }

        val result = uploader(local, remote).run()

        assertEquals(1, result.conflicts)
        assertEquals(1, remote.updates.size)
        assertEquals(listOf(1L), local.reset)
    }

    @Test
    fun `a second 412 leaves the row dirty for the next pass`() = runTest {
        val local = FakeLocal(listOf(row(contact = localBase.copy(title = "Countess"))))
        val remote = FakeRemote(listOf(card())).apply {
            answers += conflict
            answers += conflict
        }

        val result = uploader(local, remote).run()

        assertEquals(1, result.failures)
        assertFalse(result.wroteAnything)
        assertTrue(local.reset.isEmpty())
        assertTrue(local.cleaned.isEmpty())
    }

    @Test
    fun `an edit to a card the server deleted is dropped`() = runTest {
        val local = FakeLocal(listOf(row(contact = localBase.copy(title = "Countess"))))
        val remote = FakeRemote(emptyList())

        val result = uploader(local, remote).run()

        assertEquals(1, result.conflicts)
        assertEquals(listOf(1L), local.removed)
        assertTrue(remote.updates.isEmpty())
    }

    @Test
    fun `an address change alone cannot be sent and the server copy is put back`() = runTest {
        val local = FakeLocal(listOf(row(contact = localBase.copy(addresses = listOf("2 Side St")))))
        val remote = FakeRemote(listOf(card()))

        val result = uploader(local, remote).run()

        assertTrue(remote.updates.isEmpty())
        assertEquals(listOf(1L), local.reset)
        assertEquals(1, result.conflicts)
    }

    @Test
    fun `a new photo is read and sent`() = runTest {
        val local = FakeLocal(listOf(row(contact = localBase.copy(photoHash = "abc")))).apply {
            photo = ContactCardPhoto(mediaType = "image/jpeg", base64 = "AAAA")
        }
        val remote = FakeRemote(listOf(card()))

        uploader(local, remote).run()

        assertEquals("AAAA", remote.updates.single().second.photo?.base64)
        assertEquals(1, local.photoReads)
    }

    @Test
    fun `an organization card keeps its company as its name`() {
        val org = NativeContact(
            given = "Redoak",
            family = "Foodservice",
            displayName = "Redoak Foodservice",
            company = "Redoak Foodservice",
        )
        assertTrue(org.isOrganization)
        assertEquals(ContactEdit(family = "Redoak Foodservice"), org.toEdit())
        assertTrue(org.toEdit().isOrganization)
    }

    // ---------------------------------------------------------------- deletes

    @Test
    fun `a deleted contact is deleted on the server`() = runTest {
        val local = FakeLocal(listOf(row(deleted = true)))
        val remote = FakeRemote(listOf(card()))

        val result = uploader(local, remote).run()

        assertEquals(1, result.uploaded)
        assertEquals(listOf("c1.vcf"), remote.deletes)
        assertEquals(listOf(1L), local.removed)
    }

    @Test
    fun `a delete refused with 412 lets the server's newer card come back`() = runTest {
        val local = FakeLocal(listOf(row(deleted = true)))
        val remote = FakeRemote(listOf(card())).apply { answers += conflict }

        val result = uploader(local, remote).run()

        assertEquals(1, result.conflicts)
        assertEquals(listOf(1L), local.removed)
    }

    @Test
    fun `a delete that fails otherwise keeps the tombstone`() = runTest {
        val local = FakeLocal(listOf(row(deleted = true)))
        val remote = FakeRemote(listOf(card())).apply { answers += DavWriteOutcome(error = "offline") }

        val result = uploader(local, remote).run()

        assertEquals(1, result.failures)
        assertTrue(local.removed.isEmpty())
    }

    @Test
    fun `a contact deleted before it was uploaded is only removed locally`() = runTest {
        val local = FakeLocal(listOf(row(href = null, deleted = true, bases = false)))
        val remote = FakeRemote(listOf(card()))

        uploader(local, remote).run()

        assertTrue(remote.deletes.isEmpty())
        assertEquals(listOf(1L), local.removed)
    }

    // ---------------------------------------------------------------- creates

    @Test
    fun `a new contact gets its UID first and is linked to the created card`() = runTest {
        val new = NativeContact(
            given = "Grace",
            family = "Hopper",
            displayName = "Grace Hopper",
            phones = listOf("555"),
        )
        val local = FakeLocal(listOf(row(id = 7, href = null, contact = new, bases = false)))
        val remote = FakeRemote(emptyList())

        val result = uploader(local, remote).run()

        assertEquals(1, result.uploaded)
        assertEquals("fresh", local.uids[7])
        assertEquals("fresh", remote.creates.single().first)
        assertEquals("Grace", remote.creates.single().second.given)
        assertEquals(SystemMirror.sourceId(account, "new-fresh.vcf"), local.links[7])
    }

    @Test
    fun `a create whose answer was lost is adopted, not created twice`() = runTest {
        val new = NativeContact(given = "Grace", family = "Hopper", displayName = "Grace Hopper")
        val local = FakeLocal(listOf(row(id = 7, href = null, contact = new, uid = "u-new", bases = false)))
        val remote = FakeRemote(listOf(card(href = "srv.vcf", uid = "u-new", edit = ContactEdit(given = "Grace"))))

        val result = uploader(local, remote).run()

        assertTrue(remote.creates.isEmpty())
        assertEquals(SystemMirror.sourceId(account, "srv.vcf"), local.links[7])
        assertEquals(0, result.failures)
    }

    @Test
    fun `a failed create is adopted when the card did land`() = runTest {
        val new = NativeContact(given = "Grace", displayName = "Grace")
        val local = FakeLocal(listOf(row(id = 7, href = null, contact = new, bases = false)))
        val remote = FakeRemote(emptyList()).apply {
            answers += DavWriteOutcome(error = "timeout")
            afterAnswer = listOf(card(href = "landed.vcf", uid = "fresh"))
        }

        val result = uploader(local, remote).run()

        assertEquals(1, result.uploaded)
        assertEquals(SystemMirror.sourceId(account, "landed.vcf"), local.links[7])
    }

    @Test
    fun `a failed create stays for the next pass`() = runTest {
        val new = NativeContact(given = "Grace", displayName = "Grace")
        val local = FakeLocal(listOf(row(id = 7, href = null, contact = new, bases = false)))
        val remote = FakeRemote(emptyList()).apply { answers += DavWriteOutcome(error = "offline") }

        val result = uploader(local, remote).run()

        assertEquals(1, result.failures)
        assertTrue(local.links.isEmpty())
        assertEquals("fresh", local.uids[7])
    }

    @Test
    fun `a second mail account on the same Android account leaves new contacts alone`() = runTest {
        val new = NativeContact(given = "Grace", displayName = "Grace")
        val local = FakeLocal(listOf(row(id = 7, href = null, contact = new, bases = false)))
        val remote = FakeRemote(emptyList())

        uploader(local, remote, includeNew = false).run()

        assertEquals(false, local.askedIncludeNew)
        assertTrue(remote.creates.isEmpty())
    }

    @Test
    fun `base hashes round-trip through their stored form`() {
        val hashes = NativeContactDiff.serverHashes(serverBase)
        assertEquals(hashes, NativeContactDiff.decode(NativeContactDiff.encode(hashes)))
        assertEquals(null, NativeContactDiff.decode(""))
    }
}
