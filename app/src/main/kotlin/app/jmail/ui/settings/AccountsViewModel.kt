package app.jmail.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.jmail.container
import app.jmail.core.data.account.StoredAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Backs the Accounts list and per-account detail screens in Settings. */
class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore

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

    fun signOut(id: String) {
        store.remove(id)
        refresh()
    }
}
