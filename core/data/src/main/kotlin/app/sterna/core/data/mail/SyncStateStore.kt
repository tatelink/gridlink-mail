package app.sterna.core.data.mail

import android.content.Context

/**
 * Persists the per-(account, mailbox) JMAP sync cursors, write-through under
 * [MailRepository]'s in-memory map. Without it every process death forces a full
 * re-query per folder; with UnifiedPush (issue #17) the process is routinely dead
 * between pushes, so persisted cursors are what make each wakeup a cheap delta.
 * Value format: "queryState\nemailState".
 */
class SyncStateStore(context: Context) : SyncCursorStore {
    private val prefs = context.getSharedPreferences("sync_states", Context.MODE_PRIVATE)

    init {
        // v2: folder queries went uncollapsed (collapseThreads=false). A queryState minted by
        // the old collapsed query describes a DIFFERENT query — an Email/queryChanges against
        // it can silently omit thread members that predate the cursor, so the cache would
        // never backfill them. Drop the old cursors once; the next sync does a full re-query.
        if (prefs.getInt(VERSION_KEY, 1) < CURSOR_VERSION) clear()
    }

    override fun save(key: String, queryState: String, emailState: String) {
        prefs.edit().putString(key, "$queryState\n$emailState").apply()
    }

    /** The stored (queryState, emailState) pair, or null. */
    override fun load(key: String): Pair<String, String>? {
        val raw = prefs.getString(key, null) ?: return null
        val split = raw.split('\n', limit = 2)
        return if (split.size == 2) split[0] to split[1] else null
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().putInt(VERSION_KEY, CURSOR_VERSION).apply()
    }

    /** The cursor keys held, without the schema marker — which is not one and must never be
     *  handed to a per-account drop as if it were. */
    override fun keys(): Set<String> = prefs.all.keys - VERSION_KEY
}

/** Store schema marker; sync keys are "<localAccountId><mailboxId>", which can't collide. */
private const val VERSION_KEY = "cursor_version"
private const val CURSOR_VERSION = 2
