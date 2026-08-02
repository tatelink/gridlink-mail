package app.sterna.core.data.mail

import android.content.Context

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
 * `>` and not `>=`: the same version must purge exactly once, or every start of the app would
 * throw away the bodies it just fetched and every message would be a network round trip.
 */
fun bodyCachePurgeVersion(purgedForVersion: Int, currentVersion: Int): Int? =
    currentVersion.takeIf { it > purgedForVersion }

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
