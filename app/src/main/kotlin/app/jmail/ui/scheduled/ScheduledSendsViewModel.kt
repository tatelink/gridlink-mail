package app.jmail.ui.scheduled

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.data.db.ScheduledSendEntity
import app.jmail.send.ScheduledSends
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the "Scheduled" screen: lists pending scheduled sends and cancels them. */
class ScheduledSendsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository

    val items = repo.scheduledSendsFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList<ScheduledSendEntity>(),
    )

    /** Cancel a scheduled send: drop its WorkManager job and delete the queued row. */
    fun cancel(id: Long) {
        ScheduledSends.cancel(getApplication(), id)
        viewModelScope.launch { repo.deleteScheduledSend(id) }
    }
}
