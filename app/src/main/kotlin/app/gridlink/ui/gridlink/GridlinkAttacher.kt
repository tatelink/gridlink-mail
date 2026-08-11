package app.gridlink.ui.gridlink

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.core.data.mail.MailRepository
import app.gridlink.core.jmap.DownloadLimits
import app.gridlink.core.jmap.OutgoingLimits
import app.gridlink.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * How a file picked or shared into the app becomes something the composer can show and the sender
 * can carry.
 *
 * ## Why this is its own thing and not a method on [GridlinkSender]
 * Same reasoning as that interface, one step earlier. Attaching is real work against a real
 * account: it reads a content provider, enforces a size ceiling, and on JMAP it uploads a blob
 * over the network before the user has typed a word. None of that exists in the screenshot
 * harness, and the composer still has to render there with an attach button that does something
 * honest. Splitting it out means [GridlinkComposeScreen] takes a staging function or takes none,
 * and never has to know which kind of build it is in.
 *
 * ## The handle, and why the bytes are not in [GridlinkAttachment]
 * The chip the composer draws is a name and a size. What the outbox needs is an [EmailBodyPart]:
 * an uploaded blob id on JMAP, a staged file path on IMAP. Those are the engine's vocabulary, and
 * [GridlinkComposeScreen] deliberately does not speak it. So the attachment carries an opaque
 * [GridlinkAttachment.id] and the attacher keeps the part behind it, exactly as the thread view's
 * chips already work. The composer moves labels around; whoever staged the file can still find it.
 */
interface GridlinkAttacher {

    /**
     * Stage the file at [uri] and return the chip for it.
     *
     * Throws with a message worth showing when the file cannot be attached: too large, unreadable,
     * or (on JMAP) not uploadable right now. A throw means nothing was attached, which is the only
     * safe failure — a draft that quietly holds fewer files than the user chose is #70's rule
     * broken before the message is even written.
     */
    suspend fun stage(uri: Uri): GridlinkAttachment

    /**
     * Whether this attacher has a file behind [attachment].
     *
     * Pure and fast, because [GridlinkSender.check] runs on the send tap, on the main thread, over
     * a draft that is still on screen. It is the question that decides whether the send happens.
     */
    fun holds(attachment: GridlinkAttachment): Boolean

    /**
     * The parts behind [attachments], for whoever is about to send or save them.
     *
     * 🔴 Throws on an id this attacher never minted rather than skipping it. The sample reply's
     * attachment is a name and a size with no bytes anywhere, and sending the draft that holds it
     * must fail loudly instead of going out one file short.
     */
    fun parts(attachments: List<GridlinkAttachment>): List<EmailBodyPart>
}

/**
 * The real one: reads the file, caps it, and stages it the way the account's protocol needs.
 *
 * IMAP has no blob store, so the bytes go to a cache file and travel into the MIME at delivery.
 * JMAP uploads now and keeps the blob id, which is also why attaching can fail with the phone
 * offline while everything else in the composer carries on working.
 *
 * ⚠️ Held for the life of the host composition, not per composer. A draft can outlive the screen
 * it was typed on (rotation, a fold), and the map is what its chips point at.
 */
class GridlinkFileAttacher(
    private val context: Context,
    private val repository: MailRepository,
    private val accounts: AccountStore,
) : GridlinkAttacher {

    private val staged = mutableMapOf<String, EmailBodyPart>()
    private val counter = AtomicLong(0)

    override suspend fun stage(uri: Uri): GridlinkAttachment {
        val credentials = accounts.load() ?: error("No account to attach a file to.")
        val resolver = context.contentResolver
        val type = resolver.getType(uri)
        // Both columns are documented nullable, and a provider that returns a row with a null SIZE
        // is ordinary (Downloads, several cloud providers). Reading either unguarded crashes on a
        // perfectly normal file.
        val meta = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                (if (c.isNull(0)) null else c.getString(0)) to (if (c.isNull(1)) -1L else c.getLong(1))
            } else {
                null
            }
        }
        val name = meta?.first?.takeIf { it.isNotBlank() } ?: "attachment"
        // Refused before a byte is read when the provider was willing to say how big it is. -1 means
        // it was not, and that file is capped while it is read instead — a declared size is a hint,
        // and the one file crafted to lie about it must not be the one that gets to allocate freely.
        DownloadLimits.enforce(meta?.second ?: -1L, OutgoingLimits.ATTACHMENT_MAX_BYTES)
        val bytes = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use {
                OutgoingLimits.readAtMost(it, OutgoingLimits.ATTACHMENT_MAX_BYTES)
            }
        } ?: error("Couldn't read $name.")

        val part = if (credentials.protocol == MailProtocol.IMAP) {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = withContext(Dispatchers.IO) {
                File(context.cacheDir, "outgoing").apply { mkdirs() }
                    // Nano-time prefixed so two files sharing a name cannot overwrite each other in
                    // the staging dir, which the outbox copies out of at enqueue.
                    .let { File(it, "${System.nanoTime()}-$safe") }
                    .apply { writeBytes(bytes) }
            }
            EmailBodyPart(
                partId = file.absolutePath,
                type = type,
                size = bytes.size.toLong(),
                name = name,
                disposition = "attachment",
            )
        } else {
            repository.uploadAttachment(credentials, bytes, type, name)
        }
        val id = "staged:${counter.incrementAndGet()}"
        staged[id] = part
        return GridlinkAttachment(
            name = name,
            size = Formatter.formatShortFileSize(context, bytes.size.toLong()),
            id = id,
        )
    }

    override fun holds(attachment: GridlinkAttachment): Boolean = staged.containsKey(attachment.id)

    override fun parts(attachments: List<GridlinkAttachment>): List<EmailBodyPart> =
        attachments.map { attachment ->
            staged[attachment.id] ?: error("${attachment.name} has no file behind it.")
        }
}
