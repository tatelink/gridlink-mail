package app.sterna.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.MessageTextSize
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.data.settings.SettingsBackup
import app.sterna.core.data.settings.SettingsBackupCodec
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.data.settings.DeliveryMode
import app.sterna.push.NewMailNotifier
import app.sterna.push.PushController
import app.sterna.security.canAuthenticate
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

    val dynamicColor = settings.dynamicColor.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
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

    fun setImageAllowed(sender: String, allowed: Boolean) {
        viewModelScope.launch { settings.setImageAllowed(sender, allowed) }
    }

    /** New-mail delivery: Instant / Battery saver (issue #17, the ONE outcome setting). */
    val deliveryMode = settings.deliveryMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DeliveryMode.INSTANT,
    )

    fun setDeliveryMode(mode: DeliveryMode) {
        viewModelScope.launch {
            settings.setDeliveryMode(mode)
            // Re-arm with the new outcome (stops or restarts the foreground service).
            PushController.apply(getApplication(), userInitiated = true)
        }
    }

    fun setPushAllAccounts(value: Boolean) {
        store.setPushAllAccounts(value)
        _pushAllAccounts.value = value
        if (value) {
            // Accounts (re)entering the watched scope kept frozen baselines while out of
            // it; diffing those would burst stale notifications — drop them so the first
            // pass reseeds silently.
            store.allCredentials().filter { it.id != store.currentId() }
                .forEach { NewMailNotifier.clear(getApplication(), it.id) }
        }
        // Reconnect push with the new scope.
        PushController.apply(getApplication(), userInitiated = true)
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
     * Writes a JSON snapshot of the app's preferences and account configuration
     * (never credentials) to [uri]. [onResult] is invoked on the main thread with
     * success/failure.
     */
    fun exportSettings(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                val backup = settings.snapshotBackup().copy(
                    pushAllAccounts = store.pushAllAccounts(),
                    language = currentAppLanguage().tag,
                    accounts = store.accountsForBackup(),
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
     * Reads a backup file from [uri] and applies it. [onResult] reports success plus how many
     * accounts were newly added (0 if none/failure) — imported accounts have no password and must
     * be signed into. [onLanguageChanged] fires (with the new language) only when the language
     * differs, so the caller can recreate the activity to load the new strings.
     */
    fun importSettings(
        uri: Uri,
        onResult: (ok: Boolean, accountsAdded: Int) -> Unit,
        onLanguageChanged: (AppLanguage) -> Unit,
    ) {
        viewModelScope.launch {
            val backup: SettingsBackup? = runCatching {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("no input stream")
                }
                SettingsBackupCodec.decode(text)
            }.getOrNull()
            // Reject a file that isn't parseable, or that parses but carries no recognizable backup
            // fields (an unrelated JSON) — the caller shows an error and stays on its menu.
            if (backup == null || !backup.isPlausible()) {
                onResult(false, 0)
                return@launch
            }
            settings.restoreBackup(backup)
            backup.pushAllAccounts?.let { setPushAllAccounts(it) }
            _pushAllAccounts.value = store.pushAllAccounts()
            val accountsAdded = backup.accounts?.let { store.importAccounts(it) } ?: 0
            onResult(true, accountsAdded)
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

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
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

    val markReadOnDelete = settings.markReadOnDelete.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setMarkReadOnDelete(enabled: Boolean) {
        viewModelScope.launch { settings.setMarkReadOnDelete(enabled) }
    }

    val messageTextSize = settings.messageTextSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MessageTextSize.NORMAL,
    )

    fun setMessageTextSize(size: MessageTextSize) {
        viewModelScope.launch { settings.setMessageTextSize(size) }
    }
}
