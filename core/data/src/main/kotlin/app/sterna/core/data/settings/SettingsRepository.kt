package app.sterna.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the app picks light vs dark colours. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Vertical density of message-list rows. */
enum class ListDensity { COMPACT, NORMAL, SPACED }

/** How much of each message's body preview to show in the list. */
enum class PreviewLines(val lines: Int) { NONE(0), ONE(1), THREE(3), FIVE(5) }

/** An action bound to a swipe gesture on a message row. */
enum class SwipeAction { NONE, TOGGLE_READ, DELETE, ARCHIVE, FLAG }

/** How the message list is ordered. */
enum class SortOrder { DATE_DESC, DATE_ASC, SUBJECT, SENDER, UNREAD_FIRST }

/** Reading text size for the message body (WebView text zoom, in percent). */
enum class MessageTextSize(val zoom: Int) { SMALL(85), NORMAL(100), LARGE(125), HUGE(150) }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Reactive app-preferences store backed by a Preferences [DataStore]. Kept
 * separate from [app.sterna.core.data.account.AccountStore], which holds accounts,
 * credentials, and per-account metadata. Each setting is exposed as a [Flow] so
 * the UI (and the theme) can collect changes live.
 */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    /** Use Material You (wallpaper-derived) colours instead of Sterna's brand palette. Off by default. */
    val dynamicColor: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_COLOR] ?: false
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    val listDensity: Flow<ListDensity> = dataStore.data.map { prefs ->
        prefs[KEY_LIST_DENSITY]?.let { runCatching { ListDensity.valueOf(it) }.getOrNull() }
            ?: ListDensity.NORMAL
    }

    suspend fun setListDensity(density: ListDensity) {
        dataStore.edit { it[KEY_LIST_DENSITY] = density.name }
    }

    val previewLines: Flow<PreviewLines> = dataStore.data.map { prefs ->
        prefs[KEY_PREVIEW_LINES]?.let { runCatching { PreviewLines.valueOf(it) }.getOrNull() }
            ?: PreviewLines.ONE
    }

    suspend fun setPreviewLines(value: PreviewLines) {
        dataStore.edit { it[KEY_PREVIEW_LINES] = value.name }
    }

    val swipeRightAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_RIGHT, SwipeAction.TOGGLE_READ)
    val swipeLeftAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_LEFT, SwipeAction.DELETE)

    suspend fun setSwipeRightAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_RIGHT] = action.name }
    }

    suspend fun setSwipeLeftAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_LEFT] = action.name }
    }

    val sortOrder: Flow<SortOrder> = dataStore.data.map { prefs ->
        prefs[KEY_SORT_ORDER]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.DATE_DESC
    }

    suspend fun setSortOrder(order: SortOrder) {
        dataStore.edit { it[KEY_SORT_ORDER] = order.name }
    }

    /** Collapse threads into one conversation row in the list (on by default). */
    val conversationView: Flow<Boolean> = dataStore.data.map { it[KEY_CONVERSATION_VIEW] ?: true }

    suspend fun setConversationView(enabled: Boolean) {
        dataStore.edit { it[KEY_CONVERSATION_VIEW] = enabled }
    }

    /** Reading text size for the message body. */
    val messageTextSize: Flow<MessageTextSize> = dataStore.data.map { prefs ->
        prefs[KEY_MESSAGE_TEXT_SIZE]?.let { runCatching { MessageTextSize.valueOf(it) }.getOrNull() }
            ?: MessageTextSize.NORMAL
    }

    suspend fun setMessageTextSize(size: MessageTextSize) {
        dataStore.edit { it[KEY_MESSAGE_TEXT_SIZE] = size.name }
    }

    /** Whether recipient autocomplete may read the device's contacts (off by default). */
    val contactSuggestions: Flow<Boolean> = dataStore.data.map { it[KEY_CONTACT_SUGGESTIONS] ?: false }

    suspend fun setContactSuggestions(enabled: Boolean) {
        dataStore.edit { it[KEY_CONTACT_SUGGESTIONS] = enabled }
    }

    /** Whether to remove tracking query params (utm_*, fbclid, …) from tapped links (on by default). */
    val stripTrackingParams: Flow<Boolean> = dataStore.data.map { it[KEY_STRIP_TRACKING] ?: true }

    suspend fun setStripTrackingParams(enabled: Boolean) {
        dataStore.edit { it[KEY_STRIP_TRACKING] = enabled }
    }

    /** Whether to show the destination and ask before opening a tapped link (off by default). */
    val confirmLinks: Flow<Boolean> = dataStore.data.map { it[KEY_CONFIRM_LINKS] ?: false }

    suspend fun setConfirmLinks(enabled: Boolean) {
        dataStore.edit { it[KEY_CONFIRM_LINKS] = enabled }
    }

    /** Sender addresses (lower-cased) whose remote images load automatically. */
    val imageAllowlist: Flow<Set<String>> = dataStore.data.map { it[KEY_IMAGE_ALLOWLIST] ?: emptySet() }

    suspend fun setImageAllowed(sender: String, allowed: Boolean) {
        val key = sender.trim().lowercase()
        if (key.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_IMAGE_ALLOWLIST] ?: emptySet()
            prefs[KEY_IMAGE_ALLOWLIST] = if (allowed) current + key else current - key
        }
    }

    suspend fun clearImageAllowlist() {
        dataStore.edit { it.remove(KEY_IMAGE_ALLOWLIST) }
    }

    suspend fun setImageAllowlist(senders: Set<String>) {
        val cleaned = senders.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        dataStore.edit { it[KEY_IMAGE_ALLOWLIST] = cleaned }
    }

    /** Captures every DataStore-backed preference into a portable [SettingsBackup]. */
    suspend fun snapshotBackup(): SettingsBackup = SettingsBackup(
        themeMode = themeMode.first().name,
        dynamicColor = dynamicColor.first(),
        listDensity = listDensity.first().name,
        previewLines = previewLines.first().name,
        swipeRight = swipeRightAction.first().name,
        swipeLeft = swipeLeftAction.first().name,
        sortOrder = sortOrder.first().name,
        contactSuggestions = contactSuggestions.first(),
        stripTracking = stripTrackingParams.first(),
        confirmLinks = confirmLinks.first(),
        imageAllowlist = imageAllowlist.first().toList(),
        quietHoursEnabled = quietHoursEnabled.first(),
        quietHoursStart = quietHoursStart.first(),
        quietHoursEnd = quietHoursEnd.first(),
    )

    /** Applies the DataStore-backed fields of [backup]; unknown enum values are skipped. */
    suspend fun restoreBackup(backup: SettingsBackup) {
        backup.themeMode?.let { v -> runCatching { ThemeMode.valueOf(v) }.getOrNull()?.let { setThemeMode(it) } }
        backup.dynamicColor?.let { setDynamicColor(it) }
        backup.listDensity?.let { v -> runCatching { ListDensity.valueOf(v) }.getOrNull()?.let { setListDensity(it) } }
        backup.previewLines?.let { v -> runCatching { PreviewLines.valueOf(v) }.getOrNull()?.let { setPreviewLines(it) } }
        backup.swipeRight?.let { v -> runCatching { SwipeAction.valueOf(v) }.getOrNull()?.let { setSwipeRightAction(it) } }
        backup.swipeLeft?.let { v -> runCatching { SwipeAction.valueOf(v) }.getOrNull()?.let { setSwipeLeftAction(it) } }
        backup.sortOrder?.let { v -> runCatching { SortOrder.valueOf(v) }.getOrNull()?.let { setSortOrder(it) } }
        backup.contactSuggestions?.let { setContactSuggestions(it) }
        backup.stripTracking?.let { setStripTrackingParams(it) }
        backup.confirmLinks?.let { setConfirmLinks(it) }
        backup.imageAllowlist?.let { setImageAllowlist(it.toSet()) }
        backup.quietHoursEnabled?.let { setQuietHoursEnabled(it) }
        backup.quietHoursStart?.let { setQuietHoursStart(it) }
        backup.quietHoursEnd?.let { setQuietHoursEnd(it) }
    }

    /**
     * Quiet hours — when on, new-mail notifications still arrive but are posted
     * silently (no sound/vibration/heads-up) during the nightly window. Off by default.
     */
    val quietHoursEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_QUIET_ENABLED] ?: false }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_QUIET_ENABLED] = enabled }
    }

    /** Window start, minutes past midnight (default 22:00). */
    val quietHoursStart: Flow<Int> = dataStore.data.map { it[KEY_QUIET_START] ?: DEFAULT_QUIET_START }

    suspend fun setQuietHoursStart(minutes: Int) {
        dataStore.edit { it[KEY_QUIET_START] = minutes.coerceIn(0, 24 * 60 - 1) }
    }

    /** Window end, minutes past midnight (default 07:00). */
    val quietHoursEnd: Flow<Int> = dataStore.data.map { it[KEY_QUIET_END] ?: DEFAULT_QUIET_END }

    suspend fun setQuietHoursEnd(minutes: Int) {
        dataStore.edit { it[KEY_QUIET_END] = minutes.coerceIn(0, 24 * 60 - 1) }
    }

    private fun swipeFlow(key: Preferences.Key<String>, default: SwipeAction): Flow<SwipeAction> =
        dataStore.data.map { prefs ->
            prefs[key]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: default
        }

    companion object {
        const val DEFAULT_QUIET_START = 22 * 60
        const val DEFAULT_QUIET_END = 7 * 60

        /**
         * True if [nowMinutes] (minutes past midnight) falls in the quiet window
         * [start, end), handling windows that wrap past midnight (e.g. 22:00→07:00).
         * An empty window (start == end) is never quiet.
         */
        fun isWithinQuietHours(nowMinutes: Int, start: Int, end: Int): Boolean = when {
            start == end -> false
            start < end -> nowMinutes in start until end
            else -> nowMinutes >= start || nowMinutes < end
        }

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_LIST_DENSITY = stringPreferencesKey("list_density")
        private val KEY_PREVIEW_LINES = stringPreferencesKey("preview_lines")
        private val KEY_SWIPE_RIGHT = stringPreferencesKey("swipe_right")
        private val KEY_SWIPE_LEFT = stringPreferencesKey("swipe_left")
        private val KEY_SORT_ORDER = stringPreferencesKey("sort_order")
        private val KEY_CONVERSATION_VIEW = booleanPreferencesKey("conversation_view")
        private val KEY_MESSAGE_TEXT_SIZE = stringPreferencesKey("message_text_size")
        private val KEY_CONTACT_SUGGESTIONS = booleanPreferencesKey("contact_suggestions")
        private val KEY_STRIP_TRACKING = booleanPreferencesKey("strip_tracking_params")
        private val KEY_CONFIRM_LINKS = booleanPreferencesKey("confirm_links")
        private val KEY_IMAGE_ALLOWLIST = stringSetPreferencesKey("image_allowlist")
        private val KEY_QUIET_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        private val KEY_QUIET_START = intPreferencesKey("quiet_hours_start")
        private val KEY_QUIET_END = intPreferencesKey("quiet_hours_end")
    }
}
