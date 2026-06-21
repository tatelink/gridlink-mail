package app.jmail.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How the app picks light vs dark colours. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Vertical density of message-list rows. */
enum class ListDensity { COMPACT, NORMAL, SPACED }

/** An action bound to a swipe gesture on a message row. */
enum class SwipeAction { NONE, TOGGLE_READ, DELETE, ARCHIVE, FLAG }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Reactive app-preferences store backed by a Preferences [DataStore]. Kept
 * separate from [app.jmail.core.data.account.AccountStore], which holds accounts,
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

    val listDensity: Flow<ListDensity> = dataStore.data.map { prefs ->
        prefs[KEY_LIST_DENSITY]?.let { runCatching { ListDensity.valueOf(it) }.getOrNull() }
            ?: ListDensity.NORMAL
    }

    suspend fun setListDensity(density: ListDensity) {
        dataStore.edit { it[KEY_LIST_DENSITY] = density.name }
    }

    val swipeRightAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_RIGHT, SwipeAction.TOGGLE_READ)
    val swipeLeftAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_LEFT, SwipeAction.DELETE)

    suspend fun setSwipeRightAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_RIGHT] = action.name }
    }

    suspend fun setSwipeLeftAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_LEFT] = action.name }
    }

    private fun swipeFlow(key: Preferences.Key<String>, default: SwipeAction): Flow<SwipeAction> =
        dataStore.data.map { prefs ->
            prefs[key]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: default
        }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_LIST_DENSITY = stringPreferencesKey("list_density")
        val KEY_SWIPE_RIGHT = stringPreferencesKey("swipe_right")
        val KEY_SWIPE_LEFT = stringPreferencesKey("swipe_left")
    }
}
