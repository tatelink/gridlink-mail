package app.gridlink.core.data.db

import app.gridlink.core.jmap.model.EmailBodyPart
import java.io.File

/**
 * Taking a queued message out of the outbox to edit it (#70).
 *
 * The row is NOT deleted when it is opened for editing: it stays in the queue, marked
 * [OutboxState.EDITING] and held back from the send worker, with its durable attachment dir
 * intact. Closing the composer without sending puts it back to [OutboxState.QUEUED] untouched;
 * sending, saving or deleting consumes it. So nothing ever lives only in RAM, and a process
 * death mid-edit loses the edit, never the message.
 *
 * This stages the item's attachments into the cache the composer sends from, so the composer can
 * show and re-send them. File I/O only, no Android APIs, so it is unit-testable on the JVM.
 */
object OutboxEdit {
    /**
     * Stage [attachments] out of their durable per-item dir into [stagingDir] (the cache the
     * composer sends from) and return them as composer parts. An attachment whose bytes can no
     * longer be read makes this THROW rather than drop it silently: reopening an item short of what
     * it held would let the user unknowingly send an amputated message. The durable dir is left in
     * place (the row keeps it), so a throw here loses nothing — the queued message is still whole.
     */
    fun take(attachments: List<OutboxAttachment>, stagingDir: File): List<EmailBodyPart> =
        attachments.map { a ->
            when (a.kind) {
                OutboxAttachments.KIND_JMAP_BLOB -> EmailBodyPart(
                    blobId = a.blobId, type = a.type, size = a.size, name = a.name,
                    disposition = "attachment",
                )
                OutboxAttachments.KIND_IMAP_FILE -> {
                    val bytes = runCatching { File(a.path!!).readBytes() }.getOrNull()
                        ?: error("Couldn't read the queued attachment ${a.name ?: a.path} to edit it.")
                    val staged = copyInto(stagingDir, a.name, bytes)
                    EmailBodyPart(
                        partId = staged.absolutePath, type = a.type, size = bytes.size.toLong(),
                        name = a.name, disposition = "attachment",
                    )
                }
                else -> error("Unknown outbox attachment kind ${a.kind}")
            }
        }

    /** Write [bytes] under a collision-proof name, so two files sharing a name don't overwrite. */
    private fun copyInto(dir: File, name: String?, bytes: ByteArray): File {
        dir.mkdirs()
        val safe = (name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
    }
}
