package app.sterna.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the app picks light vs dark colours. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Vertical density of message-list rows. */
enum class ListDensity { COMPACT, NORMAL, SPACED }

/** How much of each message's body preview to show in the list. */
enum class PreviewLines(val lines: Int) { NONE(0), ONE(1), THREE(3), FIVE(5) }

/** An action bound to a swipe gesture on a message row. */
enum class SwipeAction { NONE, TOGGLE_READ, DELETE, ARCHIVE, FLAG }

/**
 * How the message list is ordered.
 *
 * [FLAGGED_FIRST] is a sort like any other, not a modifier: until 1.4.5 favourites were pinned
 * above every one of these orders unconditionally, so "Newest first" silently meant "newest
 * first, except the starred ones" (issue #111). Pinning is now something the reader picks.
 * Entries are persisted BY NAME, so appending is safe and reorders nothing already stored.
 */
enum class SortOrder { DATE_DESC, DATE_ASC, SUBJECT, SENDER, UNREAD_FIRST, FLAGGED_FIRST }

/** Reading text size for the message body (WebView text zoom, in percent). */
enum class MessageTextSize(val zoom: Int) { SMALL(85), NORMAL(100), LARGE(125), HUGE(150) }

/**
 * How new mail reaches the device (issue #17, outcome-framed — never a transport
 * choice): INSTANT keeps live push (UnifiedPush, or a direct connection when
 * needed); BATTERY_SAVER drops every connection Sterna holds and relies on the
 * 30-minute periodic check — UnifiedPush subscriptions stay active either way,
 * they cost the app nothing.
 */
enum class DeliveryMode { INSTANT, BATTERY_SAVER }

/** How much a new-mail notification reveals on the lock screen (Codeberg #25). */
enum class NotificationContent { SENDER_AND_SUBJECT, SENDER_ONLY, NONE }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Reactive app-preferences store backed by a Preferences [DataStore]. Kept
 * separate from [app.sterna.core.data.account.AccountStore], which holds accounts,
 * credentials, and per-account metadata. Each setting is exposed as a [Flow] so
 * the UI (and the theme) can collect changes live.
 */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    /** Use Material You (wallpaper-derived) colours instead of Sterna's brand palette. Off by default. */
    val dynamicColor: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_COLOR] ?: false
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    val listDensity: Flow<ListDensity> = dataStore.data.map { prefs ->
        prefs[KEY_LIST_DENSITY]?.let { runCatching { ListDensity.valueOf(it) }.getOrNull() }
            ?: ListDensity.NORMAL
    }

    suspend fun setListDensity(density: ListDensity) {
        dataStore.edit { it[KEY_LIST_DENSITY] = density.name }
    }

    val previewLines: Flow<PreviewLines> = dataStore.data.map { prefs ->
        prefs[KEY_PREVIEW_LINES]?.let { runCatching { PreviewLines.valueOf(it) }.getOrNull() }
            ?: PreviewLines.ONE
    }

    suspend fun setPreviewLines(value: PreviewLines) {
        dataStore.edit { it[KEY_PREVIEW_LINES] = value.name }
    }

    /** Whether an unread row in the message list carries a background of its own on top of its bold
     *  text (#141). ON by default — it is what the app does today. Off, an unread row is painted
     *  exactly like a read one and the bold text is the only difference again; the weight itself,
     *  the icons and the folder counts are not touched either way. */
    val unreadTint: Flow<Boolean> = dataStore.data.map(::unreadTintFrom)

    suspend fun setUnreadTint(enabled: Boolean) {
        dataStore.edit { it[KEY_UNREAD_TINT] = enabled }
    }

    /** Whether the dark theme sits on a black background, for OLED panels (#117). OFF by default.
     *  It only ever touches the DARK scheme: in light theme the value is stored and inert. The
     *  elevated surfaces are compressed toward black rather than flattened onto it, so the app is
     *  not uniformly black — see `pulledToBlack`. */
    val pureBlack: Flow<Boolean> = dataStore.data.map(::pureBlackFrom)

    suspend fun setPureBlack(enabled: Boolean) {
        dataStore.edit { it[KEY_PURE_BLACK] = enabled }
    }

    val swipeRightAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_RIGHT, SwipeAction.TOGGLE_READ)
    val swipeLeftAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_LEFT, SwipeAction.DELETE)

    suspend fun setSwipeRightAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_RIGHT] = action.name }
    }

    suspend fun setSwipeLeftAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_LEFT] = action.name }
    }

    /** Deduped: DataStore republishes the WHOLE `Preferences` on every write, whatever key was
     *  touched, so without this an unrelated setting (an image allow-list entry, a swipe action)
     *  re-emits the sort order that never changed — and the browse list is built off this flow, so
     *  each such re-emission threw away the running pager and started it at the first page again. */
    val sortOrder: Flow<SortOrder> = dataStore.data.map { prefs ->
        prefs[KEY_SORT_ORDER]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.DATE_DESC
    }.distinctUntilChanged()

    suspend fun setSortOrder(order: SortOrder) {
        dataStore.edit { it[KEY_SORT_ORDER] = order.name }
    }

    /** Collapse threads into one conversation row in the list (on by default). Deduped for the
     *  same reason as [sortOrder]: it is the other settings flow the browse list's paging key is
     *  built from, so an equal re-emission here rebuilt the pager on its own. */
    val conversationView: Flow<Boolean> = dataStore.data.map { it[KEY_CONVERSATION_VIEW] ?: true }
        .distinctUntilChanged()

    suspend fun setConversationView(enabled: Boolean) {
        dataStore.edit { it[KEY_CONVERSATION_VIEW] = enabled }
    }

    /** Mark a message as read when deleting it, so Trash doesn't accumulate unread
     *  badges (off by default — deletion doesn't touch flags unless opted in). */
    val markReadOnDelete: Flow<Boolean> = dataStore.data.map { it[KEY_MARK_READ_ON_DELETE] ?: false }

    suspend fun setMarkReadOnDelete(enabled: Boolean) {
        dataStore.edit { it[KEY_MARK_READ_ON_DELETE] = enabled }
    }

    /** Mark a message as read when archiving it, so the archive doesn't accumulate unread
     *  badges (off by default — archiving doesn't touch flags unless opted in; Codeberg #67). */
    val markReadOnArchive: Flow<Boolean> = dataStore.data.map { it[KEY_MARK_READ_ON_ARCHIVE] ?: false }

    suspend fun setMarkReadOnArchive(enabled: Boolean) {
        dataStore.edit { it[KEY_MARK_READ_ON_ARCHIVE] = enabled }
    }

    /** Mark a message as read when it LEAVES the Inbox for another folder — an explicit
     *  move-to-folder (Report spam included), not a move between two other folders and not a
     *  move back INTO the Inbox (off by default; Codeberg #67). */
    val markReadOnMove: Flow<Boolean> = dataStore.data.map { it[KEY_MARK_READ_ON_MOVE] ?: false }

    suspend fun setMarkReadOnMove(enabled: Boolean) {
        dataStore.edit { it[KEY_MARK_READ_ON_MOVE] = enabled }
    }

    /** Return a conversation's archived messages to the Inbox when a new reply arrives
     *  (on by default — an intact conversation is the less surprising behaviour; opt out for
     *  strict zero-inbox; Codeberg #50). */
    val unarchiveOnReply: Flow<Boolean> = dataStore.data.map { it[KEY_UNARCHIVE_ON_REPLY] ?: true }

    suspend fun setUnarchiveOnReply(enabled: Boolean) {
        dataStore.edit { it[KEY_UNARCHIVE_ON_REPLY] = enabled }
    }

    /** Whether a reply or a forward also opens with the signature in its body. Off by default: a
     *  new message signs off, a reply usually carries the sign-off of the thread already. The
     *  signature itself is per identity (Accounts → identity); this only says WHEN it is inserted. */
    val signatureOnReplies: Flow<Boolean> = dataStore.data.map { it[KEY_SIGNATURE_ON_REPLIES] ?: false }

    suspend fun setSignatureOnReplies(enabled: Boolean) {
        dataStore.edit { it[KEY_SIGNATURE_ON_REPLIES] = enabled }
    }

    /** Whether the signature sits BELOW the quoted text in a reply rather than above it. Off by
     *  default (above the quote — the majority default, and where the reader looks first).
     *  Independent of [signatureOnReplies]: neither disables the other. */
    val signatureBelowQuote: Flow<Boolean> = dataStore.data.map { it[KEY_SIGNATURE_BELOW_QUOTE] ?: false }

    suspend fun setSignatureBelowQuote(enabled: Boolean) {
        dataStore.edit { it[KEY_SIGNATURE_BELOW_QUOTE] = enabled }
    }

    /** Whether the composer puts the standard "-- " delimiter line above the signature. ON by
     *  default: that line is what other mail apps recognise a signature by, so it stays there
     *  unless the user says otherwise. Turned off, the signature field holds EXACTLY what goes
     *  into the message, so any separator — or none at all — can be typed there (Codeberg #90).
     *  Independent of [signatureOnReplies] and [signatureBelowQuote]: those say when and where
     *  the signature is inserted, this one says what the block looks like. */
    val signatureDelimiter: Flow<Boolean> = dataStore.data.map { it[KEY_SIGNATURE_DELIMITER] ?: true }

    suspend fun setSignatureDelimiter(enabled: Boolean) {
        dataStore.edit { it[KEY_SIGNATURE_DELIMITER] = enabled }
    }

    /** Whether the reader shows the Reply/Forward bar along the bottom of a message. ON by default
     *  — it is what the app has always done. Off, both of its actions are still one tap away in the
     *  top bar (Reply has its own icon; Reply all and Forward open the menu beside it), so nothing
     *  becomes unreachable (Codeberg #63). Global, not per account: the reader is shared by the
     *  unified inbox, and a per-account answer would make the bar appear and disappear while moving
     *  from one message to the next in one list. */
    val replyBar: Flow<Boolean> = dataStore.data.map(::replyBarFrom)

    suspend fun setReplyBar(enabled: Boolean) {
        dataStore.edit { it[KEY_REPLY_BAR] = enabled }
    }

    /** Reading text size for the message body. */
    val messageTextSize: Flow<MessageTextSize> = dataStore.data.map { prefs ->
        prefs[KEY_MESSAGE_TEXT_SIZE]?.let { runCatching { MessageTextSize.valueOf(it) }.getOrNull() }
            ?: MessageTextSize.NORMAL
    }

    suspend fun setMessageTextSize(size: MessageTextSize) {
        dataStore.edit { it[KEY_MESSAGE_TEXT_SIZE] = size.name }
    }

    /** Whether recipient autocomplete may read the device's contacts (off by default). */
    val contactSuggestions: Flow<Boolean> = dataStore.data.map { it[KEY_CONTACT_SUGGESTIONS] ?: false }

    suspend fun setContactSuggestions(enabled: Boolean) {
        dataStore.edit { it[KEY_CONTACT_SUGGESTIONS] = enabled }
    }

    /** Whether the first-launch privacy welcome has been shown (shown once, before adding an account). */
    val hasSeenWelcome: Flow<Boolean> = dataStore.data.map { it[KEY_HAS_SEEN_WELCOME] ?: false }

    suspend fun setHasSeenWelcome(seen: Boolean) {
        dataStore.edit { it[KEY_HAS_SEEN_WELCOME] = seen }
    }

    /** Whether the contacts-permission priming has already been offered at compose (shown once). */
    val hasPrimedContacts: Flow<Boolean> = dataStore.data.map { it[KEY_HAS_PRIMED_CONTACTS] ?: false }

    suspend fun setHasPrimedContacts(primed: Boolean) {
        dataStore.edit { it[KEY_HAS_PRIMED_CONTACTS] = primed }
    }

    /** Whether to remove tracking query params (utm_*, fbclid, …) from tapped links (on by default). */
    val stripTrackingParams: Flow<Boolean> = dataStore.data.map { it[KEY_STRIP_TRACKING] ?: true }

    suspend fun setStripTrackingParams(enabled: Boolean) {
        dataStore.edit { it[KEY_STRIP_TRACKING] = enabled }
    }

    /** Whether to show the destination and ask before opening a tapped link (off by default). */
    val confirmLinks: Flow<Boolean> = dataStore.data.map { it[KEY_CONFIRM_LINKS] ?: false }

    suspend fun setConfirmLinks(enabled: Boolean) {
        dataStore.edit { it[KEY_CONFIRM_LINKS] = enabled }
    }

    /** Sender addresses (lower-cased) whose remote images load automatically. */
    val imageAllowlist: Flow<Set<String>> = dataStore.data.map { it[KEY_IMAGE_ALLOWLIST] ?: emptySet() }

    suspend fun setImageAllowed(sender: String, allowed: Boolean) {
        val key = sender.trim().lowercase()
        if (key.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_IMAGE_ALLOWLIST] ?: emptySet()
            prefs[KEY_IMAGE_ALLOWLIST] = if (allowed) current + key else current - key
        }
    }

    suspend fun clearImageAllowlist() {
        dataStore.edit { it.remove(KEY_IMAGE_ALLOWLIST) }
    }

    suspend fun setImageAllowlist(senders: Set<String>) {
        val cleaned = senders.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        dataStore.edit { it[KEY_IMAGE_ALLOWLIST] = cleaned }
    }

    /** New-mail delivery mode; INSTANT (today's behavior) by default. */
    val deliveryMode: Flow<DeliveryMode> = dataStore.data.map { prefs ->
        prefs[KEY_DELIVERY_MODE]?.let { runCatching { DeliveryMode.valueOf(it) }.getOrNull() }
            ?: DeliveryMode.INSTANT
    }

    suspend fun setDeliveryMode(mode: DeliveryMode) {
        dataStore.edit { it[KEY_DELIVERY_MODE] = mode.name }
    }

    /** What a new-mail notification shows; sender + subject by default (Codeberg #25). */
    val notificationContent: Flow<NotificationContent> = dataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATION_CONTENT]?.let { runCatching { NotificationContent.valueOf(it) }.getOrNull() }
            ?: NotificationContent.SENDER_AND_SUBJECT
    }

    suspend fun setNotificationContent(mode: NotificationContent) {
        dataStore.edit { it[KEY_NOTIFICATION_CONTENT] = mode.name }
    }

    /** Captures every DataStore-backed preference into a portable [SettingsBackup]. */
    suspend fun snapshotBackup(): SettingsBackup = SettingsBackup(
        themeMode = themeMode.first().name,
        dynamicColor = dynamicColor.first(),
        listDensity = listDensity.first().name,
        previewLines = previewLines.first().name,
        swipeRight = swipeRightAction.first().name,
        swipeLeft = swipeLeftAction.first().name,
        sortOrder = sortOrder.first().name,
        contactSuggestions = contactSuggestions.first(),
        stripTracking = stripTrackingParams.first(),
        confirmLinks = confirmLinks.first(),
        imageAllowlist = imageAllowlist.first().toList(),
        quietHoursEnabled = quietHoursEnabled.first(),
        quietHoursStart = quietHoursStart.first(),
        quietHoursEnd = quietHoursEnd.first(),
        conversationView = conversationView.first(),
        messageTextSize = messageTextSize.first().name,
        markReadOnDelete = markReadOnDelete.first(),
        markReadOnArchive = markReadOnArchive.first(),
        markReadOnMove = markReadOnMove.first(),
        unarchiveOnReply = unarchiveOnReply.first(),
        signatureOnReplies = signatureOnReplies.first(),
        signatureBelowQuote = signatureBelowQuote.first(),
        signatureDelimiter = signatureDelimiter.first(),
        replyBar = replyBar.first(),
        unreadTint = unreadTint.first(),
        pureBlack = pureBlack.first(),
        deliveryMode = deliveryMode.first().name,
        notificationContent = notificationContent.first().name,
    )

    /** Applies the DataStore-backed fields of [backup]; unknown enum values are skipped. */
    suspend fun restoreBackup(backup: SettingsBackup) {
        backup.themeMode?.let { v -> runCatching { ThemeMode.valueOf(v) }.getOrNull()?.let { setThemeMode(it) } }
        backup.dynamicColor?.let { setDynamicColor(it) }
        backup.listDensity?.let { v -> runCatching { ListDensity.valueOf(v) }.getOrNull()?.let { setListDensity(it) } }
        backup.previewLines?.let { v -> runCatching { PreviewLines.valueOf(v) }.getOrNull()?.let { setPreviewLines(it) } }
        backup.swipeRight?.let { v -> runCatching { SwipeAction.valueOf(v) }.getOrNull()?.let { setSwipeRightAction(it) } }
        backup.swipeLeft?.let { v -> runCatching { SwipeAction.valueOf(v) }.getOrNull()?.let { setSwipeLeftAction(it) } }
        backup.sortOrder?.let { v -> runCatching { SortOrder.valueOf(v) }.getOrNull()?.let { setSortOrder(it) } }
        backup.contactSuggestions?.let { setContactSuggestions(it) }
        backup.stripTracking?.let { setStripTrackingParams(it) }
        backup.confirmLinks?.let { setConfirmLinks(it) }
        backup.imageAllowlist?.let { setImageAllowlist(it.toSet()) }
        backup.quietHoursEnabled?.let { setQuietHoursEnabled(it) }
        backup.quietHoursStart?.let { setQuietHoursStart(it) }
        backup.quietHoursEnd?.let { setQuietHoursEnd(it) }
        backup.conversationView?.let { setConversationView(it) }
        backup.messageTextSize?.let { v -> runCatching { MessageTextSize.valueOf(v) }.getOrNull()?.let { setMessageTextSize(it) } }
        backup.markReadOnDelete?.let { setMarkReadOnDelete(it) }
        backup.markReadOnArchive?.let { setMarkReadOnArchive(it) }
        backup.markReadOnMove?.let { setMarkReadOnMove(it) }
        backup.unarchiveOnReply?.let { setUnarchiveOnReply(it) }
        backup.signatureOnReplies?.let { setSignatureOnReplies(it) }
        backup.signatureBelowQuote?.let { setSignatureBelowQuote(it) }
        backup.signatureDelimiter?.let { setSignatureDelimiter(it) }
        backup.replyBar?.let { setReplyBar(it) }
        backup.unreadTint?.let { setUnreadTint(it) }
        backup.pureBlack?.let { setPureBlack(it) }
        backup.deliveryMode?.let { v -> runCatching { DeliveryMode.valueOf(v) }.getOrNull()?.let { setDeliveryMode(it) } }
        backup.notificationContent?.let { v -> runCatching { NotificationContent.valueOf(v) }.getOrNull()?.let { setNotificationContent(it) } }
    }

    /**
     * Quiet hours — when on, new-mail notifications still arrive but are posted
     * silently (no sound/vibration/heads-up) during the nightly window. Off by default.
     */
    val quietHoursEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_QUIET_ENABLED] ?: false }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_QUIET_ENABLED] = enabled }
    }

    /** Window start, minutes past midnight (default 22:00). */
    val quietHoursStart: Flow<Int> = dataStore.data.map { it[KEY_QUIET_START] ?: DEFAULT_QUIET_START }

    suspend fun setQuietHoursStart(minutes: Int) {
        dataStore.edit { it[KEY_QUIET_START] = minutes.coerceIn(0, 24 * 60 - 1) }
    }

    /** Window end, minutes past midnight (default 07:00). */
    val quietHoursEnd: Flow<Int> = dataStore.data.map { it[KEY_QUIET_END] ?: DEFAULT_QUIET_END }

    suspend fun setQuietHoursEnd(minutes: Int) {
        dataStore.edit { it[KEY_QUIET_END] = minutes.coerceIn(0, 24 * 60 - 1) }
    }

    private fun swipeFlow(key: Preferences.Key<String>, default: SwipeAction): Flow<SwipeAction> =
        dataStore.data.map { prefs ->
            prefs[key]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: default
        }

    companion object {
        const val DEFAULT_QUIET_START = 22 * 60
        const val DEFAULT_QUIET_END = 7 * 60

        /**
         * True if [nowMinutes] (minutes past midnight) falls in the quiet window
         * [start, end), handling windows that wrap past midnight (e.g. 22:00→07:00).
         * An empty window (start == end) is never quiet.
         */
        fun isWithinQuietHours(nowMinutes: Int, start: Int, end: Int): Boolean = when {
            start == end -> false
            start < end -> nowMinutes in start until end
            else -> nowMinutes >= start || nowMinutes < end
        }

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_LIST_DENSITY = stringPreferencesKey("list_density")
        private val KEY_PREVIEW_LINES = stringPreferencesKey("preview_lines")
        private val KEY_SWIPE_RIGHT = stringPreferencesKey("swipe_right")
        private val KEY_SWIPE_LEFT = stringPreferencesKey("swipe_left")
        private val KEY_SORT_ORDER = stringPreferencesKey("sort_order")
        private val KEY_CONVERSATION_VIEW = booleanPreferencesKey("conversation_view")
        private val KEY_MARK_READ_ON_DELETE = booleanPreferencesKey("mark_read_on_delete")
        private val KEY_MARK_READ_ON_ARCHIVE = booleanPreferencesKey("mark_read_on_archive")
        private val KEY_MARK_READ_ON_MOVE = booleanPreferencesKey("mark_read_on_move")
        private val KEY_UNARCHIVE_ON_REPLY = booleanPreferencesKey("unarchive_on_reply")
        private val KEY_SIGNATURE_ON_REPLIES = booleanPreferencesKey("signature_on_replies")
        private val KEY_SIGNATURE_BELOW_QUOTE = booleanPreferencesKey("signature_below_quote")
        private val KEY_SIGNATURE_DELIMITER = booleanPreferencesKey("signature_delimiter")
        private val KEY_MESSAGE_TEXT_SIZE = stringPreferencesKey("message_text_size")
        private val KEY_CONTACT_SUGGESTIONS = booleanPreferencesKey("contact_suggestions")
        private val KEY_HAS_SEEN_WELCOME = booleanPreferencesKey("has_seen_welcome")
        private val KEY_HAS_PRIMED_CONTACTS = booleanPreferencesKey("has_primed_contacts")
        private val KEY_STRIP_TRACKING = booleanPreferencesKey("strip_tracking_params")
        private val KEY_CONFIRM_LINKS = booleanPreferencesKey("confirm_links")
        private val KEY_IMAGE_ALLOWLIST = stringSetPreferencesKey("image_allowlist")
        private val KEY_DELIVERY_MODE = stringPreferencesKey("delivery_mode")
        private val KEY_NOTIFICATION_CONTENT = stringPreferencesKey("notification_content")
        private val KEY_QUIET_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        private val KEY_QUIET_START = intPreferencesKey("quiet_hours_start")
        private val KEY_QUIET_END = intPreferencesKey("quiet_hours_end")
    }
}

/** The key the reader's Reply/Forward bar switch is stored under. Renaming it loses the setting of
 *  every user who has turned the bar off, so it is pinned by name in [replyBarFrom]'s test. */
internal val KEY_REPLY_BAR = booleanPreferencesKey("reply_bar")

/**
 * What the reader shows when nobody has touched the switch: the bar, which is what the app has
 * always done and what the setting's own subtitle promises (Codeberg #63). One definition, read by
 * [replyBarFrom] and by the settings screen's initial value.
 */
const val REPLY_BAR_DEFAULT = true

/**
 * Whether the reader shows its bottom Reply/Forward bar, read from the stored preferences.
 *
 * The LOOKUP is in here, not the flow, and that is deliberate. It was a function of the stored
 * `Boolean?` first, which left the default reachable from outside it: `replyBarFrom(prefs[KEY] ?:
 * false)` takes the bar away from every user who has never touched the switch — the exact opposite
 * of what is announced — and no test can see it, since the argument is already defaulted by the
 * time the function runs. Given the whole [Preferences] there is nothing left to pre-empt: the test
 * hands it an empty store, a store with the switch on, and one with it off.
 */
internal fun replyBarFrom(prefs: Preferences): Boolean = prefs[KEY_REPLY_BAR] ?: REPLY_BAR_DEFAULT

/** The key the unread-background switch is stored under. Renaming it loses the setting of every
 *  user who has turned the tint off, so it is pinned by name in [unreadTintFrom]'s test. */
internal val KEY_UNREAD_TINT = booleanPreferencesKey("unread_tint")

/**
 * What the message list does when nobody has touched the switch: tint unread rows, which is what
 * the app does today (#141). One definition, read by [unreadTintFrom], by the settings screen's
 * initial value, by the activity's first frame and by the CompositionLocal's own fallback — the
 * last three are the copies that make a literal here dangerous.
 */
const val UNREAD_TINT_DEFAULT = true

/**
 * Whether unread rows carry a background of their own, read from the stored preferences.
 *
 * The LOOKUP is in here and not in the flow, for the reason spelled out on [replyBarFrom]: given
 * the whole [Preferences] there is no way to defeat the default from outside, and the test can hand
 * it an empty store, a store with the switch on, and one with it off.
 */
internal fun unreadTintFrom(prefs: Preferences): Boolean = prefs[KEY_UNREAD_TINT] ?: UNREAD_TINT_DEFAULT

/** The key the pure-black switch is stored under. Renaming it loses the setting of every user who
 *  has turned the black background on, so it is pinned by name in [pureBlackFrom]'s test. */
internal val KEY_PURE_BLACK = booleanPreferencesKey("pure_black")

/**
 * What the dark theme does when nobody has touched the switch: keep the Pelagic surfaces (#117).
 * OFF, deliberately — the black background is a preference, not the product's theme, and a default
 * of `true` would repaint every dark-theme user's app on update without anyone asking for it. One
 * definition, read by [pureBlackFrom], by the settings screen's initial value and by the activity's
 * first frame — the last two are the copies that make a literal there dangerous.
 */
const val PURE_BLACK_DEFAULT = false

/**
 * Whether the dark theme sits on a black background, read from the stored preferences.
 *
 * The LOOKUP is in here and not in the flow, for the reason spelled out on [replyBarFrom]: given
 * the whole [Preferences] there is no way to defeat the default from outside, and the test can hand
 * it an empty store, a store with the switch on, and one with it off.
 */
internal fun pureBlackFrom(prefs: Preferences): Boolean = prefs[KEY_PURE_BLACK] ?: PURE_BLACK_DEFAULT
