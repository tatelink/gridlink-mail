package app.sterna.core.data.account

import kotlinx.serialization.Serializable

/**
 * Per-account "messages to sync" window — how much of a mailbox to keep cached,
 * either by recency ([maxAgeDays]) or by message [limit] (ARCHITECTURE.md →
 * "Retention & eviction"). Age windows still cap the fetch with [limit].
 *
 * [limit] counts MESSAGES, not threads: folder syncs are uncollapsed (the cache is
 * WYSIWYG and holds every thread member), so a window now matches its "N messages"
 * settings label exactly. A folder whose newest N messages collapse into few
 * conversations is not truncated to those: the scroll mediator pages older mail in
 * on demand (thread-aware fill), the window only bounds what stays cached offline.
 */
@Serializable
enum class SyncWindow(val limit: Int, val maxAgeDays: Int?) {
    DAYS_30(limit = 200, maxAgeDays = 30),
    DAYS_90(limit = 200, maxAgeDays = 90),
    YEAR_1(limit = 500, maxAgeDays = 365),
    COUNT_50(limit = 50, maxAgeDays = null),
    COUNT_200(limit = 200, maxAgeDays = null),
    COUNT_500(limit = 500, maxAgeDays = null),
    ALL(limit = 1000, maxAgeDays = null),
}
