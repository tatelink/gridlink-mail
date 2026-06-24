package app.sterna.security

import app.sterna.core.data.account.AccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the app's lock state. When app lock is enabled the UI is hidden behind a
 * [LockScreen] until the user re-authenticates with the device biometric / PIN.
 *
 * The app locks on a cold start and whenever it returns to the foreground after
 * being in the background longer than [GRACE_MS] (a short window so a quick app
 * switch — e.g. the system file picker while attaching — doesn't demand a re-auth).
 */
class AppLock(private val store: AccountStore) {
    private val _locked = MutableStateFlow(store.appLockEnabled())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt = 0L

    val isEnabled: Boolean get() = store.appLockEnabled()

    /** Called when the user toggles the setting. Turning it off clears any lock. */
    fun setEnabled(enabled: Boolean) {
        store.setAppLockEnabled(enabled)
        if (!enabled) _locked.value = false
    }

    fun onAppBackgrounded(nowMs: Long) {
        backgroundedAt = nowMs
    }

    fun onAppForegrounded(nowMs: Long) {
        if (store.appLockEnabled() && nowMs - backgroundedAt >= GRACE_MS) {
            _locked.value = true
        }
    }

    fun unlock() {
        _locked.value = false
    }

    private companion object {
        const val GRACE_MS = 10_000L
    }
}
