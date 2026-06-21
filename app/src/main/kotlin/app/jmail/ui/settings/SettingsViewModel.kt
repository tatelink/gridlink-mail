package app.jmail.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.jmail.container
import app.jmail.push.PushService
import app.jmail.security.canAuthenticate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val appLock = application.container.appLock

    private val _pushAllAccounts = MutableStateFlow(store.pushAllAccounts())
    val pushAllAccounts = _pushAllAccounts.asStateFlow()

    private val _appLock = MutableStateFlow(store.appLockEnabled())
    val appLockEnabled = _appLock.asStateFlow()

    /** Set when the user tries to enable app lock but no biometric / screen lock exists. */
    private val _appLockUnavailable = MutableStateFlow(false)
    val appLockUnavailable = _appLockUnavailable.asStateFlow()

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
}
