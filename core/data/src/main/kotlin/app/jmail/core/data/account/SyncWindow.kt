package app.jmail.core.data.account

import kotlinx.serialization.Serializable

/**
 * Per-account "messages to sync" window — how much of a mailbox to keep cached,
 * either by recency ([maxAgeDays]) or by message [limit] (ARCHITECTURE.md →
 * "Retention & eviction"). Age windows still cap the fetch with [limit].
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
