package app.jmail.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jmail.container
import app.jmail.ui.connect.ConnectScreen
import app.jmail.ui.inbox.InboxScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Top-level route: do we have a saved account or not. */
sealed interface RootState {
    data object Loading : RootState
    data object NeedAccount : RootState
    data object Authenticated : RootState
}

class RootViewModel(application: Application) : AndroidViewModel(application) {
    private val accountStore = application.container.accountStore

    private val _state = MutableStateFlow<RootState>(RootState.Loading)
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = if (accountStore.hasAccount()) RootState.Authenticated else RootState.NeedAccount
    }

    fun signOut() {
        accountStore.clear()
        _state.value = RootState.NeedAccount
    }
}

@Composable
fun JmailApp(viewModel: RootViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (state) {
        RootState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        RootState.NeedAccount -> ConnectScreen(onConnected = viewModel::refresh)
        RootState.Authenticated -> InboxScreen(onSignOut = viewModel::signOut)
    }
}
