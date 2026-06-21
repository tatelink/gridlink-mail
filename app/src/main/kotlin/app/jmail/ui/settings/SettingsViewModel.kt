package app.jmail.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.data.settings.ListDensity
import app.jmail.core.data.settings.SwipeAction
import app.jmail.core.data.settings.ThemeMode
import app.jmail.push.PushService
import app.jmail.security.canAuthenticate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun setPushAllAccounts(value: Boolean) {
        store.setPushAllAccounts(value)
        _pushAllAccounts.value = value
        // Reconnect push with the new scope.
        PushService.start(getApplication())
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

    fun setSwipeRight(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeRightAction(action) }
    }

    fun setSwipeLeft(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeLeftAction(action) }
    }
}
