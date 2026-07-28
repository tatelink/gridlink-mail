package app.sterna.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Taking a queued message out to edit it and putting it back must lose nothing (#70): a message in
 * the outbox exists nowhere else, so a round trip that dropped a field or an attachment would be
 * the same silent data loss, only slower.
 */
class OutboxEditTest {
    private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

    private fun row(attachmentsJson: String = "[]") = OutboxEntity(
        id = 7,
        accountId = "acct-1",
        recipients = "jordan@example.org,alex@example.org",
        cc = "cc@example.org",
        bcc = "bcc@example.org",
        subject = "Quarterly report",
        textBody = "Body as queued.",
        htmlBody = "<p>Body as queued.</p>",
        fromName = "Alex Rivera",
        fromEmail = "alex@example.org",
        inReplyTo = "<a@example.org>",
        references = "<a@example.org> <b@example.org>",
        attachmentsJson = attachmentsJson,
        createdAtMillis = 1_000L,
        notBeforeMillis = 2_000L,
        state = OutboxState.FAILED,
        attemptCount = 3,
        lastError = "Connection refused",
        lastAttemptMillis = 1_500L,
        draftEmailId = "M42",
    )

    @Test fun roundTripKeepsEveryVisibleFieldOfTheRow() {
        val original = row()
        val restored = OutboxEdit.restoredRow(original)
        // Only what belonged to the deleted row is reset; the caller rewrites the file fields.
        assertEquals(0L, restored.id)
        assertEquals("[]", restored.attachmentsJson)
        assertNull(restored.pgpEntityPath)
        // Everything the user can see comes back untouched, failure text included.
        assertEquals(original.copy(id = 0, attachmentsJson = "[]", pgpEntityPath = null), restored)
        assertEquals(OutboxState.FAILED, restored.state)
        assertEquals("Connection refused", restored.lastError)
        assertEquals(3, restored.attemptCount)
        assertEquals(1_000L, restored.createdAtMillis)
        assertEquals(2_000L, restored.notBeforeMillis)
        assertEquals("M42", restored.draftEmailId)
    }

    @Test fun roundTripKeepsAttachmentsBytesAndMetadata() {
        val durable = tempDir("outbox-durable")
        val staging = tempDir("outbox-staging")
        val bytes = "%PDF-1.4 pretend".toByteArray()
        val file = File(durable, "report.pdf").apply { writeBytes(bytes) }
        val attachments = listOf(
            OutboxAttachment(
                kind = OutboxAttachments.KIND_IMAP_FILE, path = file.absolutePath,
                type = "application/pdf", name = "report.pdf", size = bytes.size.toLong(),
                disposition = "attachment",
            ),
            OutboxAttachment(
                kind = OutboxAttachments.KIND_JMAP_BLOB, blobId = "G123",
                type = "text/calendar", name = "invite.ics", size = 56, disposition = "attachment",
            ),
        )

        val taken = OutboxEdit.take(attachments, staging)
        // The row and its durable dir are deleted the moment the item is taken out.
        durable.deleteRecursively()

        val back = tempDir("outbox-restored")
        val restored = OutboxEdit.restore(taken.restorable, back)

        assertEquals(2, restored.size)
        val imap = restored[0]
        assertEquals(OutboxAttachments.KIND_IMAP_FILE, imap.kind)
        assertEquals("report.pdf", imap.name)
        assertEquals("application/pdf", imap.type)
        assertEquals(bytes.size.toLong(), imap.size)
        // The bytes survived the durable dir going away, via the staged copy.
        assertTrue(File(imap.path!!).exists())
        assertEquals(String(bytes), File(imap.path!!).readText())
        assertNotEquals(file.absolutePath, imap.path)
        // A server-side blob needs no copy: the id alone still points at the bytes.
        assertEquals(attachments[1], restored[1])
    }

    @Test fun inlineImageComesBackInlineNotFlattenedToAnAttachment() {
        val durable = tempDir("outbox-durable")
        val staging = tempDir("outbox-staging")
        val file = File(durable, "logo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val inline = OutboxAttachment(
            kind = OutboxAttachments.KIND_IMAP_FILE, path = file.absolutePath,
            type = "image/png", name = "logo.png", size = 3,
            cid = "logo@sterna", disposition = "inline",
        )

        val taken = OutboxEdit.take(listOf(inline), staging)
        durable.deleteRecursively()
        val restored = OutboxEdit.restore(taken.restorable, tempDir("outbox-restored"))

        // The composer's own view of the part is a plain attachment chip...
        assertEquals("attachment", taken.parts.single().disposition)
        // ...but what goes back in the queue is the part as it was queued, cid and all.
        assertEquals("logo@sterna", restored.single().cid)
        assertEquals("inline", restored.single().disposition)
        assertEquals("image/png", restored.single().type)
    }

    @Test fun anAttachmentWhoseBytesAreGoneIsNotOfferedForEditingOrRestored() {
        val staging = tempDir("outbox-staging")
        val missing = OutboxAttachment(
            kind = OutboxAttachments.KIND_IMAP_FILE, path = "/nonexistent/gone.pdf",
            type = "application/pdf", name = "gone.pdf", size = 10,
        )
        val taken = OutboxEdit.take(listOf(missing), staging)
        assertTrue(taken.parts.isEmpty())
        assertTrue(taken.restorable.isEmpty())
    }

    @Test fun takenAttachmentsAreCopiesTheComposerCanSendFrom() {
        val durable = tempDir("outbox-durable")
        val staging = tempDir("outbox-staging")
        val file = File(durable, "notes.txt").apply { writeText("hello") }
        val a = OutboxAttachment(
            kind = OutboxAttachments.KIND_IMAP_FILE, path = file.absolutePath,
            type = "text/plain", name = "notes.txt", size = 5,
        )
        val taken = OutboxEdit.take(listOf(a), staging)
        val staged = File(taken.parts.single().partId!!)
        assertEquals(staging.absolutePath, staged.parentFile?.absolutePath)
        assertEquals("hello", staged.readText())
        // Two files sharing a name must not overwrite each other.
        val twice = OutboxEdit.take(listOf(a, a), staging)
        assertNotEquals(twice.parts[0].partId, twice.parts[1].partId)
    }
}
