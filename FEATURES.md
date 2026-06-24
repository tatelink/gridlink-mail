# Features

This document tracks Jmail's feature set: what's built, what's planned on the
roadmap, and proposed additions drawn from K-9 Mail / Thunderbird for Android
and from what users expect of a modern, complete email client.

It complements [ARCHITECTURE.md](ARCHITECTURE.md) (which holds the technical
roadmap, M0–M5). For each proposed feature we note when JMAP makes it cheap to
build — several things that are hard over IMAP are nearly free in JMAP.

**Status key**

- ✅ **Done** — shipped.
- 🔜 **Planned** — on the ARCHITECTURE.md roadmap (milestone noted).
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
- ✅ Recipient autocomplete: recent/cached contacts + opt-in device contacts
- ✅ Undo send (hold-back window); 💡 full Outbox + retry
- ✅ Schedule send (quick presets; persisted + fired by WorkManager, survives app close)
- ✅ Snooze a message until later

**Tier 3 — privacy & JMAP-native power**
- 💡 OpenPGP (via OpenKeychain)
- ✅ ⭐ Vacation responder (JMAP `VacationResponse`)
- ✅ Tracking-param stripping (utm_*, fbclid, gclid… removed from tapped links); ✅ per-sender image allowlist; ✅ link confirmation
- ✅ Server-side Sieve filters/rules (form-based rule builder); ✅ server `Quota` display

**Tier 4 — polish**
- ✅ Richer search filters (from/subject/has-attachment/date, AND-combined); 💡 `SearchSnippet` highlights
- ✅ Per-account colour (avatar + unified-inbox chip); 💡 home-screen widget(s); accessibility pass (TalkBack, font scaling)
- ✅ Bundled/grouped notifications (per-account summary) + quiet hours (silent nightly window)
- 💡 `/.well-known/jmap` autodiscovery + OAuth2; settings export/import

---

## Protocols

- ✅ **JMAP** (RFC 8620/8621) — the primary, modern backend.
- ✅ **IMAP + SMTP** — a hand-rolled client (no JavaMail), at parity with JMAP:
  folder list, paged read with server-side load-more, MIME body + attachments,
  flag / archive / delete with undo, SMTP send (incl. multipart attachments) with
  APPEND-to-Sent, and server-side search. Add via Add account → "IMAP / SMTP"
  (host/port/security for both). The data layer routes per-account by protocol,
  so the cache, paging, and entire UI are protocol-agnostic.
  - ✅ One pooled connection per account, reused across calls (no reconnect per page).
  - ✅ IDLE push (new-mail notifications) via a dedicated IDLE connection.
  - 💡 IMAP gaps: inline `cid:` images, CONDSTORE incremental sync.

## Reading & triage

- ✅ Inbox list and message view (HTML in a WebView, remote content blocked;
  dark mode: theme colours for plain text, CSS invert for rich HTML)
- ✅ Offline reading (Room cache)
- ✅ Mark read/unread, flag/star, archive, delete *(M3; JMAP + IMAP)*
- ✅ Unread shown by bold text (not a status dot)
- ✅ Folder navigation drawer; view any mailbox *(M3)*
- ✅ ⭐ Conversation threading — JMAP native `Thread` objects (collapsed list + thread view)
- ✅ Pull-to-refresh
- ✅ Swipe actions (configurable) with an Undo snackbar for delete/archive
- ✅ Configurable swipe actions (left/right, in Settings → Reading)
- ✅ Sort (newest/oldest, subject, sender, unread-first) + Mark-all-read
- ✅ Multi-select (long-press / select-all): bulk read/unread toggle (keeps the
  selection), archive (Unarchive → Inbox from the Archive folder), move-to-folder, delete
- ✅ Opening a folder starts at the top of its list
- ✅ Snooze a message until later
- ✅ Paged list (Jetpack Paging 3 + Room) — large folders load in pages while scrolling, constant memory; scroll-position indicator on the right
- ✅ Scroll to load more — a Paging `RemoteMediator` fetches older mail from the server when you scroll past the cached window (JMAP anchor-based / IMAP UID paging), with a loading/retry footer
- ✅ Favourite (star) per row, tappable; favourites pin to the top
- ✅ Report spam / not-spam — message overflow, context-aware (Report spam ↔ Not spam)

## Organisation & search

- ✅ Mailbox listing
- ✅ Server-side search — inline on the mailbox (search-as-you-type; JMAP query / IMAP SEARCH, with instant local-cache results)
- ✅ Unified inbox across multiple accounts (merged, date-sorted; per-row account; JMAP + IMAP)
- ✅ Richer search filters (from, subject, has-attachment, date) — advanced panel in Search, JMAP Email/query AND filter; 💡 `SearchSnippet` highlights
- ✅ Auto-create an Archive folder on first archive (when the account has none)
- ✅ Folder management — create / rename / delete custom folders from the drawer; 💡 subscribe + per-folder settings
- ✅ Quick filter: unread-only toggle on the current view
- 💡 Quick filters: starred-only, has-attachment

## Composing & sending

- ✅ Compose and send (JMAP `EmailSubmission/set`, or SMTP submit + APPEND-to-Sent for IMAP)
- ✅ Reply / reply-all / forward with quoting (threaded via `inReplyTo`/`references`)
- ✅ Save drafts (JMAP, or IMAP APPEND to Drafts)
- ✅ Attachments: pick & send, view/download/open incoming (JMAP blobs / IMAP multipart MIME + BODY-section fetch)
- ✅ Inline images (`cid:`) rendered in the body (downloaded as data URIs)
- 💡 Rich-text editor plus plain-text mode
- ✅ Undo send (hold-back window) — held in an app-scoped outbox; 💡 full Outbox/retry
- ✅ Schedule send — quick presets; persisted in Room, fired by WorkManager (survives app close); v1 carries no attachments
- 💡 "Forgot attachment?" reminder
- ✅ Multiple sending identities per account (name + address), **each with its own
  signature** (plain text or HTML, with HTML-file import); a "From" picker in compose
  chooses which to send as (matched to a server `Identity` for JMAP submission)
- 💡 Read-receipt request and response

## Accounts & setup

- ✅ Encrypted account persistence (AndroidKeyStore)
- ✅ Multiple accounts — add / switch / sign out, with migration
- ✅ JMAP **and** IMAP/SMTP account setup (protocol picker; host/port/security)
- ✅ Account management panel — per-account editable server settings (protocol-aware: JMAP URL, or IMAP/SMTP host/port/security; username, password)
- ✅ Optional account display name (falls back to the address when unset)
- 💡 Onboarding via `/.well-known/jmap` autodiscovery; OAuth2 / Bearer auth
- ✅ Per-account colour coding (picker in account settings; tints the account avatar + the unified-inbox account chip)
- 💡 Settings export / import

## Sync, push & notifications

- ✅ ⭐ Incremental sync (`Email/queryChanges` + `Email/changes` + per-type `state`) — JMAP; IMAP does a bounded full re-query
- ✅ ⭐ Push (foreground service, no Google/FCM): JMAP EventSource, or IMAP IDLE (a dedicated connection per account, refreshed within the ~29-min limit)
- ✅ New-mail notifications (per current account, or all accounts via a setting)
- ✅ Notification quick actions: reply (inline), mark read, delete
- ✅ Push reconnects automatically when the connection drops (catches missed mail)
- ✅ Bundled/grouped notifications per account — individual new-mail notifications collapse under a per-account summary
- ✅ Quiet hours — a nightly window (Settings → Notifications) during which new mail still arrives but silently (no sound/vibration/heads-up)

## Privacy & security

- ✅ Remote image / tracking-pixel blocking by default
- ✅ App lock — biometric / face, with screen PIN/pattern/password fallback
- ✅ Per-sender "always load images" allowlist (message ⋮ menu; clear in Settings → Privacy)
- 💡 Visible no-telemetry stance
- ✅ Strip tracking parameters from tapped links (Settings → Privacy, on by default); ✅ confirm before opening external links (Settings → Privacy → Links, opt-in; dialog shows the destination)

## Encryption

- 💡 OpenPGP via OpenKeychain
- 💡 S/MIME (longer-term)

## UX & accessibility

- ✅ Material 3 / Material You dynamic colour, follows system theme
- ✅ Contact avatars / sender initials (monograms)
- ✅ Settings hub (Appearance / Notifications / Privacy & Security / Storage), DataStore-backed
- ✅ Storage screen — on-device cache usage (DB + attachments, per-account breakdown) + Clear cache
- ✅ Attachment cache cap (LRU by size/age); sign-out purges that account's cached mail + attachments
- ✅ Per-account sync window — messages to sync by age (30/90 days, 1 year) or count (50/200/500/all), default 90 days
- ✅ Per-account "Clear this account's cache" + cached-message count (Settings → Accounts → detail)
- ✅ Theme toggle (auto / light / dark)
- ✅ Message-list density (compact / normal / spaced)
- ✅ Row preview length (subject only / 1 / 3 / 5 lines)
- ✅ Compact inbox top bar showing folder + account
- 💡 Home-screen widget(s) (unread count / inbox)
- 💡 Accessibility pass (TalkBack, font scaling)

## "Complete app" extras

- ✅ ⭐ Vacation responder / auto-reply — JMAP `VacationResponse` (RFC 8621); per-account, server-side, Settings → Vacation responder (enable + subject + message + optional date range), IMAP/no-capability gated
- ✅ On-device storage usage + Clear cache (Settings → Storage); ✅ server mailbox quota via JMAP `Quota` (RFC 9425) shown in Settings → Storage when supported
- ✅ Server-side filters/rules where the server supports `SieveScript` (RFC 9661) — form-based rule builder (condition → move/mark-read/flag), compiled to Sieve and round-tripped via a JSON metadata comment
- 💡 Calendar / `.ics` invite preview (later)
