# Sterna Mail

[![CI](https://codeberg.org/emon/jmail/actions/workflows/ci.yml/badge.svg?branch=main)](https://codeberg.org/emon/jmail/actions)

**A private, modern email app for Android.**

Sterna Mail is an open-source email client built for **JMAP** — a new, faster
internet standard for email — with full support for classic **IMAP/SMTP** too.
It is private by default, pleasant to use, and entirely free software.

> _Named after **Sterna**, the genus of terns: the Arctic tern flies the longest
> migration of any animal, delivering itself across the world every year._

> **Status: pre-release.** Sterna Mail is a complete, working email client —
> read, organise, search, write, schedule, and send mail (with attachments)
> over JMAP or IMAP/SMTP, across multiple accounts, fully offline-capable, with
> push notifications and remote content blocked by default. There is no public
> build yet — see [Installing](#installing).

## About JMAP

Most email apps talk to your server using an old protocol called IMAP. JMAP is
its modern successor: it syncs faster, uses less battery and data, and was
designed for today's phones. Servers like [Stalwart](https://stalw.art/) speak
JMAP, and Sterna Mail is built to make the most of it.

Sterna Mail also speaks classic **IMAP and SMTP**, so it works with any standard
mail provider — you pick the protocol when you add an account, or just enter
your email and let autodiscovery find the server.

## What Sterna Mail stands for

- **Privacy first.** No tracking, no ads, no analytics, no Google services.
  Remote images and tracking pixels are blocked by default; tapped links can be
  stripped of tracking parameters and confirmed before opening.
- **Free and open.** Licensed under the GPLv3 — anyone can read, audit, and
  improve the code.
- **Modern and simple.** A clean, fast Material 3 interface that follows your
  phone's theme and language.

## Features

**Reading & organising**
- Unified inbox across multiple accounts (JMAP and/or IMAP/SMTP), fully offline
  (local cache); large folders page in as you scroll, fetching older mail from
  the server
- Conversation view — threads collapse into one row with a message count
- Nested folders shown as a collapsible tree; create/rename/delete, plus
  subfolders
- Configurable swipe actions, flag/star, archive & delete with undo, multi-select
  bulk actions, snooze, report spam, sort and unread-only filter
- Search-as-you-type (server-side + instant local results) with advanced filters
  (from, subject, has-attachment, date)

**Writing & sending**
- Compose with recipient chips + autocomplete and email validation, multiple
  identities each with its own signature, attachments, and drafts
- Undo send, schedule send (with a screen to view/cancel pending sends), and
  "forgot attachment?" / large-recipient-list reminders

**Accounts & setup**
- Add an account with just email + password (`/.well-known/jmap` autodiscovery),
  password-free **OAuth 2.0** sign-in (device flow), or manual IMAP/SMTP with
  provider presets; a "test connection" button before saving
- Per-account colour, sync window, and notification toggle; encrypted credential
  storage (AndroidKeyStore); app lock (biometric/PIN)

**Notifications & server power**
- Push for new mail with no Google/FCM (JMAP EventSource or IMAP IDLE), grouped
  per account, with quiet hours
- Server-side **vacation responder** and **Sieve filter rules**, mailbox quota
  display

**Polish**
- Material You theming, light/dark, 9 UI languages, adjustable message text size,
  settings export/import, and a screen-reader-friendly UI

The full, tracked feature set lives in [FEATURES.md](FEATURES.md).

**Planned:** encrypted email (OpenPGP), home-screen widgets, and more.

## Installing

There is no public release yet. When one is ready it will be listed here and
(later) on [F-Droid](https://f-droid.org/). Until then, build it from source —
see [CONTRIBUTING.md](CONTRIBUTING.md).

## For developers

Architecture and build instructions live in [ARCHITECTURE.md](ARCHITECTURE.md)
and [CONTRIBUTING.md](CONTRIBUTING.md). The planned and shipped feature set is
tracked in [FEATURES.md](FEATURES.md).

## License

[GNU General Public License v3.0](LICENSE).
