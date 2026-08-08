package app.gridlink

import android.content.Intent
import android.net.MailTo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.appcompat.app.AppCompatActivity
import app.gridlink.core.data.settings.ListDensity
import app.gridlink.core.data.settings.PreviewLines
import app.gridlink.core.data.settings.ThemeMode
import app.gridlink.ui.AppNavHost
import app.gridlink.ui.gridlink.GridlinkIntroOverlay
import app.gridlink.ui.gridlink.rememberGridlinkIntroMode
import app.gridlink.ui.message.NavFadeGuard
import app.gridlink.ui.components.LocalListDensity
import app.gridlink.ui.components.LocalPreviewLines
import app.gridlink.ui.theme.AppTheme
import app.gridlink.ui.theme.ProvideGridlinkTokens

class MainActivity : AppCompatActivity() {
    /** A mailto: link waiting to open the compose screen (Codeberg #15). Set from the launch
     *  intent or [onNewIntent] (the activity is singleTask, so an external link re-enters the
     *  running instance), consumed by the NavHost once it has navigated. */
    private val pendingMailto = androidx.compose.runtime.mutableStateOf<MailtoDraft?>(null)

    /** A new-mail notification tap waiting to open that message (Codeberg #17 follow-up). Same
     *  singleTask/onNewIntent plumbing as [pendingMailto]. */
    private val pendingEmailOpen = androidx.compose.runtime.mutableStateOf<EmailOpenTarget?>(null)

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
            // ⚠️ Not on every cold start: only on the ones that are actually somebody opening
            // Gridlink. A tapped new-mail notification and a mailto: link from another app are both
            // requests to be somewhere specific, and a brand animation in front of either is the app
            // making the user wait through its logo to get to the thing they already asked for.
            playIntro = pendingMailto.value == null && pendingEmailOpen.value == null
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
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
            val density by settings.listDensity.collectAsState(initial = ListDensity.NORMAL)
            val previewLines by settings.previewLines.collectAsState(initial = PreviewLines.ONE)
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
                        )
                        if (introPlaying) {
                            // Gridlink's palette, which the nav host provides for itself further
                            // down but nothing provides up here. See [rememberGridlinkIntroMode] for
                            // why resolving it a second time is safe and what would break it.
                            ProvideGridlinkTokens(mode = rememberGridlinkIntroMode()) {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (parseMailto(intent) ?: parseShare(intent))?.let { pendingMailto.value = it }
        parseEmailOpen(intent)?.let { pendingEmailOpen.value = it }
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
        // Stopped activity → no frames → the reader's GL functor cannot draw, so a process
        // kill while backgrounded (LMK, swipe-away) must not count as a fade-window crash.
        NavFadeGuard.onActivityStop(this)
    }

    override fun onStart() {
        super.onStart()
        application.container.appLock.onAppForegrounded(System.currentTimeMillis())
        NavFadeGuard.onActivityStart(this)
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
