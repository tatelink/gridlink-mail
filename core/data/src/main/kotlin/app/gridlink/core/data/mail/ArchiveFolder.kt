package app.gridlink.core.data.mail

/**
 * Which folder an account archives into when the server never gave one the `archive` role.
 *
 * 🔴 This is not a nicety, it is load-bearing, and it lives here rather than inside [MailRepository]
 * because two layers have to agree on the answer. The repository uses it to decide where an Archive
 * swipe puts a message; the folder tree uses it to decide whether that folder may be renamed or
 * deleted. If only the first knew, the app would offer to rename the one folder whose NAME is the
 * only thing keeping archiving pointed at it, and the next archive would quietly create a second
 * "Archive" beside the 231 messages already in the first.
 *
 * Real case, from Tate's own account: Stalwart reports his "Archive" with no role at all, and with
 * `myRights.mayRename` and `mayDelete` both true. The server is not wrong (protocol-wise it IS an
 * ordinary user folder); the app is the only party that knows it is being used as the archive.
 */

/** Lowercased folder names that clearly denote an archive, across Gridlink's locales. */
val ARCHIVE_FOLDER_NAMES = listOf(
    "archive", "archives", "archived",
    "archivé", "archivés", "archiv", "archivio",
    "arquivo", "arquivos", "archief", "archiwum", "архив",
)

/**
 * Whether a folder named [name] is one this app would resolve as an archive.
 *
 * ⚠️ Name only. Whether it is THE archive for an account depends on the other folders around it
 * (top-level wins), which is [archiveFolderIdByName]'s job.
 */
fun isArchiveFolderName(name: String): Boolean = name.lowercase() in ARCHIVE_FOLDER_NAMES

/**
 * The id of the folder [folders] would archive into by name, or null when none of them qualifies.
 *
 * Top-level preferred, matching the repository's own pick, so a nested "Archive" under some project
 * folder does not outrank the real one. Callers that have a `role == "archive"` mailbox should use
 * that instead and never reach this.
 *
 * @param folders every folder of ONE account. Passing a mixed-account list would pick a folder id
 *   that does not exist on the account being asked about.
 */
fun <T> archiveFolderIdByName(
    folders: List<T>,
    name: (T) -> String,
    parentId: (T) -> String?,
    id: (T) -> String,
): String? = folders
    .filter { isArchiveFolderName(name(it)) }
    .minByOrNull { if (parentId(it) == null) 0 else 1 }
    ?.let(id)
