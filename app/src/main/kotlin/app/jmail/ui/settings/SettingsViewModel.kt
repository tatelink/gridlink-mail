package app.jmail.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.jmail.container
import app.jmail.push.PushService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore

    private val _pushAllAccounts = MutableStateFlow(store.pushAllAccounts())
    val pushAllAccounts = _pushAllAccounts.asStateFlow()

    fun setPushAllAccounts(value: Boolean) {
        store.setPushAllAccounts(value)
        _pushAllAccounts.value = value
        // Reconnect push with the new scope.
        PushService.start(getApplication())
    }
}
