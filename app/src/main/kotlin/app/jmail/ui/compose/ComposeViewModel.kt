package app.jmail.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.jmap.model.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ComposeState {
    data object Idle : ComposeState
    data object Sending : ComposeState
    data object Done : ComposeState
    data class Error(val message: String) : ComposeState
}

/** Initial field values, e.g. for a reply or forward. */
data class DraftFields(val to: String, val subject: String, val body: String)

class ComposeViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    private val _state = MutableStateFlow<ComposeState>(ComposeState.Idle)
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    private val _prefill = MutableStateFlow<DraftFields?>(null)
    val prefill: StateFlow<DraftFields?> = _prefill.asStateFlow()

    private var prepared = false
    // Threading headers for a reply (empty for new/forward).
    private var inReplyTo: List<String> = emptyList()
    private var references: List<String> = emptyList()

    /** Build initial fields when opening as a reply/reply-all/forward of [replyToId]. */
    fun prepare(replyToId: String?, mode: String?) {
        if (prepared) return
        prepared = true
        if (replyToId == null) return
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: return@launch
                val original = repo.fetchEmail(credentials, replyToId)
                _prefill.value = buildPrefill(original, mode, credentials.username)
                if (mode != "forward") {
                    inReplyTo = original.messageId
                    references = original.references + original.messageId
                }
            } catch (_: Throwable) {
                // Leave fields blank if the original can't be loaded.
            }
        }
    }

    fun send(to: String, subject: String, body: String) =
        submit(to) { credentials, recipients ->
            repo.send(credentials, recipients, subject, body, inReplyTo, references)
        }

    fun saveDraft(to: String, subject: String, body: String) =
        submit(to) { credentials, recipients -> repo.saveDraft(credentials, recipients, subject, body) }

    private inline fun submit(
        to: String,
        crossinline op: suspend (AccountCredentials, List<String>) -> Unit,
    ) {
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: error("No saved account.")
                val recipients = to.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                op(credentials, recipients)
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun buildPrefill(original: Email, mode: String?, self: String): DraftFields = when (mode) {
        "forward" -> DraftFields(
            to = "",
            subject = withPrefix(original.subject, "Fwd:"),
            body = forwardBody(original),
        )
        "replyAll" -> DraftFields(
            to = replyAllRecipients(original, self),
            subject = withPrefix(original.subject, "Re:"),
            body = quote(original),
        )
        else -> DraftFields( // reply
            to = replyRecipient(original),
            subject = withPrefix(original.subject, "Re:"),
            body = quote(original),
        )
    }

    private fun replyRecipient(o: Email): String =
        o.from.firstOrNull()?.email.orEmpty()

    private fun replyAllRecipients(o: Email, self: String): String {
        val all = (listOf(replyRecipient(o)) + o.to.map { it.email } + o.cc.map { it.email })
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(self, ignoreCase = true) }
            .distinct()
        return all.joinToString(", ")
    }

    private fun withPrefix(subject: String?, prefix: String): String {
        val s = subject.orEmpty()
        return if (s.startsWith(prefix, ignoreCase = true)) s else "$prefix $s"
    }

    private fun quote(o: Email): String {
        val sender = o.from.firstOrNull()?.display() ?: "someone"
        val text = (o.textContent() ?: o.preview).orEmpty()
        val quoted = text.lineSequence().joinToString("\n") { "> $it" }
        return "\n\nOn ${o.receivedAt.orEmpty()}, $sender wrote:\n$quoted"
    }

    private fun forwardBody(o: Email): String {
        val from = o.from.joinToString { it.display() }
        val text = (o.textContent() ?: o.preview).orEmpty()
        return "\n\n---------- Forwarded message ----------\n" +
            "From: $from\nSubject: ${o.subject.orEmpty()}\n\n$text"
    }
}
