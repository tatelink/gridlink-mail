package app.gridlink.ui.emailhtml

/**
 * Strips the query parameters that exist to tell a sender you opened their mail.
 *
 * 🔴 This is the "Strip tracking parameters" setting, and until 2026-08-12 that setting was a switch
 * that stored a Boolean nobody read: a tapped link went to the browser exactly as written, campaign
 * tags and all, while Privacy & security said otherwise. Brandon's call when the audit turned it up:
 * wire it rather than delete it, because a privacy control that lies is worse than an absent one.
 *
 * ## Why it works on the URL as TEXT and not on [android.net.Uri]
 * `Uri` is an Android class with no behaviour off-device, so a version of this built on it could not
 * be unit-tested in this module (no Robolectric here, and this is exactly the kind of rule that has
 * to be pinned by tests: it edits the address the user is about to visit). A String in, a String out,
 * and the one call site parses the result.
 *
 * ## What it will not do
 * - It does not touch the path, the host, the fragment or the ORDER of what survives. A shortener's
 *   `/a/b/c` is where the tracking usually is on those links, and it is untouchable without following
 *   the redirect, which would mean fetching the URL before the user asked for it.
 * - It does not decode or re-encode anything. Whatever the sender wrote is what goes out, minus
 *   whole parameters.
 * - It only edits `http(s)`. `mailto:` query parts are the composer's business (subject, body), and
 *   a "tracking" name there is a false positive waiting to happen.
 *
 * ## The list, and why it is a list rather than a heuristic
 * Guessing at parameter names would break real links, and a broken link is a much louder failure than
 * a surviving tracker. So: known analytics families by prefix ([TRACKING_PREFIXES]) plus named
 * click-ids that carry no prefix ([TRACKING_NAMES]). Anything not on it is left alone on purpose.
 */
object TrackingParams {

    /** Analytics families. Everything under these prefixes is campaign metadata by construction. */
    private val TRACKING_PREFIXES = listOf(
        "utm_", // Google Analytics / everybody
        "pk_", // Matomo (legacy)
        "mtm_", // Matomo (current)
        "hsa_", // HubSpot ads
        "vero_", // Vero
        "oly_", // Omeda
        "_hs", // HubSpot email (_hsenc, _hsmi)
    )

    /** Click identifiers, which are single named parameters rather than a family. */
    private val TRACKING_NAMES = setOf(
        "gclid", "dclid", "gbraid", "wbraid", // Google Ads
        "fbclid", // Meta
        "msclkid", // Microsoft
        "twclid", // X
        "ttclid", // TikTok
        "igshid", // Instagram
        "yclid", // Yandex
        "mkt_tok", // Marketo
        "mc_cid", "mc_eid", // Mailchimp
        "ml_subscriber", "ml_subscriber_hash", // MailerLite
        "cmpid", "campaignid", "wickedid", "s_cid", "ncid", "sc_campaign",
        "ck_subscriber_id", // ConvertKit
        "trk", "trkcampaign", // LinkedIn
    )

    /** Whether [name] is one this strips. Case-insensitive: senders are inconsistent about it. */
    private fun isTracking(name: String): Boolean {
        val key = name.lowercase()
        return key in TRACKING_NAMES || TRACKING_PREFIXES.any { key.startsWith(it) }
    }

    /**
     * [url] with its tracking parameters removed, or [url] unchanged when there is nothing to do.
     *
     * A URL whose parameters were ALL tracking loses its `?` as well: a bare trailing question mark
     * is not wrong, but it is the visible residue of an edit the user did not ask to see.
     */
    fun strip(url: String): String {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return url
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#')
        // A '#' BEFORE the '?' means the question mark is inside the fragment and is not a query at
        // all. Editing there would be rewriting a page anchor.
        if (fragmentStart in 0 until queryStart) return url
        val fragment = if (fragmentStart > queryStart) url.substring(fragmentStart) else ""
        val queryEnd = if (fragmentStart > queryStart) fragmentStart else url.length
        val query = url.substring(queryStart + 1, queryEnd)
        val kept = query.split('&')
            .filter { it.isNotEmpty() && !isTracking(it.substringBefore('=')) }
        if (kept.size == query.split('&').count { it.isNotEmpty() }) return url
        val head = url.substring(0, queryStart)
        return if (kept.isEmpty()) head + fragment else head + "?" + kept.joinToString("&") + fragment
    }
}
