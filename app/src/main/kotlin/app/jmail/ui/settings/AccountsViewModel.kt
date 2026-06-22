package app.jmail.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.data.account.StoredAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Backs the Accounts list and per-account detail screens in Settings. */
class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val storage = application.container.storageRepository
    private val mail = application.container.mailRepository

    private val _accounts = MutableStateFlow(store.accounts())
    val accounts = _accounts.asStateFlow()

    private val _currentId = MutableStateFlow(store.currentId())
    val currentId = _currentId.asStateFlow()

    /** Re-read the store after any change so the UI reflects the latest state. */
    fun refresh() {
        _accounts.value = store.accounts()
        _currentId.value = store.currentId()
    }

    fun account(id: String): StoredAccount? = store.account(id)

    fun switchTo(id: String) {
        store.setCurrent(id)
        refresh()
    }

    /** Persist edits. A blank [password] keeps the existing one. */
    fun save(id: String, accountName: String, server: String, username: String, password: String) {
        store.updateAccount(id, server = server, username = username, accountName = accountName)
        if (password.isNotBlank()) store.updatePassword(id, password)
        refresh()
    }

    /** Sign out and purge that account's cached mail + the attachment cache. */
    fun signOut(id: String) {
        store.remove(id)
        refresh()
        mail.resetSyncState()
        viewModelScope.launch { storage.purgeAccount(id) }
    }
}
