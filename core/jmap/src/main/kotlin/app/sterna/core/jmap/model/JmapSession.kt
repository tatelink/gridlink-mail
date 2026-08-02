package app.sterna.core.jmap.model

import app.sterna.core.jmap.Jmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Ids per `Email/set` for a server that advertises nothing usable. Deliberately smaller than any
 * `maxObjectsInSet` we have measured (Stalwart: 500): sending too few ids per request is merely
 * slow, sending too many is the server rejecting the WHOLE request — a select-all that fails in
 * one block.
 */
const val SET_BATCH_FALLBACK = 100

/**
 * Hard cap on the ids we put in one `Email/set`, whatever the server advertises. A server
 * announcing a huge (or absurd) `maxObjectsInSet` still gets requests we can build, hold in
 * memory and retry in a sane time.
 */
const val SET_BATCH_CEILING = 500

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
     * capability), capped at [SET_BATCH_CEILING]. A server advertising nothing usable (capability
     * absent, property absent, zero, negative, unparsable) gets [SET_BATCH_FALLBACK].
     *
     * A value SMALLER than the fallback is respected as-is: that is the server's limit, not a
     * suggestion, and exceeding it fails the whole request. Read as a `Long` on purpose — a server
     * advertising a value past `Int.MAX_VALUE` must be capped, not wrapped back onto the fallback.
     */
    fun setBatchSize(): Int {
        val announced = (capabilities[Jmap.CORE_CAPABILITY]?.get("maxObjectsInSet") as? JsonPrimitive)
            ?.longOrNull?.takeIf { it > 0 }
        return (announced ?: SET_BATCH_FALLBACK.toLong()).coerceAtMost(SET_BATCH_CEILING.toLong()).toInt()
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
