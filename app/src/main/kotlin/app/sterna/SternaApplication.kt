package app.sterna

import android.app.Application
import android.content.Context
import app.sterna.core.data.DataFactory
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.mail.BodyCachePurge
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OutboxScheduler
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.jmap.JmapClient
import app.sterna.pgp.OpenKeychainPgpEngine
import app.sterna.push.UnifiedPushManager
import app.sterna.push.UnifiedPushStateStore
import app.sterna.security.AppLock
import app.sterna.send.Outbox
import app.sterna.send.SendOutbox
import app.sterna.ui.connect.OutlookSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Simple manual DI container — holds app-wide singletons. */
class AppContainer(context: Context) {
    /** Content Uris shared into the app (ACTION_SEND), awaiting attachment by the next compose
     *  screen (Codeberg #45). A transient one-shot handoff between MainActivity and ComposeScreen;
     *  the compose screen reads and clears it. */
    var pendingShareUris: List<android.net.Uri> = emptyList()

    val accountStore: AccountStore = AccountStore(context.applicationContext)
    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)
    private val jmapClient: JmapClient = JmapClient()

    /** OpenPGP via the OpenKeychain provider (binds lazily; harmless when not installed). */
    val pgpEngine: OpenKeychainPgpEngine = OpenKeychainPgpEngine(context.applicationContext)
    private val dataLayer =
        DataFactory.create(context.applicationContext, jmapClient, accountStore, pgpEngine, settingsRepository)
    val mailRepository: MailRepository = dataLayer.mailRepository
    val storageRepository: StorageRepository = dataLayer.storageRepository
    val appLock: AppLock = AppLock(accountStore)

    /** UnifiedPush transport state machine (issue #17); inert without a distributor. */
    val unifiedPushManager: UnifiedPushManager = UnifiedPushManager(
        context.applicationContext,
        accountStore,
        mailRepository,
        UnifiedPushStateStore(context.applicationContext),
    )

    /**
     * App-lifetime scope for work that must outlive a screen (e.g. Undo-send hold-back, or putting
     * a queued message back in the outbox as the composer that held it is being popped — #70).
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sendOutbox: SendOutbox = SendOutbox(appScope)

    /** Drives the Outlook OAuth device flow app-scoped, so it survives the browser round-trip. */
    val outlookSignIn: OutlookSignIn = OutlookSignIn(mailRepository, appScope, context.applicationContext)

    init {
        val appContext = context.applicationContext
        // Fallback new-mail poll: keeps notifications flowing when live push is killed
        // (Android 15+ FGS policies, OEM battery managers — Codeberg #11). Periodic work
        // persists across reboots; a healthy push makes each run a no-op.
        app.sterna.push.MailFetchWorker.ensureScheduled(appContext)
        // A UnifiedPush transport change (verified, failed, unregistered) re-evaluates
        // which accounts still need a direct connection.
        unifiedPushManager.onTransportStateChanged = {
            // Background trigger: diff, never reseed (gap mail must still notify).
            app.sterna.push.PushController.apply(appContext, userInitiated = false)
        }
        // Let the data layer arm the delivery worker from any send call site (compose, RSVP, …).
        mailRepository.outboxScheduler = OutboxScheduler { id, delay -> Outbox.enqueue(appContext, id, delay) }
        // A linked sub-account pruned on reconcile (access revoked, Codeberg #31) drops its
        // notification baselines too, exactly like a sign-out — the data layer cannot reach them.
        mailRepository.onAccountPruned = { app.sterna.push.NewMailNotifier.clear(appContext, it) }
        // A folder the server renumbered (Codeberg #99): every id in it is new, so its baseline
        // describes messages that no longer exist under those numbers. Cleared here, the next
        // pass seeds the folder silently instead of announcing its whole content as new mail.
        mailRepository.onMailboxRenumbered = { accountId, mailboxId ->
            app.sterna.push.NewMailNotifier.clear(appContext, accountId, mailboxId)
        }
        // Catch-up sweep of cached mail belonging to accounts that no longer exist (Codeberg
        // #121) — rows a removed account left behind, which nothing syncs, nothing prunes and no
        // action can reach. The account list is handed over as a READER, not as a list: the sweep
        // calls it after it has taken its inventory, so an account created while the database was
        // opening (a shared mailbox discovered by the first refresh, #31) is already in it and
        // cannot be mistaken for an orphan. An empty list sweeps nothing (OrphanedAccountCache):
        // purging before the accounts are known would wipe a healthy install's whole cache.
        appScope.launch {
            runCatching { storageRepository.purgeOrphanedAccounts { accountStore.accounts().map { it.id } } }
                .onFailure { android.util.Log.w("Sterna", "orphaned-cache sweep failed; retried next start", it) }
        }
        // One-shot after an upgrade: drop the cached message bodies (the unsubscribe headers are
        // the current reason, and they will not be the last). A body
        // serialised by an older build is short of whatever field the new one learned to read,
        // and openMessage serves the cache before looking at anything — so without this, a new
        // reader feature is invisible on precisely the mail already in the cache: the twenty
        // newest of the inbox, kept warm by the prefetch. It refills itself on the next open.
        appScope.launch {
            runCatching {
                BodyCachePurge(appContext).onceForVersion(BuildConfig.VERSION_CODE) {
                    mailRepository.clearCachedBodies()
                }
            }.onFailure { android.util.Log.w("Sterna", "body-cache upgrade purge failed; retried next start", it) }
        }
        // Re-arm any send left mid-flight (WorkManager persists jobs, but re-checking is a safety net).
        appScope.launch {
            // First rescue any row stranded in EDITING by a process death mid-edit (#70): its
            // composer is gone, so it becomes a normal QUEUED send again rather than sitting
            // off-worker forever. The re-arm below then schedules it like any other queued item.
            mailRepository.revertEditingOutbox()
            mailRepository.unfinishedOutbox().forEach { item ->
                val delay = (item.notBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0)
                Outbox.enqueue(appContext, item.id, delay)
            }
        }
    }
}

class SternaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Convenience accessor for ViewModels (which receive the Application). */
val Application.container: AppContainer
    get() = (this as SternaApplication).container
