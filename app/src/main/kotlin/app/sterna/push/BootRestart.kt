package app.sterna.push

/**
 * A transport Sterna serves with a connection of its own, held open by [PushService].
 * It is the only piece of the delivery machinery that a reboot leaves dead:
 * [Transport.UNIFIED_PUSH] rides the distributor app (which the system starts itself)
 * and [Transport.PERIODIC] is a WorkManager job (WorkManager reschedules its own work
 * after a boot, through its own receiver).
 */
internal val Transport.isDirect: Boolean
    get() = this == Transport.EVENT_SOURCE || this == Transport.IMAP_IDLE

/** One watched account reduced to what the restart decision needs (issue #98). */
internal data class AccountPushState(
    val notificationsEnabled: Boolean,
    val transport: Transport,
)

/**
 * The decision behind [BootReceiver], kept apart from Android so it can be tested:
 * after a reboot, does anything actually have to be restarted by hand?
 *
 * Almost always the answer is no. Nothing is restarted "just in case": starting the
 * foreground service for an account whose mail arrives through a UnifiedPush
 * distributor would put a permanent notification and an idle socket on the device for
 * no benefit at all.
 */
internal object BootRestart {

    /**
     * True only when at least one watched account still wants notifications *and* is
     * served by a connection of ours. No account configured (empty list), every account
     * on UnifiedPush, notifications turned off, battery-saver delivery or a linked
     * sub-account (both already resolve to [Transport.PERIODIC]) all answer false.
     */
    fun needsPushService(accounts: List<AccountPushState>): Boolean =
        accounts.any { it.notificationsEnabled && it.transport.isDirect }
}
