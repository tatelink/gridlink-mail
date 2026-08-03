package app.sterna.ui.inbox

/** What the mail list has to tell the reader about a refresh that did not land. */
internal enum class RefreshNotice {
    /** Nothing to say. */
    NONE,

    /** The DEVICE has no usable network: the calm offline banner, and the offline empty state
     *  that promises a resync (#65 — this is the reported case, with a reporter behind it). */
    OFFLINE,

    /** The refresh failed while the device is online: say so, and show WHICH failure. */
    ERROR,
}

/**
 * Which of the three a refresh landed on, from the connectivity flag and the last refresh error.
 *
 * ⛔ Why this is not `offline || error != null`, which is what the screen did: being offline is a
 * claim about the DEVICE, and the app already knows better. A rejected password, a 403, a TLS
 * failure, a folder that no longer exists, a JMAP request the server refuses — none of them are a
 * network outage, `isNetworkFailure` does not recognise them as one, so connectivity stays TRUE
 * throughout and `ui.offline` stays FALSE. Folding them into the offline banner told the reader
 * "You're offline" about a phone that was plainly online, offered a Retry that could only fail
 * again, and hid the one thing that could have explained it: the error's own text, which used to
 * be displayed nowhere at all. An expired password read as an outage, indefinitely.
 *
 * This is the same doctrine as `app.sterna.net.isOfflineFailure` ("we only say offline when the
 * device confirms it"), applied to the state the list renders rather than to a single throwable —
 * that function judges ONE failure and needs the throwable, which the UI state no longer holds.
 *
 * [offline] wins when both hold: during a real outage a refresh naturally fails too, and the
 * outage is the useful thing to say. That keeps #65's nominal case exactly as it was.
 *
 * Returns an enum rather than a string resource so the decision runs in a plain JVM test —
 * `InboxViewModel` is an `AndroidViewModel` and cannot be instantiated in one.
 */
internal fun refreshNotice(offline: Boolean, error: String?): RefreshNotice = when {
    offline -> RefreshNotice.OFFLINE
    error != null -> RefreshNotice.ERROR
    else -> RefreshNotice.NONE
}
