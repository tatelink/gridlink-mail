package app.gridlink.core.data.settings

import app.gridlink.core.data.account.StoredAccount
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A portable snapshot of the app's preferences — everything in the settings
 * [DataStore][SettingsRepository] plus the cross-cutting push scope and UI
 * language, and the account *configuration* (see [accounts]).
 *
 * Still excludes every credential: passwords and OAuth refresh tokens are sealed
 * with a device-bound AndroidKeyStore key, so they cannot be meaningfully moved to
 * another device. Imported accounts therefore arrive without a secret and stay
 * inert until the user signs in (enters the password in Accounts). Every field is
 * nullable so a backup written by an older or newer build still imports — unknown
 * keys are ignored and absent ones left as-is.
 */
@Serializable
data class SettingsBackup(
    val version: Int = 1,
    val themeMode: String? = null,
    val dynamicColor: Boolean? = null,
    val listDensity: String? = null,
    val previewLines: String? = null,
    val swipeRight: String? = null,
    val swipeLeft: String? = null,
    val sortOrder: String? = null,
    val contactSuggestions: Boolean? = null,
    val stripTracking: Boolean? = null,
    val confirmLinks: Boolean? = null,
    val imageAllowlist: List<String>? = null,
    val quietHoursEnabled: Boolean? = null,
    val quietHoursStart: Int? = null,
    val quietHoursEnd: Int? = null,
    val pushAllAccounts: Boolean? = null,
    /** App-locale language tag ("" = follow system). */
    val language: String? = null,
    val conversationView: Boolean? = null,
    /** [MessageTextSize] name. */
    val messageTextSize: String? = null,
    val markReadOnDelete: Boolean? = null,
    val markReadOnArchive: Boolean? = null,
    val markReadOnMove: Boolean? = null,
    val unarchiveOnReply: Boolean? = null,
    val signatureOnReplies: Boolean? = null,
    val signatureBelowQuote: Boolean? = null,
    /** Whether the composer adds the standard "-- " delimiter line above the signature (#90). */
    val signatureDelimiter: Boolean? = null,
    /** [DeliveryMode] name (Instant / Battery saver). */
    val deliveryMode: String? = null,
    val notificationContent: String? = null,
    /**
     * Account configuration WITHOUT any secret: server, username, protocol, IMAP/SMTP
     * endpoints, identities, signature, colour, sync window, notification opt-out. The
     * password/refresh-token slot is never exported; [StoredAccount.oauthAccessToken] and
     * device/sync state (inbox id, unread) are cleared before export. See AccountStore.
     */
    val accounts: List<StoredAccount>? = null,
) {
    /**
     * True when this decoded to something that actually looks like a Gridlink backup — at least one
     * known field is present. An unrelated JSON file decodes (unknown keys ignored) to an all-null
     * instance; treat that as invalid rather than silently "importing nothing".
     */
    fun isPlausible(): Boolean =
        themeMode != null || dynamicColor != null || listDensity != null || previewLines != null ||
            swipeRight != null || swipeLeft != null || sortOrder != null || contactSuggestions != null ||
            stripTracking != null || confirmLinks != null || imageAllowlist != null ||
            quietHoursEnabled != null || quietHoursStart != null || quietHoursEnd != null ||
            pushAllAccounts != null || language != null || conversationView != null ||
            messageTextSize != null || markReadOnDelete != null || accounts != null
}

/** JSON (de)serialization for [SettingsBackup] export/import files. */
object SettingsBackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(backup: SettingsBackup): String =
        json.encodeToString(SettingsBackup.serializer(), backup)

    /** Parses a backup file; returns null if the text is not a valid backup. */
    fun decode(text: String): SettingsBackup? =
        runCatching { json.decodeFromString(SettingsBackup.serializer(), text) }.getOrNull()
}
