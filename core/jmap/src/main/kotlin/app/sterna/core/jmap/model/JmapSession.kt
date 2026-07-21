package app.sterna.core.jmap.model

import app.sterna.core.jmap.Jmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
