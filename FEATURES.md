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

## Reading & triage

- ✅ Inbox list and message view (HTML in a WebView, remote content blocked)
- ✅ Offline reading (Room cache)
- ✅ Mark read/unread, flag/star, archive, delete *(M3)*
- ✅ Folder navigation drawer; view any mailbox *(M3)*
- ✅ ⭐ Conversation threading — JMAP native `Thread` objects (collapsed list + thread view)
- ✅ Pull-to-refresh
- ✅ Swipe actions (configurable) with an Undo snackbar for delete/archive
- ✅ Configurable swipe actions (left/right, in Settings → Reading)
- ✅ Sort (newest/oldest, subject, sender, unread-first) + Mark-all-read
- ✅ Multi-select (long-press / select-all) with bulk mark-read, archive, delete
- 💡 Snooze a message until later
- 💡 Paginated "load more"
- ✅ Favourite (star) per row, tappable; favourites pin to the top
- 💡 Report spam / not-spam (move to Junk role)

## Organisation & search

- ✅ Mailbox listing
- ✅ ⭐ Server-side search — inline on the mailbox (search-as-you-type filters the list)
- ✅ Unified inbox across multiple accounts (merged, date-sorted; per-row account)
- 💡 Richer search filters (from, subject, has-attachment, date) + `SearchSnippet` highlights
- 💡 Folder management (create / rename / subscribe), per-folder settings
- ✅ Quick filter: unread-only toggle on the current view
- 💡 Quick filters: starred-only, has-attachment

## Composing & sending

- ✅ Compose and send (`EmailSubmission/set`)
- ✅ Reply / reply-all / forward with quoting (threaded via `inReplyTo`/`references`)
- ✅ Save drafts
- ✅ Attachments: pick & send (blob upload), view/download/open incoming (blob download)
- ✅ Inline images (`cid:`) rendered in the body (downloaded as data URIs)
- 💡 Rich-text editor plus plain-text mode
- 💡 Outbox, and Undo send (hold-back window)
- 💡 ⭐ Schedule send — JMAP `EmailSubmission` supports a future `sendAt`
- 💡 "Forgot attachment?" reminder
- 💡 ⭐ Multiple identities (JMAP `Identity` objects) with per-identity signatures
- 💡 Read-receipt request and response

## Accounts & setup

- ✅ Encrypted account persistence (AndroidKeyStore)
- ✅ Multiple accounts — add / switch / sign out, with migration
- ✅ Account management panel — per-account editable server settings (URL, username, password); JMAP active, IMAP coming
- 💡 Onboarding via `/.well-known/jmap` autodiscovery; OAuth2 / Bearer auth
- 💡 Per-account colour coding
- 💡 Settings export / import

## Sync, push & notifications

- ✅ Incremental sync (`Email/queryChanges` + `Email/changes` + per-type `state`)
- ✅ ⭐ Push via JMAP EventSource (foreground service, no Google/FCM, no IMAP IDLE drain)
- ✅ New-mail notifications (per current account, or all accounts via a setting)
- ✅ Notification quick actions: reply (inline), mark read, delete
- ✅ Push reconnects automatically when the connection drops (catches missed mail)
- 💡 Bundled/grouped notifications per account
- 💡 Quiet hours / Do-Not-Disturb windows

## Privacy & security

- ✅ Remote image / tracking-pixel blocking by default
- ✅ App lock — biometric / face, with screen PIN/pattern/password fallback
- 💡 Per-sender "always load images" allowlist
- 💡 Visible no-telemetry stance
- 💡 Strip tracking parameters / confirm before opening external links

## Encryption

- 💡 OpenPGP via OpenKeychain
- 💡 S/MIME (longer-term)

## UX & accessibility

- ✅ Material 3 / Material You dynamic colour, follows system theme
- ✅ Contact avatars / sender initials (monograms)
- ✅ Settings hub (Appearance / Notifications / Privacy & Security), DataStore-backed
- ✅ Theme toggle (auto / light / dark)
- ✅ Message-list density (compact / normal / spaced)
- ✅ Row preview length (subject only / 1 / 3 / 5 lines)
- ✅ Compact inbox top bar showing folder + account
- 💡 Home-screen widget(s) (unread count / inbox)
- 💡 Accessibility pass (TalkBack, font scaling)

## "Complete app" extras

- 💡 ⭐ Vacation responder / auto-reply — JMAP `VacationResponse` (RFC 8621)
- 💡 ⭐ Quota / storage usage display (JMAP Quota extension)
- 💡 Server-side filters/rules where the server supports `SieveScript`
- 💡 Calendar / `.ics` invite preview (later)
