package app.gridlink.core.data.db

import app.gridlink.core.jmap.model.EmailAddress
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * (De)serialises the `To:` recipients stored in [EmailEntity.recipientsJson].
 *
 * Same mechanism as the other structured lists the cache persists ([OutboxAttachments] for
 * `outbox.attachmentsJson`, the serialized [app.gridlink.core.jmap.model.Email] in
 * `email_bodies.bodyJson`): kotlinx JSON of the model type itself, so there is exactly one way
 * to store a list of addresses. [EmailAddress] is already `@Serializable`.
 *
 * Both directions are total: an empty list encodes to null (nothing to store), and a null,
 * blank or unparseable column decodes to an empty list — a row cached before the v17 column
 * existed simply has no recipients and falls back to showing its sender.
 */
object EmailRecipients {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(EmailAddress.serializer())

    fun encode(addresses: List<EmailAddress>): String? =
        if (addresses.isEmpty()) null else json.encodeToString(serializer, addresses)

    fun decode(raw: String?): List<EmailAddress> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
