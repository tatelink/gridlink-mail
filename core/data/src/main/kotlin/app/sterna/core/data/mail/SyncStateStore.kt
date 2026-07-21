package app.sterna.core.data.mail

import android.content.Context

/**
 * Persists the per-(account, mailbox) JMAP sync cursors, write-through under
 * [MailRepository]'s in-memory map. Without it every process death forces a full
 * re-query per folder; with UnifiedPush (issue #17) the process is routinely dead
 * between pushes, so persisted cursors are what make each wakeup a cheap delta.
 * Value format: "queryState\nemailState".
 */
class SyncStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("sync_states", Context.MODE_PRIVATE)

    init {
        // v2: folder queries went uncollapsed (collapseThreads=false). A queryState minted by
        // the old collapsed query describes a DIFFERENT query — an Email/queryChanges against
        // it can silently omit thread members that predate the cursor, so the cache would
        // never backfill them. Drop the old cursors once; the next sync does a full re-query.
        if (prefs.getInt(VERSION_KEY, 1) < CURSOR_VERSION) clear()
    }

    fun save(key: String, queryState: String, emailState: String) {
        prefs.edit().putString(key, "$queryState\n$emailState").apply()
    }

    /** The stored (queryState, emailState) pair, or null. */
    fun load(key: String): Pair<String, String>? {
        val raw = prefs.getString(key, null) ?: return null
        val split = raw.split('\n', limit = 2)
        return if (split.size == 2) split[0] to split[1] else null
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().putInt(VERSION_KEY, CURSOR_VERSION).apply()
    }
}

/** Store schema marker; sync keys are "<localAccountId><mailboxId>", which can't collide. */
private const val VERSION_KEY = "cursor_version"
private const val CURSOR_VERSION = 2
