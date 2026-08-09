# Features

This document tracks Gridlink Mail's feature set: what's built, what's planned on the
roadmap, and proposed additions drawn from K-9 Mail / Thunderbird for Android
and from what users expect of a modern, complete email client.

It complements [ARCHITECTURE.md](ARCHITECTURE.md) (which holds the technical
roadmap, M0–M5). For each proposed feature I note when JMAP makes it cheap to
build — several things that are hard over IMAP are nearly free in JMAP.

**Status key**

- ✅ **Done** — shipped.
- 🔜 **Planned** — next up, not yet built.
- 💡 **Proposed** — not yet scheduled.
- ⭐ **JMAP-native** — RFC 8620/8621 gives this almost for free.

---

## Roadmap — what's next (prioritized)

The categories further down list the full feature set; this is the order of work.

**Tier 1 — close functional holes** *(done)*
- ✅ IMAP push (IDLE) — IMAP accounts now get new-mail notifications like JMAP
- ✅ Folder management (create / rename / delete)
- ✅ Report spam / not-spam (move to/from Junk)

**Tier 2 — modern compose & send** *(done)*
- ✅ Compose overhaul: cross-account From picker, frameless full-width line fields,
  icon actions, auto-focused To, expandable Cc/Bcc (rich-text editor dropped —
  low value on mobile)
- ✅ Recipient autocomplete: recent/cached contacts + opt-in device contacts; recipients show as chips (removable), with email-format validation — invalid addresses are flagged and block sending
- ✅ Undo send (hold-back window); ✅ full Outbox + retry (persistent queue, survives app death, auto-retries on network return)
- ✅ Schedule send (quick presets; persisted + fired by WorkManager, survives app close)
- ✅ Snooze a message until later

**Tier 3 — privacy & JMAP-native power** *(done)*
- ✅ OpenPGP (via OpenKeychain) — read + send, both protocols
- ✅ ⭐ Vacation responder (JMAP `VacationResponse`)
- ✅ Tracking-param stripping (utm_*, fbclid, gclid… removed from tapped links); ✅ per-sender image allowlist; ✅ link confirmation
- ✅ Server-side Sieve filters/rules (form-based rule builder); ✅ server `Quota` display

**Tier 4 — polish**
- ✅ Richer search filters (from/subject/has-attachment/date, AND-combined); 💡 `SearchSnippet` highlights
- ✅ Gridlink brand identity — Arctic (light) / Pelagic (dark) palette, a calm sea-teal action colour with a coral accent (the tern's beak); Material You is an opt-in toggle; coastal line-art empty states; calmer microcopy
- ✅ Per-account colour (avatar + unified-inbox chip); ✅ accessibility pass v1 (screen-reader labels, font scaling, system-bar contrast); ✅ home-screen widgets (a resizable recent-inbox list and a 2x1 unread count, both cache-only — they never sync); 💡 fuller TalkBack audit
- ✅ Bundled/grouped notifications (per-account summary) + quiet hours (silent nightly window)
- ✅ Settings export/import (app preferences → JSON file via SAF; excludes accounts/credentials); ✅ `/.well-known/jmap` autodiscovery (email → server); ✅ OAuth2 device-flow sign-in (RFC 8628)

---

## Protocols

- ✅ **JMAP** (RFC 8620/8621) — the primary, modern backend.
- ✅ **IMAP + SMTP** — a hand-rolled client (no JavaMail), at parity with JMAP:
  folder list, paged read with server-side load-more, MIME body + attachments,
  star / archive / delete with undo, SMTP send (incl. multipart attachments) with
  APPEND-to-Sent, and server-side search. Add via Add account → "IMAP / SMTP"
  (host/port/security for both). The data layer routes per-account by protocol,
  so the cache, paging, and entire UI are protocol-agnostic.
  - ✅ One pooled connection per account, reused across calls (no reconnect per page).
  - ✅ IDLE push (new-mail notifications) via a dedicated IDLE connection.
  - 💡 IMAP gap: CONDSTORE incremental sync.

## Reading & triage

- ✅ Friendly empty states — coastal line-art illustrations (a tern over the horizon for an empty inbox, a magnifier for search, a folder, a swept bin for the trash, a drifting cloud for offline), theme-aware and drawn from the Material colour scheme; a calm welcome on first-run setup ("Your email, finally yours.")
- ✅ Reply-all / mass-send guard — sending to 5+ recipients (To+Cc+Bcc) asks for confirmation, with the count
- ✅ Sign-out asks for confirmation before removing an account and clearing its cache
- ✅ Inbox list and message view (HTML in a WebView, remote content blocked;
  dark mode: theme colours for plain text, CSS invert for rich HTML); plain-text
  bodies preserve paragraphs and unwrap `format=flowed` soft line breaks (RFC 3676)
  so they read as written instead of one run-on block or mid-sentence wraps
- ✅ Offline reading (Room cache)
- ✅ Mark read/unread, star, archive, delete *(M3; JMAP + IMAP)*
- ✅ Optional "mark as read when deleting" (Settings → Reading), so deleted mail doesn't sit unread in Trash
- ✅ Unread shown by bold text (not a status dot)
- ✅ Folder navigation drawer; view any mailbox *(M3)*
- ✅ ⭐ Conversation threading — JMAP native `Thread` objects. The list collapses a thread into one row with a message-count badge (Settings → Reading → Conversation view, on by default; toggle off for a flat list); opening a row shows the thread view. Grouping is done in SQL (representative = latest message, unread if any in the thread)
- ✅ Pull-to-refresh
- ✅ Swipe actions (configurable) with an Undo snackbar for delete/archive; "Empty trash" (Trash overflow menu) destroys, behind the same held-back Undo, exactly the messages the folder held when you confirmed — mail filed there afterwards is not touched
- ✅ Configurable swipe actions (left/right, in Settings → Reading)
- ✅ Sort (newest/oldest, subject, sender, unread-first, starred-first) + Mark-all-read
- ✅ Multi-select (long-press / select-all): bulk read/unread toggle (keeps the
  selection), archive (Unarchive → Inbox from the Archive folder), move-to-folder, delete
- ✅ Opening a folder starts at the top of its list
- ✅ Snooze a message until later
- ✅ Paged list (Jetpack Paging 3 + Room) — large folders load in pages while scrolling, constant memory; scroll-position indicator on the right
- ✅ Scroll to load more — a Paging `RemoteMediator` fetches older mail from the server when you scroll past the cached window (JMAP anchor-based / IMAP UID paging), with a loading/retry footer
- ✅ Star per row, tappable; "Starred first" is one of the sort orders, so starred mail pins
  to the top when you ask for it and sorts normally the rest of the time; "Starred only" is a
  criterion of the advanced search, which gathers them across the whole account
- ✅ Report spam / not-spam — message overflow, context-aware (Report spam ↔ Not spam)

## Organisation & search

- ✅ Mailbox listing
- ✅ Server-side search — inline on the mailbox (search-as-you-type; JMAP query / IMAP SEARCH, with instant local-cache results); the search field names its scope (current folder, or "All inboxes"). In the unified inbox the search fans out to **every** account in parallel (not just the active one), merges + de-duplicates the results, and each result row carries its account name/address chip like the unified list
- ✅ Unified inbox across multiple accounts (merged, date-sorted; per-row account; JMAP + IMAP) — switching the active account refreshes the list; archive/delete from the unified inbox resolve the target folder on each message's own account
- ✅ Richer search filters (from, subject, has-attachment, date) — advanced panel in Search, JMAP Email/query AND filter; 💡 `SearchSnippet` highlights
- ✅ Auto-create an Archive folder on first archive (when the account has none)
- ✅ Folder management — create / rename / delete custom folders from the drawer, including nested subfolders (JMAP parentId / IMAP path), shown as a collapsible tree; 💡 subscribe + per-folder settings, drag-to-reorder
- ✅ Quick filter: unread-only toggle on the current view
- 💡 Quick filters: starred-only, has-attachment

## Composing & sending

- ✅ Compose and send (JMAP `EmailSubmission/set`, or SMTP submit + APPEND-to-Sent for IMAP)
- ✅ Opens `mailto:` links (registered as an email app, from browsers and other apps); the link's addresses, subject, body, cc and bcc prefill compose
- ✅ Reply / reply-all / forward with quoting (threaded via `inReplyTo`/`references`)
- ✅ Save drafts (JMAP, or IMAP APPEND to Drafts); closing compose with unsaved edits prompts to save the draft, discard, or keep editing (intercepts the Close button and system back)
- ✅ Attachments: pick & send, view/download/open incoming (JMAP blobs / IMAP multipart MIME + BODY-section fetch)
- ✅ Inline images (`cid:`) rendered in the body (downloaded as data URIs)
- ✅ Formatting in compose — bold, italic, bulleted and numbered lists, and links, from a small
  toolbar that appears above the keyboard while the body has focus. Lists are real `• ` / `1. `
  prefixes so a plain-text reader sees them, and the return key continues (or ends) a list.
  Marks serialise to the HTML alternative that already ships beside every message, so nothing
  new goes on the wire. **Plain text stays the default**: an untouched body renders byte-identical
  to what the plain escaper always produced (pinned by test), and a "plain text" button strips
  every mark and marker back out. Links accept what people type (`e.com`, `jeff@e.com`) and refuse
  any scheme other than `http(s):`/`mailto:` rather than prefixing it. Formatting survives a saved
  draft (read back out of the draft's own HTML part). Not included: headings, colours, alignment,
  inline images
- ✅ Undo send (hold-back window) — held in an app-scoped outbox; ✅ full Outbox — a
  Room-backed send queue that survives app death and auto-retries with exponential
  backoff when the network returns; every send path (compose, reply/forward, RSVP,
  scheduled, notification quick-reply) routes through it. A dedicated Outbox screen
  (inbox overflow) lists waiting/sending/failed items with retry / edit / delete, with a
  discreet inbox badge. IMAP attachments are persisted so a deferred retry keeps them
- ✅ Schedule send — quick presets; persisted in Room, fired by WorkManager (survives app close); v1 carries no attachments. A "Scheduled" screen (inbox ⋮ → Scheduled messages) lists pending sends and cancels them
- ✅ "Forgot attachment?" reminder — sending a message that mentions an attachment (in any of the 9 UI languages) with none added prompts to confirm
- ✅ Multiple sending identities per account (name + address), **each with its own
  signature** (plain text or HTML, with HTML-file import); a "From" picker in compose
  chooses which to send as (matched to a server `Identity` for JMAP submission)
- 💡 Read-receipt request and response

## Accounts & setup

- ✅ Encrypted account persistence (AndroidKeyStore)
- ✅ Multiple accounts — add / switch / sign out, with migration
- ✅ Multiple JMAP accounts under one login (RFC 8620 §1.6.2) — when a single sign-in exposes several mail accounts (delegated / shared / team mailboxes), each is surfaced as its own account in the drawer switcher, with its own inbox, folders, unread count, mail cache and new-mail notifications, all sharing the one stored credential. Sending from a sub-account uses that account's own server identities. Discovered automatically on connect and pruned when access is revoked; one push subscription per login carries changes for every account it reaches. A login that exposes a single mail account behaves exactly as before
- ✅ JMAP **and** IMAP/SMTP account setup (protocol picker; host/port/security) — IMAP setup has quick-setup presets (Gmail, Yahoo, iCloud, Fastmail, Proton Bridge) that prefill host/port/security, with a reminder that most providers need an app-specific password (not the normal one); a rejected IMAP login repeats that hint. Password fields have a show/hide toggle. (Outlook/Microsoft uses OAuth instead of a password — see the XOAUTH2 item below.)
- ✅ Account management panel — per-account editable server settings (protocol-aware: JMAP URL, or IMAP/SMTP host/port/security; username, password), with a "Test connection" button that validates the (edited) settings before saving
- ✅ Optional account display name (falls back to the address when unset)
- ✅ Onboarding via `/.well-known/jmap` autodiscovery — enter just email + password; Gridlink probes the email domain's well-known endpoint (and mail./jmap. subdomains, following redirects) to find the JMAP server, with a manual-server fallback; 💡 DNS SRV (`_jmap._tcp`)
- ✅ OAuth2 / Bearer auth — "Sign in with OAuth" uses the OAuth 2.0 Device Authorization Grant (RFC 8628): discovers the server's `/.well-known/oauth-authorization-server`, shows a user code to enter in the browser, polls for tokens, and stores an encrypted refresh token (auto-refreshed). No password handled by the app. Verified against Stalwart.
- ✅ **Outlook / Microsoft OAuth2 + XOAUTH2** — "Sign in with Microsoft" via the OAuth 2.0 Device Authorization Grant (a code typed into the browser), against a registered public Azure client; the access token is presented to the IMAP **and** SMTP servers with the **XOAUTH2** SASL mechanism (no password handled or stored; refresh token encrypted and auto-refreshed). Outlook is a provider chip in Add account, with server fields hidden. **Personal Outlook/Hotmail works.** Two limits, both gatekeeping rather than code: **work/school (org) accounts** need the organisation's admin to consent, or a Microsoft "verified publisher" badge I cannot obtain from a personal Microsoft account (investigated, paused); and a brand-new, not-yet-provisioned Outlook mailbox can fail (K-9 fails on it too — it's the account, not the client).
- 🔜 **Gmail / Google OAuth2 + XOAUTH2** *(planned)* — the XOAUTH2 plumbing above (IMAP + SMTP) is provider-agnostic and reusable; what is missing is a Google OAuth provider (Google client id + endpoints + the `https://mail.google.com/` scope). The real blocker is **Google's verification for restricted Gmail scopes** (a recurring third-party security assessment), not the code. Until then Gmail works with an **app-specific password**, like Yahoo/iCloud/Fastmail.
- ✅ Per-account colour coding (picker in account settings; tints the account avatar + the unified-inbox account chip)
- ✅ Settings export / import — app preferences (appearance, reading, notifications, privacy, language) to/from a JSON file via the Storage Access Framework (Settings → Backup); accounts and passwords are excluded (device-bound encryption)

## Sync, push & notifications

- ✅ ⭐ Incremental sync (`Email/queryChanges` + `Email/changes` + per-type `state`) — JMAP; IMAP does a bounded full re-query
- ✅ ⭐ Push (foreground service, no Google/FCM): JMAP EventSource, or IMAP IDLE (a dedicated connection per account, refreshed within the ~29-min limit)
- ✅ New-mail notifications (per current account, or all accounts via a setting); per-account opt-out toggle in Settings → Accounts → [account] → Notifications
- ✅ Notification quick actions: reply (inline), mark read, delete
- ✅ Push reconnects automatically when the connection drops (catches missed mail)
- ✅ Bundled/grouped notifications per account — individual new-mail notifications collapse under a per-account summary
- ✅ Push/notifications beyond the Inbox (issue #16) — per-folder watch switch in the drawer (Sieve-filtered folders included): JMAP account-wide changes filtered on the watched set; IMAP non-Inbox folders via the periodic poll (IDLE is single-folder)
- ✅ ⭐ UnifiedPush transport (issue #17) — JMAP `PushSubscription` to a UnifiedPush endpoint (ntfy, NextPush…) removes the persistent connection and its permanent notification for JMAP accounts; IMAP keeps direct IDLE or the periodic poll. The transport is picked automatically per account; the only user-facing setting is outcome-framed ("New mail delivery: Instant / Battery saver"); a read-only per-account status line shows what's in use; a distributor picker appears only when several are installed
- ✅ Quiet hours — a nightly window (Settings → Notifications) during which new mail still arrives but silently (no sound/vibration/heads-up)

## Privacy & security

- ✅ Remote image / tracking-pixel blocking by default
- ✅ App lock — biometric / face, with screen PIN/pattern/password fallback
- ✅ Per-sender "always load images" allowlist (add from a message's ⋮ menu, or add/remove individual senders + clear all in Settings → Privacy)
- ✅ Visible no-telemetry stance — [PRIVACY.md](PRIVACY.md) + the README privacy section
- ✅ Strip tracking parameters from tapped links (Settings → Privacy, on by default); ✅ confirm before opening external links (Settings → Privacy → Links, opt-in; dialog shows the destination)

## Encryption

- ✅ OpenPGP via OpenKeychain: read (decrypt + verify signatures) and send
  (sign and/or encrypt, PGP/MIME) on both JMAP and IMAP/SMTP. Per-account setup
  in Settings; a lock toggle in the composer. Decrypted content is never written
  to disk (not cached, not search-indexed); the message subject is not encrypted.
  User guide: [ENCRYPTION.md](ENCRYPTION.md).
- 💡 S/MIME (longer-term)

## UX & accessibility

- ✅ Material 3 with Gridlink's own brand palette (Arctic light / Pelagic deep-teal dark) by default; Material You wallpaper colour is an opt-in toggle (Settings → Appearance); the system status/navigation-bar icons follow the in-app theme so they stay legible
- ✅ Contact avatars / sender initials (monograms)
- ✅ Settings hub (Appearance / Notifications / Privacy & Security / Storage), DataStore-backed; grouped into Accounts · App · "This account · <name>" so app-wide vs per-account (server-side) settings are clear at a glance
- ✅ Storage screen — on-device cache usage (DB + attachments, per-account breakdown) + Clear cache
- ✅ Attachment cache cap (LRU by size/age); sign-out purges that account's cached mail + attachments
- ✅ Per-account sync window — messages to sync by age (30/90 days, 1 year) or count (50/200/500/all), default 90 days
- ✅ Per-account "Clear this account's cache" + cached-message count (Settings → Accounts → detail)
- ✅ Theme toggle (auto / light / dark)
- ✅ Message-list density (compact / normal / spaced)
- ✅ Row preview length (subject only / 1 / 3 / 5 lines)
- ✅ Message text size (small / normal / large / huge) — scales the message-body WebView (Settings → Reading → Message)
- ✅ Compact inbox top bar showing folder + account
- ✅ About section in Settings — version (with release date), source code, license and author links
- ✅ Home-screen widgets — a resizable recent-inbox list (sender, subject, preview, relative time, unread dot, attachment mark; header carries the account, an unread pill, refresh and compose) and a 2x1 unread count. Both read the local cache only: they never sync, so they cost no network and cannot stall the launcher. Refresh enqueues the ordinary fetch worker and both redraw when it lands. "Never synced" prints an em dash rather than a zero, which would be a claim the widget has not earned
- ✅ Accessibility pass (v1): screen-reader labels for icon-only controls (e.g. the star reads "Add star"/"Remove star" instead of the ★ glyph), decorative icons left unlabelled to avoid double-announcement, and text scales with the system font size (Compose sp); 💡 fuller TalkBack audit, large-touch-target review

## "Complete app" extras

- ✅ ⭐ Vacation responder / auto-reply — JMAP `VacationResponse` (RFC 8621); per-account, server-side, Settings → Vacation responder (enable + subject + message + optional date range), IMAP/no-capability gated
- ✅ On-device storage usage + Clear cache (Settings → Storage); ✅ server mailbox quota via JMAP `Quota` (RFC 9425) shown in Settings → Storage when supported
- ✅ Server-side filters/rules where the server supports `SieveScript` (RFC 9661) — form-based rule builder (condition → move/mark-read/star), compiled to Sieve and round-tripped via a JSON metadata comment
- ✅ Calendar invite (`.ics`) preview — a `text/calendar` part is detected (captured on
  IMAP even without a filename; JMAP lists it already), the first `VEVENT` is parsed by a
  small dependency-free iCalendar reader (timezones, all-day, recurrence, `DURATION`), and
  an event card above the body shows title / when / where / organiser / guests. "Add to
  calendar" opens the user's calendar app prefilled via an Intent, so **no calendar
  permission** is taken; a parse failure falls back to opening the raw `.ics`.
- ✅ RSVP to an invite — Accept / Decline / Tentative on a `REQUEST` invite sends an iTIP
  `REPLY` email (`text/calendar; METHOD:REPLY`) to the organiser, built without any
  dependency and sent over the existing JMAP/IMAP path (so still **no calendar
  permission**). 💡 Conflict detection later (deferred: it would need calendar read access)
