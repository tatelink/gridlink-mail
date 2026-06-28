package app.sterna.core.data.mail

/**
 * Arms the background job that delivers an outbox item. Implemented in the app layer (which owns
 * WorkManager and the worker class) and injected into [MailRepository], so the data module can
 * enqueue a send without depending on the app.
 */
fun interface OutboxScheduler {
    /** Arm delivery of item [id], first running after [initialDelayMillis] (e.g. the undo window). */
    fun schedule(id: Long, initialDelayMillis: Long)
}
