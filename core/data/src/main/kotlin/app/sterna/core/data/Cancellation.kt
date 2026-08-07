package app.sterna.core.data

import kotlinx.coroutines.CancellationException

/**
 * [Result.getOrElse] for suspending work: the fallback runs for a real failure, but a
 * cancellation is re-thrown untouched.
 *
 * `runCatching` catches [CancellationException] like anything else, so a coroutine that is
 * cancelled mid-call comes out of it looking like a failed call, and the failure branch — a
 * cached fallback, a state teardown, an error banner — runs on behalf of a caller that no
 * longer exists. This has already bitten the Empty-trash path twice (Codeberg #99): the
 * folder photograph fell back to the cache when an Undo cancelled it, so the confirmation the
 * user had just withdrawn stood; and the view model's failure teardown cleared a pending purge
 * that had meanwhile passed to another job, leaving the destroy unopposed with no Undo on
 * screen. Cancellation is not a failure to recover from — it is an instruction to stop.
 *
 * Public rather than `internal`: the two call sites live in different Gradle modules
 * (`core:data` and `app`), and `internal` would not reach across.
 *
 * Inline, with a plain (non-crossinline) lambda, so a call site can still `return@launch` out
 * of the fallback the way a bare [Result.getOrElse] allows. Same `<R, T : R>` shape as
 * [Result.getOrElse] too, so the fallback may widen the type (typically to `Nothing`, when it
 * returns out of the caller instead of producing a value).
 */
inline fun <R, T : R> Result<T>.getOrElseUnlessCancelled(onFailure: (Throwable) -> R): R {
    val error = exceptionOrNull() ?: return getOrThrow()
    if (error is CancellationException) throw error
    return onFailure(error)
}

/**
 * The same rule for a [Result] that is KEPT rather than unwrapped: a success or an ordinary failure
 * is handed straight back, and a [CancellationException] is re-thrown.
 *
 * [getOrElseUnlessCancelled] cannot serve where the Result itself is the value — the sub-account
 * probe of Codeberg #129 stores one Result per candidate and hands the whole batch to a pure
 * decision, and a Result carrying a cancellation would be read there as "this account failed, so
 * keep it": the reconcile would finish, and write, on behalf of a connect() that was stopped. Same
 * for a bare `runCatching` whose result is discarded (best-effort work): it turns a cancellation
 * into a normal return, and the caller carries on — which is how a screen the user has just left
 * gets told it is Connected.
 */
fun <T> Result<T>.rethrowIfCancelled(): Result<T> {
    val error = exceptionOrNull()
    if (error is CancellationException) throw error
    return this
}
