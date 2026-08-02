package app.sterna.core.jmap.model

import app.sterna.core.jmap.Jmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Ids per request for a server that advertises no usable per-call object limit. Deliberately
 * smaller than any `maxObjectsInSet` / `maxObjectsInGet` we have measured (Stalwart: 500 for both):
 * sending too few ids per request is merely slow, sending too many is the server rejecting the
 * WHOLE request (RFC 8620 §5.1) — a select-all, or its Undo, that fails in one block.
 */
const val JMAP_BATCH_FALLBACK = 100

/**
 * Hard cap on the ids we put in one request, whatever the server advertises. A server announcing a
 * huge (or absurd) limit still gets requests we can build, hold in memory and retry in a sane time.
 */
const val JMAP_BATCH_CEILING = 500

/**
 * The JMAP Session resource (RFC 8620 §2): capabilities and the URLs/accounts
 * the authenticated user can use.
 */
@Serializable
data class JmapSession(
    val capabilities: Map<String, JsonObject> = emptyMap(),
    val accounts: Map<String, JmapAccount> = emptyMap(),
    val primaryAccounts: Map<String, String> = emptyMap(),
    val username: String = "",
    val apiUrl: String,
    val downloadUrl: String? = null,
    val uploadUrl: String? = null,
    val eventSourceUrl: String? = null,
    val state: String? = null,
) {
    /** The primary mail account id, falling back to the first account available. */
    fun mailAccountId(): String? =
        primaryAccounts[Jmap.MAIL_CAPABILITY] ?: accounts.keys.firstOrNull()

    /**
     * Every mail-capable account in the session (RFC 8620 §1.6.2), primary first and the rest in
     * their map order. A single login can expose several (delegated / shared mailboxes, issue #31).
     * An account is mail-capable when it advertises the mail capability in its accountCapabilities;
     * servers that omit accountCapabilities entirely fall back to the primary mail account so this
     * degrades to the pre-multi-account behaviour of a single account.
     */
    fun mailAccountIds(): List<String> {
        val advertised = accounts.filterValues { it.accountCapabilities.containsKey(Jmap.MAIL_CAPABILITY) }.keys
        val ids = advertised.ifEmpty { listOfNotNull(mailAccountId()).toSet() }
        val primary = mailAccountId()
        return (listOfNotNull(primary?.takeIf { it in ids }) + ids.filterNot { it == primary }).distinct()
    }

    /**
     * The server's VAPID application key (RFC 9749), when advertised — passed to the
     * UnifiedPush registration; null for servers without VAPID (e.g. Stalwart today).
     */
    fun vapidPublicKey(): String? =
        (capabilities[Jmap.WEBPUSH_VAPID_CAPABILITY]?.get("applicationServerKey") as? JsonPrimitive)
            ?.contentOrNull

    /**
     * Ids one `Email/set` may carry: the server's `maxObjectsInSet` (RFC 8620 §2, CORE
     * capability), guarded and capped by [advertisedLimit].
     */
    fun setBatchSize(): Int = advertisedLimit("maxObjectsInSet")

    /**
     * Ids one `Email/get` may ask for: the server's `maxObjectsInGet` (RFC 8620 §2, CORE
     * capability), read exactly like [setBatchSize] but from its OWN property — a server advertises
     * the two separately and may well differ, a get carrying whole messages where a set carries
     * ids. Sizing one call with the other's limit is the bug this twin exists to make impossible.
     *
     * The Undo of a bulk move re-fetches every id it moved back ([MailRepository.restoreAll]), and
     * past this limit the server rejects the WHOLE call: the mail is back on the server and gone
     * from the list, with nothing to say so.
     */
    fun getBatchSize(): Int = advertisedLimit("maxObjectsInGet")

    /**
     * A per-call object limit of the CORE capability, guarded: absent, non-numeric, zero or
     * negative falls back to [JMAP_BATCH_FALLBACK], anything above [JMAP_BATCH_CEILING] is capped.
     * A value SMALLER than the fallback is respected as-is — that is the server's limit, not a
     * suggestion, and exceeding it fails the whole request. Read as a `Long` on purpose: a server
     * advertising a value past `Int.MAX_VALUE` must be capped, not wrapped back onto the fallback.
     */
    private fun advertisedLimit(property: String): Int {
        val announced = (capabilities[Jmap.CORE_CAPABILITY]?.get(property) as? JsonPrimitive)
            ?.longOrNull?.takeIf { it > 0 }
        return (announced ?: JMAP_BATCH_FALLBACK.toLong()).coerceAtMost(JMAP_BATCH_CEILING.toLong()).toInt()
    }
}

@Serializable
data class JmapAccount(
    val name: String,
    val isPersonal: Boolean = false,
    val isReadOnly: Boolean = false,
    /**
     * The data types this account exposes (RFC 8620 §2), e.g. the mail capability for a mailbox.
     * Used to tell mail accounts apart from contacts/calendar-only accounts in a shared session.
     */
    val accountCapabilities: Map<String, JsonObject> = emptyMap(),
)
