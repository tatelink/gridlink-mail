package app.sterna.core.data.mail

import android.content.Context

/**
 * The ONE version whose arrival needs the cached message bodies gone: the release that taught
 * the reader to look for the unsubscribe headers (`versionCode` 168, i.e. the release after
 * 1.4.7 = 167). A literal on purpose — it is a fact about a past upgrade, not the current build
 * number, and it must not move when the version does.
 *
 * ⚠ **The day another change needs the cached bodies dropped, add a SECOND constant and a second
 * threshold; do not raise this one and do not go back to "purge on every version bump".** A
 * threshold is a one-off answer to a one-off need. "Every version bump" is a permanent policy
 * that throws away every body on the first start after each update — every message read again
 * becoming a network round trip, and nothing openable offline — in exchange for a need that
 * arose exactly once.
 */
const val BODY_CACHE_PURGE_VERSION = 168

/**
 * Which version to record after dropping the cached message bodies at startup, or null when
 * there is nothing to do — the whole decision behind [BodyCachePurge], as one executed function.
 *
 * **Why a cache written by an older build has to go.** `openMessage` serves a cached body BEFORE
 * reading anything off the message, and the cache is a serialised `Email`: a row written by a
 * build that did not know a field simply does not have it, and no amount of reopening the message
 * brings it back. The bodies most likely to be in there are the twenty most recent of the inbox,
 * kept warm by the prefetch — which, for most people, is largely newsletters. So the one feature
 * that reads a new header would have been invisible on exactly the mail it was written for, and
 * would only have appeared on what arrived afterwards. Not a bug anyone could report: just a
 * button that is not there.
 *
 * A cache is the one store where this is free: it refills itself from the server on the next open.
 * Hence no migration, no column, no schema change — a DELETE and a number in a preference file.
 *
 * **A threshold CROSSED, not a version bump.** Purge if and only if the cache has not yet been
 * purged for [BODY_CACHE_PURGE_VERSION] and the build now running is that version or later.
 * Crossed, not equalled: someone who skips from 1.4.6 straight to 1.4.9 must purge once on
 * arrival, and someone already past the threshold must never purge again, at 169, at 170, or at
 * any version this app ever ships.
 */
fun bodyCachePurgeVersion(purgedForVersion: Int, currentVersion: Int): Int? =
    currentVersion.takeIf {
        purgedForVersion < BODY_CACHE_PURGE_VERSION && it >= BODY_CACHE_PURGE_VERSION
    }

/**
 * The once-per-upgrade purge of the cached message bodies, remembered in its own small preference
 * file (the decision itself is [bodyCachePurgeVersion]).
 *
 * ⛔ Bodies ONLY. Not the message list, not the attachments, not the search index: those are not
 * caches in the same sense — losing them costs a full resync, and none of them holds a serialised
 * `Email` that an older build could have written short of a field.
 *
 * The version is recorded only AFTER the purge succeeds, so a failure (a locked database at
 * startup, say) is simply retried at the next launch instead of being silently skipped forever.
 */
class BodyCachePurge(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("body_cache_upgrade", Context.MODE_PRIVATE)

    suspend fun onceForVersion(currentVersion: Int, purge: suspend () -> Unit) {
        val toRecord = bodyCachePurgeVersion(prefs.getInt(KEY_PURGED_FOR, 0), currentVersion) ?: return
        purge()
        prefs.edit().putInt(KEY_PURGED_FOR, toRecord).apply()
    }

    private companion object {
        const val KEY_PURGED_FOR = "purgedForVersion"
    }
}
