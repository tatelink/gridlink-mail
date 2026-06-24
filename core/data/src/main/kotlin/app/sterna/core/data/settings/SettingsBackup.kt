package app.sterna.core.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A portable snapshot of the app's preferences — everything in the settings
 * [DataStore][SettingsRepository] plus the cross-cutting push scope and UI
 * language. Deliberately excludes accounts and credentials: passwords are sealed
 * with a device-bound AndroidKeyStore key, so they cannot be meaningfully moved
 * to another device. Every field is nullable so a backup written by an older or
 * newer build still imports — unknown keys are ignored and absent ones left as-is.
 */
@Serializable
data class SettingsBackup(
    val version: Int = 1,
    val themeMode: String? = null,
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
)

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
