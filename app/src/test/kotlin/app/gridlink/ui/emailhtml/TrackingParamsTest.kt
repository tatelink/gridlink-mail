package app.gridlink.ui.emailhtml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rules [TrackingParams.strip] has to keep. It edits the address a tap is about to open, so the
 * interesting half of this file is what it must NOT change: a stripper that mangles a real link
 * fails louder than one that misses a tracker, and the failure lands on the user mid-tap.
 */
class TrackingParamsTest {

    @Test fun `campaign families go`() {
        assertEquals(
            "https://shop.example.com/sale",
            TrackingParams.strip(
                "https://shop.example.com/sale?utm_source=news&utm_medium=email&utm_campaign=aug",
            ),
        )
    }

    @Test fun `click ids go`() {
        assertEquals(
            "https://example.com/a",
            TrackingParams.strip("https://example.com/a?fbclid=XYZ&gclid=ABC&mc_eid=99"),
        )
    }

    @Test fun `real parameters stay, in their original order`() {
        assertEquals(
            "https://example.com/search?q=mail&page=2&sort=date",
            TrackingParams.strip(
                "https://example.com/search?q=mail&utm_source=x&page=2&fbclid=1&sort=date",
            ),
        )
    }

    @Test fun `a link with nothing to strip is returned byte for byte`() {
        val url = "https://example.com/a/b?id=7&ref=friend#section-2"
        assertEquals(url, TrackingParams.strip(url))
    }

    @Test fun `the fragment survives and travels with the shortened query`() {
        assertEquals(
            "https://example.com/doc?page=3#heading",
            TrackingParams.strip("https://example.com/doc?utm_source=news&page=3#heading"),
        )
    }

    @Test fun `a question mark inside a fragment is not a query`() {
        val url = "https://example.com/app#/route?utm_source=news"
        assertEquals(url, TrackingParams.strip(url))
    }

    @Test fun `non-web schemes are left alone entirely`() {
        val mailto = "mailto:someone@example.com?subject=utm_source"
        assertEquals(mailto, TrackingParams.strip(mailto))
        assertEquals("tel:+15551234567", TrackingParams.strip("tel:+15551234567"))
    }

    @Test fun `names are matched case-insensitively`() {
        assertEquals("https://example.com/a", TrackingParams.strip("https://example.com/a?UTM_Source=x"))
    }

    @Test fun `a parameter that merely starts like a real word is not a tracker`() {
        // "utmost" is not "utm_", and "gclide" is not "gclid": prefixes carry their underscore and
        // names match whole. A looser rule here would quietly break somebody's search.
        val url = "https://example.com/a?utmost=1&gclide=2&campaign_notes=3"
        assertEquals(url, TrackingParams.strip(url))
    }
}
