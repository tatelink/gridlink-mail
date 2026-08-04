package app.sterna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.filter.FilterScriptWarning
import app.sterna.core.data.filter.refreshedFilterWarning
import app.sterna.core.data.mail.VacationState
import app.sterna.core.jmap.model.VacationResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** Which transient error (if any) the vacation screen should surface. */
enum class VacationError { LOAD, SAVE, INVALID_DATES }

/**
 * UI state for the vacation responder. Dates are held as epoch-millis at UTC
 * midnight of the chosen day (what Material's date picker speaks); they are
 * converted to/from RFC 8621 UTCDate strings at the JMAP boundary.
 */
data class VacationUiState(
    val loading: Boolean = true,
    val noAccount: Boolean = false,
    val supported: Boolean = true,
    val accountLabel: String = "",
    val enabled: Boolean = false,
    val subject: String = "",
    val message: String = "",
    val fromDate: Long? = null,
    val toDate: Long? = null,
    val saving: Boolean = false,
    val errorKind: VacationError? = null,
    val errorDetail: String = "",
    /** Bumped after each successful save; the screen shows a confirmation while > 0. */
    val savedTick: Int = 0,
    /**
     * Whether the edited fields still differ from what the server holds. Gates Save, so the button
     * is offered only when it has something to push (#34), and goes out again if the edits are
     * undone by hand.
     */
    val dirty: Boolean = false,
    /**
     * What the account's Sieve scripts say about the user's filter rules, or null for nothing to
     * say. Read after the responder itself, and never part of [edits]: it describes the server,
     * not the form, so it can neither be edited nor saved.
     */
    val filterWarning: FilterScriptWarning? = null,
) {
    /** The fields a Save actually writes — what "changed" is measured on. */
    internal fun edits(): List<Any?> = listOf(enabled, subject, message, fromDate, toDate)
}

/**
 * Drives the server-side vacation responder (JMAP VacationResponse) for the
 * current account. Unlike the local DataStore-backed settings, every read/write
 * is a network round-trip, so the state carries explicit loading/saving/error.
 */
class VacationViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val store = application.container.accountStore

    private val _state = MutableStateFlow(VacationUiState())
    val state = _state.asStateFlow()

    /** The responder as the server last confirmed it; edits are compared to this (#34). */
    private var serverEdits: List<Any?> = VacationUiState().edits()

    init { load() }

    fun load() {
        val credentials = store.load()
        if (credentials == null) {
            _state.value = VacationUiState(loading = false, noAccount = true)
            return
        }
        _state.update { it.copy(loading = true, errorKind = null) }
        viewModelScope.launch {
            try {
                when (val result = repo.loadVacation(credentials)) {
                    VacationState.Unsupported ->
                        _state.value = VacationUiState(
                            loading = false,
                            supported = false,
                            accountLabel = store.accountLabel(),
                        )
                    is VacationState.Loaded -> {
                        val r = result.response
                        // BEFORE the form is published, not after. The warning this screen carries
                        // is about the switch three rows below it: published late, it arrives after
                        // a fast hand has already flipped that switch and pressed Save, and a
                        // warning that can be outrun is not a warning. The screen therefore stays
                        // on its spinner for one more round trip. That cost is why the read is
                        // narrow (scripts only) and why its failure is silent rather than fatal.
                        val warning = refreshedFilterWarning(
                            previous = null,
                            status = repo.loadFilterScriptStatus(credentials),
                            responderEnabled = r.isEnabled,
                        )
                        _state.value = VacationUiState(
                            loading = false,
                            supported = true,
                            accountLabel = store.accountLabel(),
                            enabled = r.isEnabled,
                            subject = r.subject.orEmpty(),
                            message = r.textBody.orEmpty(),
                            fromDate = r.fromDate?.let { parseMillis(it) },
                            toDate = r.toDate?.let { parseMillis(it) },
                            filterWarning = warning,
                        )
                        serverEdits = _state.value.edits()
                    }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        errorKind = VacationError.LOAD,
                        errorDetail = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun setEnabled(value: Boolean) = edit { it.copy(enabled = value) }
    fun setSubject(value: String) = edit { it.copy(subject = value) }
    fun setMessage(value: String) = edit { it.copy(message = value) }
    fun setFromDate(millis: Long?) = edit { it.copy(fromDate = millis) }
    fun setToDate(millis: Long?) = edit { it.copy(toDate = millis) }

    /** Apply a field edit, clearing any pending error / saved-confirmation. */
    private fun edit(transform: (VacationUiState) -> VacationUiState) =
        _state.update {
            val next = transform(it).copy(errorKind = null, savedTick = 0)
            next.copy(dirty = next.edits() != serverEdits)
        }

    fun save() {
        val credentials = store.load() ?: return
        val s = _state.value
        if (s.fromDate != null && s.toDate != null && s.fromDate > s.toDate) {
            _state.update { it.copy(errorKind = VacationError.INVALID_DATES) }
            return
        }
        _state.update { it.copy(saving = true, errorKind = null) }
        viewModelScope.launch {
            var written = false
            try {
                val vacation = VacationResponse(
                    isEnabled = s.enabled,
                    fromDate = s.fromDate?.let { isoStartOfDay(it) },
                    toDate = s.toDate?.let { isoEndOfDay(it) },
                    subject = s.subject.ifBlank { null },
                    textBody = s.message.ifBlank { null },
                    htmlBody = null,
                )
                repo.saveVacation(credentials, vacation)
                serverEdits = s.edits()
                _state.update { it.copy(saving = false, savedTick = it.savedTick + 1, dirty = false) }
                written = true
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        errorKind = VacationError.SAVE,
                        errorDetail = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
            // OUTSIDE the try, and after the state that lets a "save then leave" go. Two reasons,
            // both learned the hard way: the exit gesture must not wait on a read the write does
            // not need, and this read is the one that gets CANCELLED by that very exit — inside
            // the catch above, a cancellation would be painted as a failed save over a write the
            // server has already taken. What it buys, for the user who stays: having just switched
            // the responder on, the line becomes the statement of fact instead of predicting what
            // has already happened.
            //
            // `responderEnabled = s.enabled`: what was just WRITTEN to the server, not what the
            // switch shows — they are the same here and only here. And a read that FAILS keeps the
            // line that was on screen (previous), because the case where it fails is the case
            // where it matters: switching the responder off leaves no script active at all, so the
            // rules are dead exactly when a swallowed failure would blank the warning.
            if (written) {
                val status = repo.loadFilterScriptStatus(credentials)
                _state.update {
                    it.copy(
                        filterWarning = refreshedFilterWarning(
                            previous = it.filterWarning,
                            status = status,
                            responderEnabled = s.enabled,
                        ),
                    )
                }
            }
        }
    }

    private fun parseMillis(iso: String): Long? =
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    /** Normalise a picked day to 00:00:00 UTC and render as an RFC 8621 UTCDate. */
    private fun isoStartOfDay(millis: Long): String =
        Instant.ofEpochMilli(millis - (millis % DAY_MS)).toString()

    /** End-of-day UTC (23:59:59) for the picked day, so the day is inclusive. */
    private fun isoEndOfDay(millis: Long): String =
        Instant.ofEpochMilli(millis - (millis % DAY_MS) + DAY_MS - 1000).toString()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
