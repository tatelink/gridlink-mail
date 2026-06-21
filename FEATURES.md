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
- 🔜 Mark read/unread, flag/star, archive, move, delete *(M3)*
- 💡 ⭐ Conversation threading — JMAP has native `Thread` objects
- 💡 Configurable swipe actions (e.g. swipe to archive / delete)
- 💡 Multi-select and bulk actions
- 💡 Snooze a message until later
- 💡 Pull-to-refresh and paginated "load more"
- 💡 Pin/star to top; report spam / not-spam (move to Junk role)

## Organisation & search

- ✅ Mailbox listing
- 💡 Unified inbox across multiple accounts
- 💡 ⭐ Server-side search — `Email/query` filters (from, subject, body,
  has-attachment, date) plus `SearchSnippet` for highlighted results
- 💡 Folder management (create / rename / subscribe), per-folder settings
- 💡 Quick filters: unread-only, starred-only, has-attachment

## Composing & sending *(M4)*

- 🔜 Compose and send (`EmailSubmission/set`)
- 💡 Rich-text editor plus plain-text mode
- 💡 Drafts, Outbox, and Undo send (hold-back window)
- 💡 ⭐ Schedule send — JMAP `EmailSubmission` supports a future `sendAt`
- 💡 Reply / reply-all / forward with quoting
- 💡 Attachments: pick, inline images, save/share incoming, "forgot
  attachment?" reminder
- 💡 ⭐ Multiple identities (JMAP `Identity` objects) with per-identity signatures
- 💡 Read-receipt request and response

## Accounts & setup

- ✅ Encrypted account persistence (single account)
- 🔜 Multiple accounts *(M5)*
- 💡 Onboarding via `/.well-known/jmap` autodiscovery; OAuth2 / Bearer auth
- 💡 Per-account colour coding
- 💡 Settings export / import

## Sync, push & notifications *(M5)*

- 🔜 Incremental sync (`/changes` + per-type `state`), WorkManager
- 🔜 ⭐ Push via JMAP EventSource / WebSocket (no IMAP IDLE battery drain)
- 💡 Rich notifications: per-account, bundled, with quick actions
  (reply / archive / delete / mark-read)
- 💡 Quiet hours / Do-Not-Disturb windows

## Privacy & security

- ✅ Remote image / tracking-pixel blocking by default
- 💡 Per-sender "always load images" allowlist
- 💡 App lock (biometric / PIN)
- 💡 Visible no-telemetry stance
- 💡 Strip tracking parameters / confirm before opening external links

## Encryption

- 💡 OpenPGP via OpenKeychain
- 💡 S/MIME (longer-term)

## UX & accessibility

- ✅ Material 3 / Material You dynamic colour, follows system theme
- 💡 Theme toggle (auto / light / dark); message-list density options
- 💡 Contact avatars / sender initials
- 💡 Home-screen widget(s) (unread count / inbox)
- 💡 Accessibility pass (TalkBack, font scaling)

## "Complete app" extras

- 💡 ⭐ Vacation responder / auto-reply — JMAP `VacationResponse` (RFC 8621)
- 💡 ⭐ Quota / storage usage display (JMAP Quota extension)
- 💡 Server-side filters/rules where the server supports `SieveScript`
- 💡 Calendar / `.ics` invite preview (later)
