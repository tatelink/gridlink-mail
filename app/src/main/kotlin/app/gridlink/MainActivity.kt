package app.gridlink

import android.content.Intent
import android.net.MailTo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.gridlink.core.data.settings.GridlinkPalette
import app.gridlink.core.data.settings.ListDensity
import app.gridlink.core.data.settings.PreviewLines
import app.gridlink.core.data.settings.ThemeMode
import app.gridlink.icon.AppIcons
import app.gridlink.ui.AppNavHost
import app.gridlink.ui.components.LocalListDensity
import app.gridlink.ui.components.LocalPreviewLines
import app.gridlink.ui.gridlink.GridlinkDestination
import app.gridlink.ui.gridlink.GridlinkIntroOverlay
import app.gridlink.ui.gridlink.LocalGridlinkIntroPlaying
import app.gridlink.ui.gridlink.rememberGridlinkIntroMode
import app.gridlink.ui.theme.AppTheme
import app.gridlink.ui.theme.ProvideGridlinkTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {
    /** A mailto: link waiting to open the compose screen (Codeberg #15). Set from the launch
     *  intent or [onNewIntent] (the activity is singleTask, so an external link re-enters the
     *  running instance), consumed by the NavHost once it has navigated. */
    private val pendingMailto = androidx.compose.runtime.mutableStateOf<MailtoDraft?>(null)

    /** A new-mail notification tap waiting to open that message (Codeberg #17 follow-up). Same
     *  singleTask/onNewIntent plumbing as [pendingMailto]. */
    private val pendingEmailOpen = androidx.compose.runtime.mutableStateOf<EmailOpenTarget?>(null)

    /** A tab a widget tap wants to land on (the agenda widget's rows). Same plumbing again. */
    private val pendingSection = androidx.compose.runtime.mutableStateOf<GridlinkDestination?>(null)

    /**
     * Whether this launch gets the animated intro.
     *
     * 🔴 Read once in [onCreate] and never again, because "did the user open the app" is a fact
     * about the launch and not about the composition. Deciding it inside `setContent` would replay
     * the intro on every recreation the system feels like handing out — a rotation, an unfold, a
     * font size change, coming back to a process the launcher trimmed — each time on top of
     * whatever the user was reading.
     */
    private var playIntro = false

    /**
     * Whether the system splash is out of the way, which is what the intro's clock waits for.
     *
     * 🔴 On API 31+ the platform draws its splash as a separate window OVER the app while the app
     * composes and renders its first frames underneath. The intro overlay composes during exactly
     * that covered stretch, so starting its clock on composition meant the opening scenes played
     * to nobody and the user saw only the tail — which is how the first build shipped, and why the
     * animation read as absurdly fast on a real launch. Registering [android.window.SplashScreen]'s
     * exit listener does two things at once: it hands control of the splash's removal to us (we
     * remove it with no exit animation, a clean cut onto the intro's own frame zero, whose backdrop
     * the splash colour was already chosen to match), and it tells us the moment the user can
     * actually see the screen, which is when the choreography is allowed to begin.
     *
     * ⚠️ True from the start everywhere the listener can never fire: below API 31 (no system
     * splash), on recreations (`savedInstanceState != null` — rotation and the hinge re-show no
     * splash, and a restored mid-play intro would otherwise wait forever on a signal that is not
     * coming), and on launches that skip the intro anyway. The lesson behind the caution is the
     * seedable-display-state deadlock from the sync work: a gate must never be able to outlive the
     * thing it gates.
     */
    private val introReady = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a real launch. The activity is singleTask, so the intent that opened a
        // notification (or a mailto: link) stays the activity's intent for good; re-parsing it
        // on every recreation — rotation, theme/locale change, font size, split screen — would
        // re-navigate to that message on top of whatever the user is doing, once per
        // recreation, and pile up back-stack entries they then have to unwind.
        if (savedInstanceState == null) {
            pendingMailto.value = parseMailto(intent) ?: parseShare(intent)
            pendingEmailOpen.value = parseEmailOpen(intent)
            pendingSection.value = parseSection(intent)
            // ⚠️ Not on every cold start: only on the ones that are actually somebody opening
            // Gridlink. A tapped new-mail notification and a mailto: link from another app are both
            // requests to be somewhere specific, and a brand animation in front of either is the app
            // making the user wait through its logo to get to the thing they already asked for.
            // 🔴 …and only on the launches the intro is actually DUE on. Brandon, 2026-08-10: "i
            // like the video but loading it every time seems like a lot - maybe only the first time
            // after account added? every app open is too much tho." Due means: never played here
            // before, or the account list has grown since it last played. See
            // [SettingsRepository.introSeenAccountCount] for why it is a count and not a boolean.
            // 🔴 A widget tap counts too. Somebody who tapped an appointment on their home screen
            // asked for their calendar, not for a logo animation in front of it.
            playIntro = pendingMailto.value == null &&
                pendingEmailOpen.value == null &&
                pendingSection.value == null &&
                introIsDue()
        }
        if (playIntro && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splash ->
                // No exit animation: the splash colour matches the intro's backdrop, so a hard cut
                // lands on frame zero of the choreography and the removal itself is invisible.
                splash.remove()
                introReady.value = true
            }
            // ⚠️ Belt and braces on the deadlock above: launch paths exist where the system decides
            // not to show a splash at all (trampolines, some launcher edge cases), and then the
            // listener never fires. Late is a recoverable failure; never is not.
            window.decorView.postDelayed({ introReady.value = true }, INTRO_SPLASH_FALLBACK_MS)
        } else {
            introReady.value = true
        }
        val settings = application.container.settingsRepository
        // 🔴 Re-assert the chosen launcher icon over whatever the system currently has enabled. The
        // two are separate copies of one fact and they DO drift: "clear app data" resets this store
        // to AUTO while leaving the alias where it was, so the phone would keep showing an icon the
        // app no longer believes it chose. The stored value wins, because it is the one the user
        // made. See [AppIcons.reconcile]; every call is a no-op when the state already agrees.
        lifecycleScope.launch { AppIcons.reconcile(this@MainActivity, settings.appIcon.first()) }
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
            val density by settings.listDensity.collectAsState(initial = ListDensity.NORMAL)
            // 🔴 The initial value is the one every list is drawn with for the frame or two before
            // DataStore answers, so it has to be the value that changes nothing: a taller initial
            // would make the inbox visibly shrink on every cold start for the users who leave the
            // setting alone, which is all of them by default.
            val previewLines by settings.previewLines.collectAsState(initial = PreviewLines.NONE)
            // ⚠️ Only the intro overlay reads this. The Gridlink screens underneath collect it
            // again for themselves, and have to, because they cannot start on a default and correct
            // themselves later the way an animation can. See [rememberGridlinkIntroMode].
            val gridlinkPalette by settings.gridlinkPalette.collectAsState(initial = GridlinkPalette.AUTO)
            AppTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                CompositionLocalProvider(
                    LocalListDensity provides density,
                    LocalPreviewLines provides previewLines,
                ) {
                    // 🔴 Saved, not merely remembered. Unfolding recreates this activity, and a
                    // remembered flag would come back false-by-default and replay the intro over
                    // the mail the user just opened the phone to read. Saved, an intro that has
                    // already finished stays finished across the hinge.
                    var introPlaying by rememberSaveable { mutableStateOf(playIntro) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        // The keyboard would rise OVER the overlay (see LocalGridlinkIntroPlaying),
                        // so the screens underneath get told the intro is up and hold their focus
                        // grabs until it is gone.
                        CompositionLocalProvider(LocalGridlinkIntroPlaying provides introPlaying) {
                            AppNavHost(
                                pendingMailto = pendingMailto.value,
                                onMailtoConsumed = {
                                    pendingMailto.value = null
                                    stripMailtoPayload()
                                },
                                pendingEmailOpen = pendingEmailOpen.value,
                                onEmailOpenConsumed = {
                                    pendingEmailOpen.value = null
                                    stripEmailOpenPayload()
                                },
                                pendingSection = pendingSection.value,
                                onSectionConsumed = {
                                    pendingSection.value = null
                                    intent?.removeExtra(EXTRA_OPEN_SECTION)
                                },
                            )
                        }
                        if (introPlaying) {
                            // Gridlink's palette, which the nav host provides for itself further
                            // down but nothing provides up here. See [rememberGridlinkIntroMode] for
                            // why resolving it a second time is safe and what would break it.
                            ProvideGridlinkTokens(mode = rememberGridlinkIntroMode(gridlinkPalette)) {
                                GridlinkIntroOverlay(
                                    onFinished = { introPlaying = false },
                                    started = introReady.value,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Whether this launch is one of the ones the intro is owed on, and claim it if so.
     *
     * Due when nothing has been recorded yet (a genuine first launch) or when the account list has
     * grown past the count the intro last saw (an account was added since). Claiming it writes
     * today's count back, so the next launch with the same accounts skips the animation — which is
     * the whole point of the change.
     *
     * ⚠️ The read is blocking, on the main thread, on purpose. "Does this launch get the intro" is a
     * fact about the launch: it has to be settled before the splash-exit listener is registered a
     * few lines below, and before `setContent` seeds `introPlaying`. Deciding it later — from a
     * collected flow — would either flash the intro onto a screen that had already drawn mail, or
     * hold every launch behind an async read to avoid that. The read is one small preferences file
     * while the system splash is still covering the window, and the alternative costs more.
     *
     * The write is not blocking and does not need to be: nothing this launch does depends on it, and
     * a process killed between the two only spends one extra intro.
     */
    private fun introIsDue(): Boolean {
        val settings = application.container.settingsRepository
        val accountCount = application.container.accountStore.accounts().size
        val seen = runBlocking { settings.introSeenAccountCount.first() }
        if (seen != null && accountCount <= seen) return false
        lifecycleScope.launch { settings.setIntroSeenAccountCount(accountCount) }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (parseMailto(intent) ?: parseShare(intent))?.let { pendingMailto.value = it }
        parseEmailOpen(intent)?.let { pendingEmailOpen.value = it }
        parseSection(intent)?.let { pendingSection.value = it }
    }

    /**
     * Drop a consumed one-shot payload from the retained intent. Belt and braces next to the
     * [onCreate] guard: whatever re-reads the intent later, it no longer carries an open/compose
     * order that was already carried out. Kept per-payload so consuming one can never cancel a
     * genuinely pending other.
     */
    private fun stripEmailOpenPayload() {
        val i = intent ?: return
        i.removeExtra(EXTRA_OPEN_EMAIL_ID)
        i.removeExtra(EXTRA_OPEN_ACCOUNT_ID)
        i.removeExtra(EXTRA_OPEN_MAILBOX_ID)
    }

    private fun stripMailtoPayload() {
        val i = intent ?: return
        if ("mailto".equals(i.scheme, ignoreCase = true)) i.data = null
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) {
            i.action = Intent.ACTION_MAIN
            i.removeExtra(Intent.EXTRA_SUBJECT)
            i.removeExtra(Intent.EXTRA_TEXT)
            i.removeExtra(Intent.EXTRA_STREAM)
            // The stash [parseShare] filled, dropped with the payload it belongs to. Belt and
            // braces: both composers clear it as they read it (Gridlink stages the files before it
            // opens, upstream attaches them on first composition). Left standing it would sit in
            // the container until some later draft picked up files from a share the user made hours
            // ago — and the read grants behind those URIs will have expired by then anyway.
            application.container.pendingShareUris = emptyList()
        }
    }

    /** The message a tapped new-mail notification wants to open, or null. */
    private fun parseEmailOpen(intent: Intent?): EmailOpenTarget? {
        val emailId = intent?.getStringExtra(EXTRA_OPEN_EMAIL_ID) ?: return null
        return EmailOpenTarget(
            emailId = emailId,
            accountId = intent.getStringExtra(EXTRA_OPEN_ACCOUNT_ID)?.ifBlank { null },
            mailboxId = intent.getStringExtra(EXTRA_OPEN_MAILBOX_ID)?.ifBlank { null },
        )
    }

    /**
     * The tab a widget tap wants, or null.
     *
     * 🔴 Matched by NAME against the enum rather than by ordinal, and an unrecognised name is null
     * rather than a fallback tab. A widget's PendingIntent outlives the app it was built by: the
     * launcher holds it across updates, so this can be handed a section string from a build that
     * shipped months ago. Landing that on the wrong tab because the enum's order changed in between
     * is the kind of bug nobody reproduces; landing it on the app's normal opening screen is the
     * behaviour of the widget that had no deep link at all, which is exactly right for one that no
     * longer means anything.
     */
    private fun parseSection(intent: Intent?): GridlinkDestination? {
        val name = intent?.getStringExtra(EXTRA_OPEN_SECTION)?.takeIf { it.isNotBlank() } ?: return null
        return GridlinkDestination.entries.firstOrNull { it.name == name }
    }

    /** RFC 6068 mailto: parsing — addresses plus the optional subject/body/cc/bcc fields. */
    private fun parseMailto(intent: Intent?): MailtoDraft? {
        val data = intent?.data ?: return null
        if (!"mailto".equals(data.scheme, ignoreCase = true)) return null
        return runCatching {
            val m = MailTo.parse(data.toString())
            MailtoDraft(
                to = m.to.orEmpty(),
                cc = m.cc.orEmpty(),
                bcc = m.headers?.get("bcc").orEmpty(),
                subject = m.subject.orEmpty(),
                body = m.body.orEmpty(),
            )
        }.getOrNull()
    }

    /**
     * A system "Share" (ACTION_SEND / SEND_MULTIPLE) opens the compose screen: shared text and
     * subject prefill the fields (reusing the mailto path), and any shared files are stashed for
     * the compose screen to attach (Codeberg #45).
     *
     * Only `content:` URIs are taken. The resolver would happily open a `file:` URI too, with OUR
     * permissions and no grant involved, so any app could hand us a path inside our own private
     * directory (the database, the account preferences) and have it pre-attached to a draft. A
     * legitimate share has been a `content:` URI since Android 7 anyway.
     */
    private fun parseShare(intent: Intent?): MailtoDraft? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        @Suppress("DEPRECATION")
        val uris: List<Uri> = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }.filter { "content".equals(it.scheme, ignoreCase = true) }
        if (subject.isBlank() && text.isBlank() && uris.isEmpty()) return null
        application.container.pendingShareUris = uris
        return MailtoDraft(to = "", cc = "", bcc = "", subject = subject, body = text)
    }

    override fun onStop() {
        super.onStop()
        application.container.appLock.onAppBackgrounded(System.currentTimeMillis())
        // A session's reading, deleting and archiving is invisible to the home screen until
        // something redraws it, and the user is on their way back to the home screen right now —
        // this is the exact moment a stale widget gets looked at. Background sync pokes the
        // widgets too (push/FetchAndNotify.kt); this covers everything the user did by hand.
        app.gridlink.widget.GridlinkWidgets.refresh(this)
    }

    override fun onStart() {
        super.onStart()
        application.container.appLock.onAppForegrounded(System.currentTimeMillis())
        applySecureFlag()
    }

    /**
     * With app lock on, mark the window FLAG_SECURE so message bodies and the credential
     * screen are kept out of the recents thumbnail, screenshots, and screen recordings —
     * otherwise the lock overlay hides the live UI but the OS still snapshots it.
     */
    private fun applySecureFlag() {
        if (application.container.appLock.isEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        /** How long a missing splash-exit signal is allowed to hold the intro before it plays anyway. */
        private const val INTRO_SPLASH_FALLBACK_MS = 2000L

        const val EXTRA_OPEN_EMAIL_ID = "app.gridlink.OPEN_EMAIL_ID"
        const val EXTRA_OPEN_ACCOUNT_ID = "app.gridlink.OPEN_ACCOUNT_ID"
        const val EXTRA_OPEN_MAILBOX_ID = "app.gridlink.OPEN_MAILBOX_ID"

        /** Carries a [GridlinkDestination] name. See [parseSection] for why it is the name. */
        const val EXTRA_OPEN_SECTION = "app.gridlink.OPEN_SECTION"
    }
}

/** Prefill fields parsed from a mailto: link, handed to the compose screen (Codeberg #15). */
data class MailtoDraft(
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
)

/**
 * The message a tapped new-mail notification should open (Codeberg #17 follow-up), and the
 * context it lives in: its account (#31) and the folder it sits in (#91), so the list underneath
 * ends up where the message is and Back lands in a list that holds it. Either may be null for a
 * notification posted by an older version.
 */
data class EmailOpenTarget(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String? = null,
)
