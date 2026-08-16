package app.gridlink.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gridlink.container
import app.gridlink.core.data.account.K9SettingsImporter
import app.gridlink.core.data.settings.APP_ICON_DEFAULT
import app.gridlink.core.data.settings.AppIcon
import app.gridlink.core.data.settings.DeliveryMode
import app.gridlink.core.data.settings.ListDensity
import app.gridlink.core.data.settings.MailTag
import app.gridlink.core.data.settings.MessageTextSize
import app.gridlink.core.data.settings.NotificationContent
import app.gridlink.core.data.settings.PreviewLines
import app.gridlink.core.data.settings.SettingsBackup
import app.gridlink.core.data.settings.SettingsBackupCodec
import app.gridlink.core.data.settings.SettingsRepository
import app.gridlink.core.data.settings.SwipeAction
import app.gridlink.core.data.settings.TagColor
import app.gridlink.core.data.settings.ThemeMode
import app.gridlink.core.data.settings.ThreadToolbarAction
import app.gridlink.icon.AppIcons
import app.gridlink.push.NewMailNotifier
import app.gridlink.push.PushController
import app.gridlink.security.canAuthenticate
import app.gridlink.sync.SystemMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val appLock = application.container.appLock
    private val settings = application.container.settingsRepository

    /** Only for the tag manager's "tags in use" scan; nothing else on this screen touches mail. */
    private val mail = application.container.mailRepository

    private val _pushAllAccounts = MutableStateFlow(store.pushAllAccounts())
    val pushAllAccounts = _pushAllAccounts.asStateFlow()

    private val _appLock = MutableStateFlow(store.appLockEnabled())
    val appLockEnabled = _appLock.asStateFlow()

    /** Set when the user tries to enable app lock but no biometric / screen lock exists. */
    private val _appLockUnavailable = MutableStateFlow(false)
    val appLockUnavailable = _appLockUnavailable.asStateFlow()

    val themeMode = settings.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.SYSTEM,
    )

    val dynamicColor = settings.dynamicColor.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    val listDensity = settings.listDensity.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ListDensity.NORMAL,
    )

    val previewLines = settings.previewLines.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PreviewLines.NONE,
    )

    val appIcon = settings.appIcon.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = APP_ICON_DEFAULT,
    )

    /**
     * 🔴 Writes the launcher's component state as well as the store, and in that order: the store is
     * the record, [AppIcons] is the thing the user can actually see. A setter that only persisted
     * would leave the row saying "Dark" over a home screen that never changed.
     */
    fun setAppIcon(icon: AppIcon) {
        AppIcons.apply(getApplication(), icon)
        viewModelScope.launch { settings.setAppIcon(icon) }
    }

    // ⚠️ The initial values must match the repository's own defaults, or the rows show one answer
    // for a frame and then swap to another. They also changed on 2026-08-12 when these settings were
    // finally wired to the gesture; see [SettingsRepository.swipeRightAction].
    val swipeRight = settings.swipeRightAction.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SwipeAction.ARCHIVE,
    )

    val swipeLeft = settings.swipeLeftAction.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SwipeAction.TOGGLE_READ,
    )

    val swipeLeftFar = settings.swipeLeftFarAction.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SwipeAction.DELETE,
    )

    val contactSuggestions = settings.contactSuggestions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setContactSuggestions(value: Boolean) {
        viewModelScope.launch { settings.setContactSuggestions(value) }
    }

    val systemAccountMirror = settings.systemAccountMirror.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    /**
     * Turn the system contacts/calendar mirror on or off, then reconcile immediately.
     *
     * 🔴 The reconcile is part of the action, not a background tidy-up. Turning the switch off is
     * how the user asks for their contacts to be taken back out of the system, and that removal
     * happens inside [SystemMirror.apply] by unregistering the account. Leaving it to the next app
     * start would mean the switch reads "off" while the data is still there.
     */
    fun setSystemAccountMirror(value: Boolean) {
        viewModelScope.launch {
            settings.setSystemAccountMirror(value)
            SystemMirror.apply(getApplication(), store, settings)
        }
    }

    /** Ask both sync adapters to run now, for the "Sync now" row. */
    fun syncSystemAccountsNow() {
        SystemMirror.requestSync(getApplication())
    }

    val stripTrackingParams = settings.stripTrackingParams.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setStripTrackingParams(value: Boolean) {
        viewModelScope.launch { settings.setStripTrackingParams(value) }
    }

    val confirmLinks = settings.confirmLinks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setConfirmLinks(value: Boolean) {
        viewModelScope.launch { settings.setConfirmLinks(value) }
    }

    // 🔴 initialValue matches the repository's default, not `false`. The switch is drawn from this
    // before the first DataStore read lands, and a privacy control that flickers off on entry
    // invites the reader to "fix" a setting that was never broken.
    val blockRemoteImages = settings.blockRemoteImages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setBlockRemoteImages(value: Boolean) {
        viewModelScope.launch { settings.setBlockRemoteImages(value) }
    }

    val imageAllowlist = settings.imageAllowlist.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet(),
    )

    fun clearImageAllowlist() {
        viewModelScope.launch { settings.clearImageAllowlist() }
    }

    fun setImageAllowed(sender: String, allowed: Boolean) {
        viewModelScope.launch { settings.setImageAllowed(sender, allowed) }
    }

    /**
     * The reader's custom tags: what they are called, and what colour they are drawn in.
     *
     * 🔴 Device-local by design, and the tag manager says so. What travels to the server is the
     * keyword alone — JMAP keywords (RFC 8621) and IMAP custom flags are both a bare set of strings
     * with nowhere to put a colour or a display name. So the colour lives here and in the settings
     * backup, and a message tagged on this phone shows up tagged in any other client, just without
     * the paint.
     */
    val mailTags = settings.mailTags.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun createMailTag(label: String, color: TagColor) {
        viewModelScope.launch { settings.createMailTag(label, color) }
    }

    fun updateMailTag(keyword: String, label: String, color: TagColor) {
        viewModelScope.launch { settings.updateMailTag(keyword, label, color) }
    }

    /**
     * ⚠️ Forgets the definition; it does NOT take the tag off the mail carrying it.
     *
     * Un-tagging would mean a write per message across a mailbox this device has only a window of,
     * and the messages it could not see would keep the keyword anyway — so the "clean" version of
     * this is one that quietly half-works. The tag simply stops being defined: mail that has it
     * still shows it, under its wire name in a derived colour, and the screen says so before the
     * delete goes through.
     */
    fun deleteMailTag(keyword: String) {
        viewModelScope.launch { settings.deleteMailTag(keyword) }
    }

    /**
     * Keywords sitting on cached mail that no definition on this device explains.
     *
     * Where they come from: another client on the same mailbox, or this device itself before a
     * settings restore, or a tag whose definition was deleted while tagged mail stayed tagged. All
     * three leave the reader looking at a grey chip under a wire name with no way to claim it, so
     * the manager lists them and offers [adoptMailTag].
     *
     * ⚠️ Only what is CACHED, and only for the current account. The window this device syncs is not
     * the mailbox, so a keyword that exists only on old mail will not appear here — which is why
     * this is an offer at the bottom of the screen and not a promise of completeness.
     *
     * Loaded once, on demand, rather than as a flow: it is a Room scan over the message table with
     * a LIKE on packed keywords, and nothing on this screen changes it.
     */
    private val _undefinedTags = MutableStateFlow<List<String>>(emptyList())
    val undefinedTags = _undefinedTags.asStateFlow()

    fun refreshUndefinedTags() {
        viewModelScope.launch {
            val accountId = store.currentId() ?: return@launch
            val defined = settings.mailTags.first().map { it.keyword }.toSet()
            _undefinedTags.value = runCatching { mail.tagsInUse(accountId) }
                .getOrDefault(emptyList())
                .filterNot { it in defined }
                .sorted()
        }
    }

    /**
     * Give an existing keyword a name and a colour, without touching the mail that carries it.
     *
     * The keyword is kept exactly as found — [SettingsRepository.createMailTag] would derive a new
     * one from the label, and a derived keyword that differed by one character would define a tag
     * that no message on the server has. So this appends the definition directly.
     */
    fun adoptMailTag(keyword: String, label: String, color: TagColor) {
        viewModelScope.launch {
            val existing = settings.mailTags.first()
            if (existing.none { it.keyword == keyword }) {
                settings.setMailTags(
                    existing + MailTag(keyword = keyword, label = label.trim(), color = color.name),
                )
            }
            refreshUndefinedTags()
        }
    }

    /** New-mail delivery: Instant / Battery saver (issue #17, the ONE outcome setting). */
    val deliveryMode = settings.deliveryMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DeliveryMode.INSTANT,
    )

    fun setDeliveryMode(mode: DeliveryMode) {
        viewModelScope.launch {
            settings.setDeliveryMode(mode)
            // Re-arm with the new outcome (stops or restarts the foreground service).
            PushController.apply(getApplication(), userInitiated = true)
        }
    }

    /** What a new-mail notification reveals: sender + subject / sender only / neither (#25). */
    val notificationContent = settings.notificationContent.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotificationContent.SENDER_AND_SUBJECT,
    )

    fun setNotificationContent(mode: NotificationContent) {
        viewModelScope.launch { settings.setNotificationContent(mode) }
    }

    fun setPushAllAccounts(value: Boolean) {
        store.setPushAllAccounts(value)
        _pushAllAccounts.value = value
        if (value) {
            // Accounts (re)entering the watched scope kept frozen baselines while out of
            // it; diffing those would burst stale notifications — drop them so the first
            // pass reseeds silently.
            store.allCredentials().filter { it.id != store.currentId() }
                .forEach { NewMailNotifier.clear(getApplication(), it.id) }
        }
        // Reconnect push with the new scope.
        PushController.apply(getApplication(), userInitiated = true)
    }

    val quietHoursEnabled = settings.quietHoursEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    val quietHoursStart = settings.quietHoursStart.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsRepository.DEFAULT_QUIET_START,
    )

    val quietHoursEnd = settings.quietHoursEnd.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsRepository.DEFAULT_QUIET_END,
    )

    fun setQuietHoursEnabled(value: Boolean) {
        viewModelScope.launch { settings.setQuietHoursEnabled(value) }
    }

    fun setQuietHoursStart(minutes: Int) {
        viewModelScope.launch { settings.setQuietHoursStart(minutes) }
    }

    fun setQuietHoursEnd(minutes: Int) {
        viewModelScope.launch { settings.setQuietHoursEnd(minutes) }
    }

    /**
     * Writes a JSON snapshot of the app's preferences and account configuration
     * (never credentials) to [uri]. [onResult] is invoked on the main thread with
     * success/failure.
     */
    fun exportSettings(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                val backup = settings.snapshotBackup().copy(
                    pushAllAccounts = store.pushAllAccounts(),
                    language = currentAppLanguage().tag,
                    accounts = store.accountsForBackup(),
                )
                val text = SettingsBackupCodec.encode(backup)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(text.toByteArray())
                    } ?: error("no output stream")
                }
            }.isSuccess
            onResult(ok)
        }
    }

    /**
     * Reads a backup file from [uri] and applies it. [onResult] reports success plus how many
     * accounts were newly added (0 if none/failure) — imported accounts have no password and must
     * be signed into. [onLanguageChanged] fires (with the new language) only when the language
     * differs, so the caller can recreate the activity to load the new strings.
     */
    fun importSettings(
        uri: Uri,
        onResult: (ok: Boolean, accountsAdded: Int) -> Unit,
        onLanguageChanged: (AppLanguage) -> Unit,
    ) {
        viewModelScope.launch {
            val backup: SettingsBackup? = runCatching {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("no input stream")
                }
                SettingsBackupCodec.decode(text)
            }.getOrNull()
            // Reject a file that isn't parseable, or that parses but carries no recognizable backup
            // fields (an unrelated JSON) — the caller shows an error and stays on its menu.
            if (backup == null || !backup.isPlausible()) {
                onResult(false, 0)
                return@launch
            }
            settings.restoreBackup(backup)
            backup.pushAllAccounts?.let { setPushAllAccounts(it) }
            _pushAllAccounts.value = store.pushAllAccounts()
            val accountsAdded = backup.accounts?.let { store.importAccounts(it) } ?: 0
            onResult(true, accountsAdded)
            backup.language?.let { tag ->
                val target = AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
                if (target != currentAppLanguage()) onLanguageChanged(target)
            }
        }
    }

    /** Parse a K-9 / Thunderbird `.k9s` export and import its (inert) accounts. [onResult] reports
     *  success, how many accounts were added (0 on failure/none), how many were skipped
     *  (POP3 / unsupported), and how many got a guessed connection security the user should
     *  check. Imported accounts have no credentials and must be signed into. */
    fun importK9Settings(uri: Uri, onResult: (ok: Boolean, added: Int, skipped: Int, unverified: Int) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        K9SettingsImporter.parse(it)
                    } ?: error("no input stream")
                }
            }.getOrNull()
            if (result == null) { onResult(false, 0, 0, 0); return@launch }
            val added = store.importAccounts(result.accounts)
            onResult(true, added, result.skipped.size, result.securityUnverified.size)
        }
    }

    fun setAppLock(value: Boolean) {
        if (value && !canAuthenticate(getApplication())) {
            _appLockUnavailable.value = true
            _appLock.value = false
            return
        }
        _appLockUnavailable.value = false
        appLock.setEnabled(value)
        _appLock.value = value
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setListDensity(density: ListDensity) {
        viewModelScope.launch { settings.setListDensity(density) }
    }

    fun setPreviewLines(value: PreviewLines) {
        viewModelScope.launch { settings.setPreviewLines(value) }
    }

    fun setSwipeRight(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeRightAction(action) }
    }

    fun setSwipeLeft(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeLeftAction(action) }
    }

    fun setSwipeLeftFar(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeLeftFarAction(action) }
    }

    val conversationView = settings.conversationView.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setConversationView(enabled: Boolean) {
        viewModelScope.launch { settings.setConversationView(enabled) }
    }

    /**
     * 🔴 `initialValue` is the real default, not a placeholder — same rule as [bundleAutomated]
     * below. Seeded with anything else and the switches would all be drawn off for a frame and then
     * snap on, which on a screen made entirely of switches reads as the setting resetting itself.
     */
    val threadToolbarActions = settings.threadToolbarActions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThreadToolbarAction.DEFAULTS,
    )

    /**
     * Flip one action on or off.
     *
     * Takes the action and the desired state rather than a whole set, so the caller is a switch row
     * that knows about exactly one action. Building the new set here also means the "off" case
     * cannot accidentally be written as a set of one.
     */
    fun setThreadToolbarAction(action: ThreadToolbarAction, enabled: Boolean) {
        val next = threadToolbarActions.value.toMutableSet().apply {
            if (enabled) add(action) else remove(action)
        }
        viewModelScope.launch { settings.setThreadToolbarActions(next) }
    }

    /**
     * 🔴 `initialValue = false` and that is not a placeholder, it is the setting's default.
     * See [SettingsRepository.bundleAutomated]: the list shows mail until the reader asks for it to
     * be reorganised, so the first frame drawn before DataStore answers must be the unsorted one.
     */
    val bundleAutomated = settings.bundleAutomated.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setBundleAutomated(enabled: Boolean) {
        viewModelScope.launch { settings.setBundleAutomated(enabled) }
    }

    val markReadOnDelete = settings.markReadOnDelete.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setMarkReadOnDelete(enabled: Boolean) {
        viewModelScope.launch { settings.setMarkReadOnDelete(enabled) }
    }

    val markReadOnArchive = settings.markReadOnArchive.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setMarkReadOnArchive(enabled: Boolean) {
        viewModelScope.launch { settings.setMarkReadOnArchive(enabled) }
    }

    val markReadOnMove = settings.markReadOnMove.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setMarkReadOnMove(enabled: Boolean) {
        viewModelScope.launch { settings.setMarkReadOnMove(enabled) }
    }

    val unarchiveOnReply = settings.unarchiveOnReply.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setUnarchiveOnReply(enabled: Boolean) {
        viewModelScope.launch { settings.setUnarchiveOnReply(enabled) }
    }

    val signatureOnReplies = settings.signatureOnReplies.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setSignatureOnReplies(enabled: Boolean) {
        viewModelScope.launch { settings.setSignatureOnReplies(enabled) }
    }

    val signatureBelowQuote = settings.signatureBelowQuote.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setSignatureBelowQuote(enabled: Boolean) {
        viewModelScope.launch { settings.setSignatureBelowQuote(enabled) }
    }

    val signatureDelimiter = settings.signatureDelimiter.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setSignatureDelimiter(enabled: Boolean) {
        viewModelScope.launch { settings.setSignatureDelimiter(enabled) }
    }

    val messageTextSize = settings.messageTextSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MessageTextSize.NORMAL,
    )

    fun setMessageTextSize(size: MessageTextSize) {
        viewModelScope.launch { settings.setMessageTextSize(size) }
    }
}
