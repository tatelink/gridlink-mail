package app.sterna.core.data.db

import app.sterna.core.jmap.model.EmailBodyPart
import java.io.File

/**
 * Taking a queued message out of the outbox to edit it, and putting it back unchanged (#70).
 *
 * A queued message exists nowhere else — no server draft, no local copy — so the two halves must
 * be symmetric: whatever the take strips out, the restore has to give back. That is why the take
 * returns two lists. [Taken.parts] is what the composer opens with (chips, re-sendable files);
 * [Taken.restorable] is the same attachments as durable descriptors, cid and disposition intact,
 * pointed at the staged copies — the composer's view flattens inline parts to plain attachments,
 * and putting a flattened list back would hand the queue an amputated message.
 *
 * File I/O only, no Android APIs, so both halves are unit-testable on the JVM.
 */
object OutboxEdit {
    /** The two views of a taken item's attachments; see [take]. */
    data class Taken(
        val parts: List<EmailBodyPart>,
        val restorable: List<OutboxAttachment>,
    )

    /**
     * Stage [attachments] out of their per-item durable dir (which the caller then deletes with the
     * row) into [stagingDir], the cache the composer sends from. An attachment whose bytes can no
     * longer be read is dropped from both lists: it can be neither sent nor put back.
     */
    fun take(attachments: List<OutboxAttachment>, stagingDir: File): Taken {
        val parts = mutableListOf<EmailBodyPart>()
        val restorable = mutableListOf<OutboxAttachment>()
        for (a in attachments) {
            when (a.kind) {
                OutboxAttachments.KIND_JMAP_BLOB -> {
                    parts += EmailBodyPart(
                        blobId = a.blobId, type = a.type, size = a.size, name = a.name,
                        disposition = "attachment",
                    )
                    // The bytes live on the server: the blob id alone survives the row's deletion.
                    restorable += a
                }
                OutboxAttachments.KIND_IMAP_FILE -> {
                    val bytes = runCatching { File(a.path!!).readBytes() }.getOrNull() ?: continue
                    val staged = copyInto(stagingDir, a.name, bytes)
                    parts += EmailBodyPart(
                        partId = staged.absolutePath, type = a.type, size = bytes.size.toLong(),
                        name = a.name, disposition = "attachment",
                    )
                    restorable += a.copy(path = staged.absolutePath, size = bytes.size.toLong())
                }
            }
        }
        return Taken(parts, restorable)
    }

    /**
     * Copy the staged bytes of [attachments] back into [durableDir], the per-item dir of the row
     * being re-inserted, so the queued message is durable again the moment it reappears.
     */
    fun restore(attachments: List<OutboxAttachment>, durableDir: File): List<OutboxAttachment> =
        attachments.mapNotNull { a ->
            if (a.kind != OutboxAttachments.KIND_IMAP_FILE) return@mapNotNull a
            val bytes = runCatching { File(a.path!!).readBytes() }.getOrNull() ?: return@mapNotNull null
            a.copy(path = copyInto(durableDir, a.name, bytes).absolutePath, size = bytes.size.toLong())
        }

    /**
     * The row to re-insert for a taken item: every field the user can see is kept as it was —
     * headers, body, sending identity, state and its failure text, creation and send-after times —
     * and only what belongs to the deleted row is reset (its id, and the file locations the caller
     * rewrites once the new id exists).
     */
    fun restoredRow(row: OutboxEntity): OutboxEntity =
        row.copy(id = 0, attachmentsJson = "[]", pgpEntityPath = null)

    /** Write [bytes] under a collision-proof name, so two files sharing a name don't overwrite. */
    private fun copyInto(dir: File, name: String?, bytes: ByteArray): File {
        dir.mkdirs()
        val safe = (name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
    }
}
