package app.gridlink.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.gridlink.core.data.db.EmailKeywords
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the app picks light vs dark colours. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Which of the Gridlink screens' three palettes is pinned, or [AUTO] to follow the day.
 *
 * Deliberately a data-layer enum of its own rather than the UI's `GridlinkMode?`, for the same
 * reason [ThemeMode] has SYSTEM instead of a nullable: "follow the clock" is a choice the user
 * made and a value worth storing, not the absence of one. The UI maps AUTO to a null override at
 * the boundary. Persisted BY NAME, so an unknown value from a newer build reads back as AUTO
 * rather than throwing.
 */
enum class GridlinkPalette { AUTO, DAY, NIGHT, OLED }

/** Vertical density of message-list rows. */
enum class ListDensity { COMPACT, NORMAL, SPACED }

/** How much of each message's body preview to show in the list. */
enum class PreviewLines(val lines: Int) { NONE(0), ONE(1), THREE(3), FIVE(5) }

/**
 * An action bound to a swipe gesture on a message row.
 *
 * ⚠️ Persisted BY NAME, so appending is safe and reordering is not. SNOOZE was appended rather
 * than slotted in beside ARCHIVE for exactly that reason, and it is deliberately NOT a default:
 * a swipe someone already has in their fingers must not change meaning under them.
 */
enum class SwipeAction { NONE, TOGGLE_READ, DELETE, ARCHIVE, FLAG, SNOOZE }

/**
 * An action the reader may put on the open thread's bottom bar.
 *
 * 🔴 Tate, 2026-08-10: "the dynamic control bar at the bottom in unfolded mode should be
 * customizable, make that an option in settings", and he picked the fixed-order variant over a
 * drag-to-reorder one. So this enum IS the order: the settings screen lists it top to bottom and
 * the bar fills its slots from the enabled ones in the same sequence. Nothing anywhere stores a
 * position, which is what makes the stored value a plain Set and makes appending an action here a
 * one-line change rather than a migration.
 *
 * ⚠️ Entries are persisted BY NAME, so appending is safe and reordering is NOT: moving an entry
 * changes where an already-enabled action lands on everybody's bar. Append.
 *
 * REPLY is deliberately absent. It owns the accent circle beside the bar on every thread and is the
 * one action this app will not let you lose; a switch for it would offer to leave a reading screen
 * with no way to answer the mail. Everything else here is optional, [DEFAULTS] included.
 */
enum class ThreadToolbarAction {
    // 🔴 Forward and Archive lead, and that is not a style choice: with [DEFAULTS] enabled they are
    // the two that fit in front of More, which is precisely the bar this app already shipped. Put
    // REPLY_ALL first and an untouched install opens to Reply all / Forward / More with Archive
    // demoted a tap further away, which is a change nobody asked for dressed up as a default.
    FORWARD,
    ARCHIVE,
    REPLY_ALL,
    DELETE,
    MOVE,
    MARK_UNREAD,
    STAR,
    PRINT,
    JUNK,

    // Appended last on purpose. Order here is bar order, so anything ahead of JUNK would push a
    // shipped action off the visible slots of every untouched install to make room for a new one.
    // Last means Snooze arrives under More, which is where a rarely-used action belongs anyway.
    SNOOZE,
    ;

    companion object {
        /**
         * What the bar held before it was customisable, exactly.
         *
         * 🔴 Chosen to reproduce the shipped bar rather than to be a nicer default: Forward and
         * Archive take the two visible slots, Reply all and Junk sit under More, and an install
         * that never opens this setting sees no change at all. A default set that "improved" the
         * bar would rearrange the controls of every existing reader to settle an argument they were
         * not having.
         *
         * Star is off despite being on Tate's list, because the lit star beside the subject is
         * already on screen whenever this bar is; enabling it puts the same toggle in two places.
         * It is offered because he asked for it, not recommended.
         *
         * SNOOZE is on despite being new, and that does not move anything: it declares last, so it
         * lands under More beside Reply all and Junk while Forward and Archive keep the two visible
         * slots. Off by default it would ship a snooze list with no way to put mail in it, which is
         * the hole this feature exists to close.
         */
        val DEFAULTS: Set<ThreadToolbarAction> = setOf(FORWARD, ARCHIVE, REPLY_ALL, JUNK, SNOOZE)
    }
}

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
 * needed); BATTERY_SAVER drops every connection Gridlink holds and relies on the
 * 30-minute periodic check — UnifiedPush subscriptions stay active either way,
 * they cost the app nothing.
 */
enum class DeliveryMode { INSTANT, BATTERY_SAVER }

/** How much a new-mail notification reveals on the lock screen (Codeberg #25). */
enum class NotificationContent { SENDER_AND_SUBJECT, SENDER_ONLY, NONE }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Reactive app-preferences store backed by a Preferences [DataStore]. Kept
 * separate from [app.gridlink.core.data.account.AccountStore], which holds accounts,
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

    /**
     * The Gridlink screens' palette pill (`Auto · Day · Night · OLED`).
     *
     * Separate from [themeMode], which is upstream's light/dark switch for the settings and
     * account screens. The two are not the same control and must not be collapsed into one: the
     * Gridlink ladder has a third rung (OLED) that light/dark cannot express, and the settings
     * screens have no aurora to put it on.
     */
    val gridlinkPalette: Flow<GridlinkPalette> = dataStore.data.map { prefs ->
        prefs[KEY_GRIDLINK_PALETTE]?.let { runCatching { GridlinkPalette.valueOf(it) }.getOrNull() }
            ?: GridlinkPalette.AUTO
    }

    suspend fun setGridlinkPalette(palette: GridlinkPalette) {
        dataStore.edit { it[KEY_GRIDLINK_PALETTE] = palette.name }
    }

    /** Use Material You (wallpaper-derived) colours instead of Gridlink's brand palette. Off by default. */
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

    /**
     * How many lines of body preview a message row carries.
     *
     * 🔴 The default is NONE, and it was ONE until the setting was wired up (2026-08-14). Until then
     * nothing in the UI read this flow, so the stored ONE was inert and every install in existence
     * was looking at a two-line row. Honouring ONE at the moment the wiring landed would have handed
     * a third line to every user who had never opened this setting, which is the list they already
     * know silently rearranging itself on an update. NONE is what they are actually looking at, so
     * NONE is what an untouched install keeps; the preview is now something you ask for.
     */
    val previewLines: Flow<PreviewLines> = dataStore.data.map { prefs ->
        prefs[KEY_PREVIEW_LINES]?.let { runCatching { PreviewLines.valueOf(it) }.getOrNull() }
            ?: PreviewLines.NONE
    }

    suspend fun setPreviewLines(value: PreviewLines) {
        dataStore.edit { it[KEY_PREVIEW_LINES] = value.name }
    }

    /**
     * The three swipe slots. Right, shallow left, and left past 60%.
     *
     * 🔴 Three, not two, and the defaults changed with them (2026-08-12). These keys were written by
     * Settings and read by nobody: the list hardcoded archive right, mark-unread on the shallow left
     * and delete past 60%. Wiring them up meant matching the store to the gesture that actually
     * exists, which has always had a two-stage left swipe, so a third key was added and the defaults
     * were set to what the app already does. Nothing anyone sees changes on upgrade, because nothing
     * was reading the old defaults to change away from.
     *
     * ⚠️ [KEY_SWIPE_LEFT] keeps its name and now means the SHALLOW left stage, whose old default was
     * DELETE. Anyone who explicitly picked Delete there gets it at 25% instead of 60%, which is what
     * they asked for; anyone who never touched it gets the shipped gesture.
     */
    val swipeRightAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_RIGHT, SwipeAction.ARCHIVE)
    val swipeLeftAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_LEFT, SwipeAction.TOGGLE_READ)
    val swipeLeftFarAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_LEFT_FAR, SwipeAction.DELETE)

    suspend fun setSwipeRightAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_RIGHT] = action.name }
    }

    suspend fun setSwipeLeftAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_LEFT] = action.name }
    }

    suspend fun setSwipeLeftFarAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_LEFT_FAR] = action.name }
    }

    val sortOrder: Flow<SortOrder> = dataStore.data.map { prefs ->
        prefs[KEY_SORT_ORDER]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.DATE_DESC
    }

    suspend fun setSortOrder(order: SortOrder) {
        dataStore.edit { it[KEY_SORT_ORDER] = order.name }
    }

    /**
     * Which actions the open thread's bottom bar may offer, in [ThreadToolbarAction]'s own order.
     *
     * 🔴 A Set, not a List, and that is the fixed-order decision made storable: order comes from the
     * enum, so the only thing worth persisting is membership. It also means an unknown name (a build
     * that removed an action, or a backup from a newer one) is simply dropped instead of leaving a
     * hole at a stored position.
     *
     * ⚠️ An empty stored set is a real answer and NOT the same as absent. Turning every switch off
     * leaves the bar with nothing but More, which is a legitimate thing to want; only a key that has
     * never been written falls back to [ThreadToolbarAction.DEFAULTS]. The `?:` therefore hangs off
     * the key lookup, not off `isEmpty()`.
     */
    val threadToolbarActions: Flow<Set<ThreadToolbarAction>> = dataStore.data.map { prefs ->
        prefs[KEY_THREAD_TOOLBAR]
            ?.mapNotNullTo(mutableSetOf()) { runCatching { ThreadToolbarAction.valueOf(it) }.getOrNull() }
            ?: ThreadToolbarAction.DEFAULTS
    }

    suspend fun setThreadToolbarActions(actions: Set<ThreadToolbarAction>) {
        dataStore.edit { prefs -> prefs[KEY_THREAD_TOOLBAR] = actions.mapTo(mutableSetOf()) { it.name } }
    }

    /** Collapse threads into one conversation row in the list (on by default). */
    val conversationView: Flow<Boolean> = dataStore.data.map { it[KEY_CONVERSATION_VIEW] ?: true }

    suspend fun setConversationView(enabled: Boolean) {
        dataStore.edit { it[KEY_CONVERSATION_VIEW] = enabled }
    }

    /**
     * Show every account's inbox merged into one list ("All inboxes" in the drawer). Off by default.
     *
     * ## Why this is remembered and the open FOLDER is not
     * Both are reached the same way, by a tap in the drawer, and it would be reasonable to call this
     * navigation and let it reset. It does not, because it is not "where am I" but "how do I read my
     * mail": somebody with three accounts who wants them merged wants them merged tomorrow too, and
     * a mode that quietly reverts on every cold start is a mode nobody trusts enough to use.
     *
     * 🔴 Deliberately NOT in the settings backup, unlike [conversationView] next door. This one is
     * only meaningful with more than one account, and a backup carries settings, not accounts — so
     * restoring it onto a fresh single-account install would arm a mode with nothing to merge. The
     * drawer row that sets it is hidden there for the same reason, and the two must not disagree.
     */
    val unifiedInbox: Flow<Boolean> = dataStore.data.map { it[KEY_UNIFIED_INBOX] ?: false }

    suspend fun setUnifiedInbox(enabled: Boolean) {
        dataStore.edit { it[KEY_UNIFIED_INBOX] = enabled }
    }

    /**
     * Gather automated senders (no-reply@, notifications@, and the rest of the list in
     * `GridlinkMailMapping`) into one collapsed row above the timeline.
     *
     * 🔴 OFF by default, and that default is the whole point. It shipped on, and Tate's verdict
     * was "lose the automated auto-sorter thing... by default it should just show mail." A list that
     * silently reorganises itself has to be understood before it can be read, and the reader who
     * wants that grouping can ask for it. The reader who does not never agreed to it.
     */
    val bundleAutomated: Flow<Boolean> = dataStore.data.map { it[KEY_BUNDLE_AUTOMATED] ?: false }

    suspend fun setBundleAutomated(enabled: Boolean) {
        dataStore.edit { it[KEY_BUNDLE_AUTOMATED] = enabled }
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

    /**
     * Whether the account's CardDAV/CalDAV cache is published into Android's own contacts and
     * calendar providers, so caller ID and the system Calendar app see it (off by default).
     *
     * 🔴 Off is not a neutral default here, it is the safe one. Turning it on registers an account
     * with the system and writes into two databases every other app can read; turning it back off
     * unregisters that account, which is what deletes the rows again. Nothing should do either
     * without being asked. Read by `app.gridlink.sync.SystemMirror`.
     */
    val systemAccountMirror: Flow<Boolean> = dataStore.data.map { it[KEY_SYSTEM_ACCOUNT_MIRROR] ?: false }

    suspend fun setSystemAccountMirror(enabled: Boolean) {
        dataStore.edit { it[KEY_SYSTEM_ACCOUNT_MIRROR] = enabled }
    }

    /** Whether the first-launch privacy welcome has been shown (shown once, before adding an account). */
    val hasSeenWelcome: Flow<Boolean> = dataStore.data.map { it[KEY_HAS_SEEN_WELCOME] ?: false }

    suspend fun setHasSeenWelcome(seen: Boolean) {
        dataStore.edit { it[KEY_HAS_SEEN_WELCOME] = seen }
    }

    /**
     * How many accounts existed the last time the animated intro played, or `null` if it has never
     * played on this install.
     *
     * ## Why a count and not a "seen" boolean
     *
     * Tate, 2026-08-10: *"i like the video but loading it every time seems like a lot - maybe
     * only the first time after account added? every app open is too much tho."* So the intro is due
     * exactly twice in a normal life: on the very first launch (nothing stored yet, `null`), and on
     * the first launch after the account list GREW past what the intro last saw.
     *
     * A count gets that with no hook in the add path at all. `null` covers first launch; `count >
     * stored` covers every account added afterwards, however it was added — the JMAP form, the IMAP
     * form, the OAuth flow, a settings import, or any fourth path somebody writes next year. There
     * is nothing to remember to call, so there is nothing a new path can forget, which is the same
     * reasoning `addAccountThenPrime` is built on.
     *
     * ⚠️ Removing an account and adding it back does NOT replay it: the count dips and returns to a
     * number the intro has already seen. That is the behaviour worth having. Re-authenticating a
     * mailbox is not a new mailbox, and a brand animation in front of a repair is the app
     * congratulating itself over the user's problem.
     *
     * 🔴 Deliberately NOT in [SettingsBackup], for the same reason [hasSeenWelcome] is not: it
     * describes what this install has shown this user, not what the user chose. Restoring it onto a
     * new device would suppress the intro on a device that has never played it.
     */
    val introSeenAccountCount: Flow<Int?> = dataStore.data.map { it[KEY_INTRO_SEEN_ACCOUNTS] }

    suspend fun setIntroSeenAccountCount(count: Int) {
        dataStore.edit { it[KEY_INTRO_SEEN_ACCOUNTS] = count }
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

    /**
     * The reader's colour-coded tags: wire keyword, display label, palette colour.
     *
     * 🔴 Only the definitions live here. WHICH messages carry a tag is the server's business and
     * is cached in `emails.keywordsJson`; this is the label and the colour, which no mail protocol
     * carries (see [MailTag]). That split is why a tag can be recoloured in one tap without a
     * single message being touched.
     *
     * Ordered, and the order is the reader's: new tags append. Everything that lists tags reads
     * this flow, so the manager, the chip picker and the filter row can never disagree about which
     * tags exist or what order they come in.
     */
    val mailTags: Flow<List<MailTag>> = dataStore.data.map { MailTagCodec.decode(it[KEY_MAIL_TAGS]) }

    /**
     * Create a tag from a label the reader typed, or return the existing one if its wire name is
     * already taken.
     *
     * ⚠️ Collisions are resolved TOWARDS the existing tag rather than by minting "work-2". Two
     * different labels can slug to the same keyword ("Follow up" and "follow-up"), and the wire
     * name is the identity as far as every server and every other client is concerned, so two tags
     * sharing one would be two chips that apply and remove each other.
     *
     * Returns null when the label has no usable wire name at all, which the caller shows as
     * "pick another name".
     */
    suspend fun createMailTag(label: String, color: TagColor): MailTag? {
        val keyword = EmailKeywords.toKeyword(label) ?: return null
        val existing = mailTags.first()
        existing.firstOrNull { it.keyword == keyword }?.let { return it }
        val tag = MailTag(keyword = keyword, label = label.trim(), color = color.name)
        dataStore.edit { it[KEY_MAIL_TAGS] = MailTagCodec.encode(existing + tag) }
        return tag
    }

    /**
     * Rename or recolour an existing tag, matched by its wire [MailTag.keyword].
     *
     * 🔴 The keyword is NOT rewritten by a rename, and cannot be: it is on every message that
     * carries the tag, on the server. Renaming changes what the chip reads on this device only.
     * Anything that wants a different wire name is a different tag, applied and removed message by
     * message, which is what the manager makes the reader do explicitly instead of pretending a
     * rename could do it silently.
     */
    suspend fun updateMailTag(keyword: String, label: String, color: TagColor) {
        val updated = mailTags.first().map {
            if (it.keyword == keyword) it.copy(label = label.trim(), color = color.name) else it
        }
        dataStore.edit { it[KEY_MAIL_TAGS] = MailTagCodec.encode(updated) }
    }

    /**
     * Forget a tag's definition.
     *
     * ⚠️ Forget, not un-tag: the messages carrying the keyword keep carrying it, here and on every
     * other client, and their chips fall back to the wire name in an auto-assigned colour. Deleting
     * the definition cannot strip a keyword off mail that may not even be cached, and a delete that
     * quietly rewrote as much of the mailbox as it could reach would be the more surprising of the
     * two behaviours. The manager says which it is.
     */
    suspend fun deleteMailTag(keyword: String) {
        val remaining = mailTags.first().filterNot { it.keyword == keyword }
        dataStore.edit { it[KEY_MAIL_TAGS] = MailTagCodec.encode(remaining) }
    }

    /** Replace the whole set (a settings restore, and the tag manager's reorder if it gains one). */
    suspend fun setMailTags(tags: List<MailTag>) {
        dataStore.edit { it[KEY_MAIL_TAGS] = MailTagCodec.encode(tags) }
    }

    /** Captures every DataStore-backed preference into a portable [SettingsBackup]. */
    suspend fun snapshotBackup(): SettingsBackup = SettingsBackup(
        themeMode = themeMode.first().name,
        dynamicColor = dynamicColor.first(),
        listDensity = listDensity.first().name,
        previewLines = previewLines.first().name,
        swipeRight = swipeRightAction.first().name,
        swipeLeft = swipeLeftAction.first().name,
        swipeLeftFar = swipeLeftFarAction.first().name,
        sortOrder = sortOrder.first().name,
        contactSuggestions = contactSuggestions.first(),
        stripTracking = stripTrackingParams.first(),
        confirmLinks = confirmLinks.first(),
        imageAllowlist = imageAllowlist.first().toList(),
        quietHoursEnabled = quietHoursEnabled.first(),
        quietHoursStart = quietHoursStart.first(),
        quietHoursEnd = quietHoursEnd.first(),
        conversationView = conversationView.first(),
        // Exported, unlike `introSeenAccountCount` next door. That one records what THIS install has
        // played and would be a lie on a new device; this one is a choice the reader made about how
        // their mail app is laid out, which is the whole category this file exists to carry.
        threadToolbarActions = threadToolbarActions.first().map { it.name },
        bundleAutomated = bundleAutomated.first(),
        messageTextSize = messageTextSize.first().name,
        markReadOnDelete = markReadOnDelete.first(),
        markReadOnArchive = markReadOnArchive.first(),
        markReadOnMove = markReadOnMove.first(),
        unarchiveOnReply = unarchiveOnReply.first(),
        signatureOnReplies = signatureOnReplies.first(),
        signatureBelowQuote = signatureBelowQuote.first(),
        signatureDelimiter = signatureDelimiter.first(),
        deliveryMode = deliveryMode.first().name,
        notificationContent = notificationContent.first().name,
        // The label and the colour of every tag — the half of a tag that no mail protocol carries,
        // and therefore the half that a fresh install cannot get back from the server.
        mailTags = mailTags.first(),
    )

    /** Applies the DataStore-backed fields of [backup]; unknown enum values are skipped. */
    suspend fun restoreBackup(backup: SettingsBackup) {
        backup.themeMode?.let { v -> runCatching { ThemeMode.valueOf(v) }.getOrNull()?.let { setThemeMode(it) } }
        backup.dynamicColor?.let { setDynamicColor(it) }
        backup.listDensity?.let { v -> runCatching { ListDensity.valueOf(v) }.getOrNull()?.let { setListDensity(it) } }
        backup.previewLines?.let { v -> runCatching { PreviewLines.valueOf(v) }.getOrNull()?.let { setPreviewLines(it) } }
        backup.swipeRight?.let { v -> runCatching { SwipeAction.valueOf(v) }.getOrNull()?.let { setSwipeRightAction(it) } }
        backup.swipeLeft?.let { v -> runCatching { SwipeAction.valueOf(v) }.getOrNull()?.let { setSwipeLeftAction(it) } }
        backup.swipeLeftFar?.let { v -> runCatching { SwipeAction.valueOf(v) }.getOrNull()?.let { setSwipeLeftFarAction(it) } }
        backup.sortOrder?.let { v -> runCatching { SortOrder.valueOf(v) }.getOrNull()?.let { setSortOrder(it) } }
        backup.contactSuggestions?.let { setContactSuggestions(it) }
        backup.stripTracking?.let { setStripTrackingParams(it) }
        backup.confirmLinks?.let { setConfirmLinks(it) }
        backup.imageAllowlist?.let { setImageAllowlist(it.toSet()) }
        backup.quietHoursEnabled?.let { setQuietHoursEnabled(it) }
        backup.quietHoursStart?.let { setQuietHoursStart(it) }
        backup.quietHoursEnd?.let { setQuietHoursEnd(it) }
        backup.conversationView?.let { setConversationView(it) }
        // 🔴 Unknown names are dropped, an empty result is still written. A backup naming only
        // actions this build no longer has restores an EMPTY bar rather than silently reverting to
        // the defaults, because the reader did choose to have those and no others; the defaults are
        // for a key nobody has ever set, and a restore is somebody setting it.
        backup.threadToolbarActions?.let { names ->
            setThreadToolbarActions(
                names.mapNotNullTo(mutableSetOf()) { runCatching { ThreadToolbarAction.valueOf(it) }.getOrNull() },
            )
        }
        backup.bundleAutomated?.let { setBundleAutomated(it) }
        backup.messageTextSize?.let { v -> runCatching { MessageTextSize.valueOf(v) }.getOrNull()?.let { setMessageTextSize(it) } }
        backup.markReadOnDelete?.let { setMarkReadOnDelete(it) }
        backup.markReadOnArchive?.let { setMarkReadOnArchive(it) }
        backup.markReadOnMove?.let { setMarkReadOnMove(it) }
        backup.unarchiveOnReply?.let { setUnarchiveOnReply(it) }
        backup.signatureOnReplies?.let { setSignatureOnReplies(it) }
        backup.signatureBelowQuote?.let { setSignatureBelowQuote(it) }
        backup.signatureDelimiter?.let { setSignatureDelimiter(it) }
        backup.deliveryMode?.let { v -> runCatching { DeliveryMode.valueOf(v) }.getOrNull()?.let { setDeliveryMode(it) } }
        backup.notificationContent?.let { v -> runCatching { NotificationContent.valueOf(v) }.getOrNull()?.let { setNotificationContent(it) } }
        // Restored wholesale, replacing rather than merging: a backup is a picture of the reader's
        // tags at one moment, and merging would resurrect every tag they had deleted since. An
        // empty list in the backup is a real answer (they deleted them all); absent is not, and
        // leaves whatever this device already has.
        backup.mailTags?.let { setMailTags(it) }
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
        private val KEY_GRIDLINK_PALETTE = stringPreferencesKey("gridlink_palette")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_LIST_DENSITY = stringPreferencesKey("list_density")
        private val KEY_PREVIEW_LINES = stringPreferencesKey("preview_lines")
        private val KEY_SWIPE_RIGHT = stringPreferencesKey("swipe_right")
        private val KEY_SWIPE_LEFT = stringPreferencesKey("swipe_left")
        private val KEY_SWIPE_LEFT_FAR = stringPreferencesKey("swipe_left_far")
        private val KEY_SORT_ORDER = stringPreferencesKey("sort_order")
        private val KEY_CONVERSATION_VIEW = booleanPreferencesKey("conversation_view")
        private val KEY_UNIFIED_INBOX = booleanPreferencesKey("unified_inbox")
        private val KEY_THREAD_TOOLBAR = stringSetPreferencesKey("thread_toolbar_actions")
        private val KEY_BUNDLE_AUTOMATED = booleanPreferencesKey("bundle_automated")
        private val KEY_MARK_READ_ON_DELETE = booleanPreferencesKey("mark_read_on_delete")
        private val KEY_MARK_READ_ON_ARCHIVE = booleanPreferencesKey("mark_read_on_archive")
        private val KEY_MARK_READ_ON_MOVE = booleanPreferencesKey("mark_read_on_move")
        private val KEY_UNARCHIVE_ON_REPLY = booleanPreferencesKey("unarchive_on_reply")
        private val KEY_SIGNATURE_ON_REPLIES = booleanPreferencesKey("signature_on_replies")
        private val KEY_SIGNATURE_BELOW_QUOTE = booleanPreferencesKey("signature_below_quote")
        private val KEY_SIGNATURE_DELIMITER = booleanPreferencesKey("signature_delimiter")
        private val KEY_MESSAGE_TEXT_SIZE = stringPreferencesKey("message_text_size")
        private val KEY_CONTACT_SUGGESTIONS = booleanPreferencesKey("contact_suggestions")
        private val KEY_SYSTEM_ACCOUNT_MIRROR = booleanPreferencesKey("system_account_mirror")
        private val KEY_HAS_SEEN_WELCOME = booleanPreferencesKey("has_seen_welcome")
        private val KEY_HAS_PRIMED_CONTACTS = booleanPreferencesKey("has_primed_contacts")
        private val KEY_INTRO_SEEN_ACCOUNTS = intPreferencesKey("intro_seen_account_count")
        private val KEY_STRIP_TRACKING = booleanPreferencesKey("strip_tracking_params")
        private val KEY_CONFIRM_LINKS = booleanPreferencesKey("confirm_links")
        private val KEY_IMAGE_ALLOWLIST = stringSetPreferencesKey("image_allowlist")
        private val KEY_DELIVERY_MODE = stringPreferencesKey("delivery_mode")
        private val KEY_NOTIFICATION_CONTENT = stringPreferencesKey("notification_content")
        private val KEY_MAIL_TAGS = stringPreferencesKey("mail_tags")
        private val KEY_QUIET_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        private val KEY_QUIET_START = intPreferencesKey("quiet_hours_start")
        private val KEY_QUIET_END = intPreferencesKey("quiet_hours_end")
    }
}
