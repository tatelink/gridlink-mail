package app.jmail.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.data.settings.ListDensity
import app.jmail.core.data.settings.PreviewLines
import app.jmail.core.data.settings.SettingsBackup
import app.jmail.core.data.settings.SettingsBackupCodec
import app.jmail.core.data.settings.SettingsRepository
import app.jmail.core.data.settings.SwipeAction
import app.jmail.core.data.settings.ThemeMode
import app.jmail.push.PushService
import app.jmail.security.canAuthenticate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val appLock = application.container.appLock
    private val settings = application.container.settingsRepository

    private val _pushAllAccounts = MutableStateFlow(store.pushAllAccounts())
    val pushAllAccounts = _pushAllAccounts.asStateFlow()

    private val _appLock = MutableStateFlow(store.appLockEnabled())
    val appLockEnabled = _appLock.asStateFlow()

    /** Set when the user tries to enable app lock but no biometric / screen lock exists. */
    private val _appLockUnavailable = MutableStateFlow(false)
    val appLockUnavailable = _appLockUnavailable.asStateFlow()

    val themeMode = settings.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.SYSTEM,
    )

    val listDensity = settings.listDensity.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ListDensity.NORMAL,
    )

    val previewLines = settings.previewLines.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PreviewLines.ONE,
    )

    val swipeRight = settings.swipeRightAction.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SwipeAction.TOGGLE_READ,
    )

    val swipeLeft = settings.swipeLeftAction.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SwipeAction.DELETE,
    )

    val contactSuggestions = settings.contactSuggestions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setContactSuggestions(value: Boolean) {
        viewModelScope.launch { settings.setContactSuggestions(value) }
    }

    val stripTrackingParams = settings.stripTrackingParams.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setStripTrackingParams(value: Boolean) {
        viewModelScope.launch { settings.setStripTrackingParams(value) }
    }

    val confirmLinks = settings.confirmLinks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setConfirmLinks(value: Boolean) {
        viewModelScope.launch { settings.setConfirmLinks(value) }
    }

    val imageAllowlist = settings.imageAllowlist.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet(),
    )

    fun clearImageAllowlist() {
        viewModelScope.launch { settings.clearImageAllowlist() }
    }

    fun setPushAllAccounts(value: Boolean) {
        store.setPushAllAccounts(value)
        _pushAllAccounts.value = value
        // Reconnect push with the new scope.
        PushService.start(getApplication())
    }

    val quietHoursEnabled = settings.quietHoursEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    val quietHoursStart = settings.quietHoursStart.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsRepository.DEFAULT_QUIET_START,
    )

    val quietHoursEnd = settings.quietHoursEnd.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsRepository.DEFAULT_QUIET_END,
    )

    fun setQuietHoursEnabled(value: Boolean) {
        viewModelScope.launch { settings.setQuietHoursEnabled(value) }
    }

    fun setQuietHoursStart(minutes: Int) {
        viewModelScope.launch { settings.setQuietHoursStart(minutes) }
    }

    fun setQuietHoursEnd(minutes: Int) {
        viewModelScope.launch { settings.setQuietHoursEnd(minutes) }
    }

    /**
     * Writes a JSON snapshot of the app's preferences (not accounts/credentials)
     * to [uri]. [onResult] is invoked on the main thread with success/failure.
     */
    fun exportSettings(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                val backup = settings.snapshotBackup().copy(
                    pushAllAccounts = store.pushAllAccounts(),
                    language = currentAppLanguage().tag,
                )
                val text = SettingsBackupCodec.encode(backup)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(text.toByteArray())
                    } ?: error("no output stream")
                }
            }.isSuccess
            onResult(ok)
        }
    }

    /**
     * Reads a backup file from [uri] and applies it. [onResult] reports success;
     * [onLanguageChanged] fires (with the new language) only when the language
     * differs, so the caller can recreate the activity to load the new strings.
     */
    fun importSettings(uri: Uri, onResult: (Boolean) -> Unit, onLanguageChanged: (AppLanguage) -> Unit) {
        viewModelScope.launch {
            val backup: SettingsBackup? = runCatching {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("no input stream")
                }
                SettingsBackupCodec.decode(text)
            }.getOrNull()
            if (backup == null) {
                onResult(false)
                return@launch
            }
            settings.restoreBackup(backup)
            backup.pushAllAccounts?.let { setPushAllAccounts(it) }
            _pushAllAccounts.value = store.pushAllAccounts()
            onResult(true)
            backup.language?.let { tag ->
                val target = AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
                if (target != currentAppLanguage()) onLanguageChanged(target)
            }
        }
    }

    fun setAppLock(value: Boolean) {
        if (value && !canAuthenticate(getApplication())) {
            _appLockUnavailable.value = true
            _appLock.value = false
            return
        }
        _appLockUnavailable.value = false
        appLock.setEnabled(value)
        _appLock.value = value
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setListDensity(density: ListDensity) {
        viewModelScope.launch { settings.setListDensity(density) }
    }

    fun setPreviewLines(value: PreviewLines) {
        viewModelScope.launch { settings.setPreviewLines(value) }
    }

    fun setSwipeRight(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeRightAction(action) }
    }

    fun setSwipeLeft(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeLeftAction(action) }
    }

    val conversationView = settings.conversationView.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setConversationView(enabled: Boolean) {
        viewModelScope.launch { settings.setConversationView(enabled) }
    }
}
