package app.jmail.send

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped hold-back for outgoing mail (Undo-send). A queued send is held for a
 * few seconds — surfaced as a cancellable "Undo" affordance in the UI — then run on
 * the app scope, so it survives the compose screen closing. A send that fails after
 * the window is reported via [failure].
 */
class SendOutbox(private val scope: CoroutineScope) {
    data class Pending(val token: Long, val label: String)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private var lastJob: Job? = null
    private var counter = 0L

    /** Hold [send] for [holdMs] (cancellable via [undo]), then run it. */
    fun enqueue(label: String, holdMs: Long = HOLD_MS, send: suspend () -> Unit) {
        val token = ++counter
        _pending.value = Pending(token, label)
        lastJob = scope.launch {
            delay(holdMs) // cancelled by undo() → send never runs
            if (_pending.value?.token == token) _pending.value = null
            runCatching { send() }.onFailure {
                _failure.value = it.message ?: it.javaClass.simpleName
            }
        }
    }

    /** Cancel the most recent pending send (the one the Undo affordance refers to). */
    fun undo() {
        lastJob?.cancel()
        _pending.value = null
    }

    fun consumeFailure() {
        _failure.value = null
    }

    private companion object {
        const val HOLD_MS = 5_000L
    }
}
