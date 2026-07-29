package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxIdRole
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchableFoldersTest {

    private fun folders(vararg pairs: Pair<String, String?>) = pairs.map { MailboxIdRole(it.first, it.second) }

    @Test fun trashAndJunkAreLeftOut() {
        val ids = searchableFolderIds(
            folders("i" to "inbox", "a" to "archive", "j" to "junk", "t" to "trash"),
        )
        assertEquals(listOf("i", "a"), ids)
    }

    @Test fun spamIsTheOtherNameForJunk() {
        assertEquals(listOf("i"), searchableFolderIds(folders("i" to "inbox", "s" to "spam")))
    }

    @Test fun caseAndSpacingDoNotDecide() {
        assertEquals(listOf("i"), searchableFolderIds(folders("i" to "inbox", "t" to " Trash ")))
    }

    @Test fun aFolderWithoutARoleIsSearched() {
        assertEquals(listOf("i", "x"), searchableFolderIds(folders("i" to "inbox", "x" to null)))
    }

    @Test fun theGivenOrderIsKept() {
        assertEquals(
            listOf("i", "a", "s", "d", "x"),
            searchableFolderIds(
                folders("i" to "inbox", "a" to "archive", "s" to "sent", "d" to "drafts", "x" to "custom"),
            ),
        )
    }

    // excludedSearchFolderIds is the exact complement (same role source), used to exclude Trash/Junk
    // from the JMAP server search and the FTS crawl so every server behaves like the IMAP walk.
    @Test fun excludedIsJustTheTrashAndJunkFolders() {
        assertEquals(
            listOf("j", "t"),
            excludedSearchFolderIds(
                folders("i" to "inbox", "a" to "archive", "j" to "junk", "t" to "trash"),
            ),
        )
    }

    @Test fun excludedCatchesSpamAndIgnoresCaseAndSpacing() {
        assertEquals(listOf("s", "t"), excludedSearchFolderIds(folders("i" to "inbox", "s" to "SPAM", "t" to " Trash ")))
    }

    @Test fun excludedIsEmptyWhenNothingIsTrashOrJunk() {
        assertEquals(emptyList<String>(), excludedSearchFolderIds(folders("i" to "inbox", "x" to null)))
    }

    @Test fun searchableAndExcludedPartitionTheFolders() {
        val all = folders("i" to "inbox", "a" to "archive", "j" to "junk", "t" to "trash", "x" to null)
        assertEquals(listOf("i", "a", "x"), searchableFolderIds(all))
        assertEquals(listOf("j", "t"), excludedSearchFolderIds(all))
    }
}
