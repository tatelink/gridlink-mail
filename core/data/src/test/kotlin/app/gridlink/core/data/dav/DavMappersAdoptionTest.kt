package app.gridlink.core.data.dav

import app.gridlink.core.data.db.DavCollectionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A collection changing protocol without changing identity.
 *
 * 🔴 What is really being pinned here is that an account which has been syncing over CalDAV and then
 * meets a server advertising JMAP does NOT re-key its calendars. The system-calendar mirror derives
 * the Android provider's `_SYNC_ID` from the collection url, so a new url is not a rename: it is a
 * delete and an insert, taking the colour and visibility the user chose in their calendar app and
 * every event filed underneath with it.
 */
class DavMappersAdoptionTest {

    private fun dav(url: String, name: String?) = DavCollectionEntity(
        accountId = "acct",
        url = url,
        kind = DavCollectionEntity.KIND_CALENDAR,
        displayName = name,
        color = null,
        syncToken = "token",
        sortOrder = 0,
    )

    private fun adopt(
        existing: List<DavCollectionEntity>,
        vararg collections: Pair<String, String>,
    ) = DavMappers.adoptCollectionUrls(existing, collections.toList(), DavMappers::jmapCollectionUrl)

    @Test fun aCalendarAlreadySyncedOverCaldavKeepsItsUrl() {
        val adopted = adopt(
            listOf(dav("https://mail.example/dav/cal/personal/", "Personal")),
            "cal-1" to "Personal",
        )

        assertEquals(mapOf("cal-1" to "https://mail.example/dav/cal/personal/"), adopted)
    }

    @Test fun aCalendarThisCacheHasNeverSeenGetsTheSyntheticUrl() {
        val adopted = adopt(
            listOf(dav("https://mail.example/dav/cal/personal/", "Personal")),
            "cal-1" to "Personal",
            "cal-2" to "Birthdays",
        )

        assertEquals("https://mail.example/dav/cal/personal/", adopted["cal-1"])
        assertEquals("jmap:calendar/cal-2", adopted["cal-2"])
    }

    @Test fun aRowThatIsAlreadyJmapKeyedIsNotAdoptionMaterial() {
        // The second sync must produce exactly what the first one did. Matching a `jmap:` row by
        // name would be a no-op at best, and on a server that renamed a calendar it would hand one
        // calendar's url to a different calendar's id.
        val adopted = adopt(
            listOf(
                DavCollectionEntity(
                    accountId = "acct",
                    url = "jmap:calendar/cal-9",
                    kind = DavCollectionEntity.KIND_CALENDAR,
                    displayName = "Personal",
                    color = null,
                    syncToken = null,
                    sortOrder = 0,
                    remoteId = "cal-9",
                ),
            ),
            "cal-1" to "Personal",
        )

        assertEquals("jmap:calendar/cal-1", adopted["cal-1"])
    }

    @Test fun matchingIgnoresCaseAndSurroundingSpaceBecauseTheNameIsAHumanLabel() {
        val adopted = adopt(
            listOf(dav("https://mail.example/dav/cal/personal/", "  personal ")),
            "cal-1" to "Personal",
        )

        assertEquals("https://mail.example/dav/cal/personal/", adopted["cal-1"])
    }

    @Test fun twoCalendarsSharingANameCannotBothClaimTheSameRow() {
        // 🔴 The url is a primary key. Handing it to two calendars would collapse them into one row
        // and file both calendars' events together.
        val adopted = adopt(
            listOf(
                dav("https://mail.example/dav/cal/work-a/", "Work"),
                dav("https://mail.example/dav/cal/work-b/", "Work"),
            ),
            "cal-1" to "Work",
            "cal-2" to "Work",
            "cal-3" to "Work",
        )

        assertEquals(3, adopted.values.toSet().size)
        assertEquals("https://mail.example/dav/cal/work-a/", adopted["cal-1"])
        assertEquals("https://mail.example/dav/cal/work-b/", adopted["cal-2"])
        assertEquals("jmap:calendar/cal-3", adopted["cal-3"])
    }

    @Test fun anUnnamedCollectionMatchesNothingRatherThanTheFirstOtherUnnamedOne() {
        // Two nameless calendars have no handle to match on, and pairing them would be a coin flip
        // that files one calendar's events under another. Fresh urls are the safe answer: they cost
        // the one-off churn this function exists to avoid, once, for a calendar nobody named.
        val adopted = adopt(
            listOf(dav("https://mail.example/dav/cal/mystery/", null)),
            "cal-1" to "",
        )

        assertEquals("jmap:calendar/cal-1", adopted["cal-1"])
    }

    @Test fun anAddressBookAdoptsTheSameWayWithItsOwnSyntheticScheme() {
        val adopted = DavMappers.adoptCollectionUrls(
            existing = listOf(
                dav("https://mail.example/dav/card/default/", "Contacts")
                    .copy(kind = DavCollectionEntity.KIND_CONTACTS),
            ),
            collections = listOf("book-1" to "Contacts", "book-2" to "Shared"),
            syntheticUrl = DavMappers::jmapBookUrl,
        )

        assertEquals("https://mail.example/dav/card/default/", adopted["book-1"])
        assertEquals("jmap:addressbook/book-2", adopted["book-2"])
    }
}
