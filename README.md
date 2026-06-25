<p align="center">
  <img src="docs/logo.png" width="104" alt="Sterna Mail logo">
</p>

# Sterna Mail

[![CI](https://codeberg.org/emon/sterna-mail/actions/workflows/ci.yml/badge.svg?branch=main)](https://codeberg.org/emon/sterna-mail/actions)
[![Latest release](https://img.shields.io/badge/download-preview%20APK-2F5E59)](https://codeberg.org/emon/sterna-mail/releases/latest)
[![License: GPLv3](https://img.shields.io/badge/license-GPLv3-blue)](LICENSE)
[![Support on Ko-fi](https://img.shields.io/badge/Ko--fi-support-FF6A4D?logo=ko-fi&logoColor=white)](https://ko-fi.com/emoncode)

**A private, modern email app for Android — your email, finally yours.** ✨

Sterna Mail is an open-source email client built for **JMAP**, the new and faster
internet standard for email — with full support for classic **IMAP/SMTP** too. No
ads, no tracking, no Google services. Just your mail, on the server *you* choose.

> _Named after **Sterna**, the genus of terns 🐦 — the Arctic tern flies the
> longest migration of any animal, carrying itself clear across the world and back
> every single year. We liked the idea of mail that travels light and always finds
> its way home._

> **Status: pre-release (and proud of it).** Sterna is already a complete, daily
> drivable email client — read, organise, search, write, schedule, and send mail
> (with attachments) over JMAP or IMAP/SMTP, across multiple accounts, fully
> offline-capable, with push notifications and remote content blocked by default.
> It's pre-1.0, so expect the odd rough edge — and please tell us about them!

## 📸 A look

| Inbox (light) | Inbox (dark) | Reading | Compose | Appearance |
|:---:|:---:|:---:|:---:|:---:|
| ![Inbox, light theme](docs/screenshots/inbox-light.png) | ![Inbox, dark theme](docs/screenshots/inbox-dark.png) | ![Reading a message](docs/screenshots/message.png) | ![Composing](docs/screenshots/compose.png) | ![Appearance settings](docs/screenshots/settings.png) |

*Light "Arctic" and dark "Pelagic" themes, a calm sea-teal accent with a warm
coral touch — the tern's beak. 🪸*

## 📲 Download & install

There's no app-store listing yet, but you can grab the **preview APK** today:

1. **[⬇️ Download the latest APK](https://codeberg.org/emon/sterna-mail/releases/latest)** (under *Assets*).
2. Open the downloaded `.apk` on your phone. Android will ask whether to **allow
   installing apps from this source** — accept it for your browser or file manager.
3. Tap **Install**, then open Sterna and add your account. 🎉

> ⚠️ The preview is signed with a **test key**, so the eventual official build (and
> the future [F-Droid](https://f-droid.org/) release) will install as a separate
> app. It's pre-1.0 — back up nothing important to it just yet. 😉

Prefer to build it yourself? See [CONTRIBUTING.md](CONTRIBUTING.md).

## 🤔 Why JMAP?

Most email apps talk to your server using an old protocol called IMAP. **JMAP** is
its modern successor: it syncs faster, uses less battery and data, and was designed
for today's phones. Servers like [Stalwart](https://stalw.art/) speak JMAP, and
Sterna is built to make the most of it. ⚡

Sterna also speaks classic **IMAP and SMTP**, so it works with any standard mail
provider — pick the protocol when you add an account, or just type your email and
let autodiscovery find the server for you.

## 💚 What Sterna stands for

- **🔒 Privacy first.** No tracking, no ads, no analytics, no Google services.
  Remote images and tracking pixels are blocked by default; tapped links can be
  stripped of tracking parameters and confirmed before opening.
- **🆓 Free and open.** Licensed under the GPLv3 — anyone can read, audit, and
  improve the code.
- **🎨 Modern and calm.** A clean, fast Material 3 interface with its own brand
  identity that follows your phone's theme and language.

## ✨ Features

**📥 Reading & organising**
- Unified inbox across multiple accounts (JMAP and/or IMAP/SMTP), fully offline
  (local cache); large folders page in as you scroll, fetching older mail from
  the server
- Conversation view — threads collapse into one row with a message count
- Nested folders shown as a collapsible tree; create / rename / delete, plus
  subfolders
- Configurable swipe actions, flag/star, archive & delete with undo, multi-select
  bulk actions, snooze, report spam, empty trash (with undo), sort and unread-only
  filter
- Search-as-you-type (server-side + instant local results) with advanced filters
  (from, subject, has-attachment, date)

**✍️ Writing & sending**
- Compose with recipient chips + autocomplete and email validation, multiple
  identities each with its own signature, attachments, and drafts
- Undo send, schedule send (with a screen to view/cancel pending sends), and
  "forgot attachment?" / large-recipient-list reminders

**🔑 Accounts & setup**
- Add an account with just email + password (`/.well-known/jmap` autodiscovery),
  password-free **OAuth 2.0** sign-in (device flow), or manual IMAP/SMTP with
  provider presets; a "test connection" button before saving
- Per-account colour, sync window, and notification toggle; encrypted credential
  storage (AndroidKeyStore); app lock (biometric / PIN)

**🔔 Notifications & server power**
- Push for new mail with **no Google / FCM** (JMAP EventSource or IMAP IDLE),
  grouped per account, with quiet hours
- Server-side **vacation responder** and **Sieve filter rules**, mailbox quota
  display

**🎨 Polish**
- Sterna's own Arctic (light) / Pelagic (dark) palette, optional Material You,
  adjustable message text size, settings export/import, a screen-reader-friendly
  UI, and coastal empty-state illustrations

The full, tracked feature set lives in [FEATURES.md](FEATURES.md).

**🛠️ Planned:** encrypted email (OpenPGP), home-screen widgets, and more.

## 🌍 Languages

Sterna's interface is fully translated into nine languages:

🇬🇧 English · 🇫🇷 French · 🇩🇪 German · 🇪🇸 Spanish · 🇮🇹 Italian · 🇵🇹 Portuguese ·
🇳🇱 Dutch · 🇷🇺 Russian · 🇵🇱 Polish

It also respects your system language out of the box, and you can override it
per-app in Settings → Appearance.

## 👩‍💻 For developers

Architecture and build instructions live in [ARCHITECTURE.md](ARCHITECTURE.md)
and [CONTRIBUTING.md](CONTRIBUTING.md). The planned and shipped feature set is
tracked in [FEATURES.md](FEATURES.md). Contributions, bug reports and ideas are
very welcome. 🙌

## ☕ Support

Sterna is free and open, and always will be. If it's useful to you and you'd like
to help fuel its development, you can **[buy me a coffee on Ko-fi](https://ko-fi.com/emoncode)**.
Entirely optional — thank you for even considering it. 💚

## 📄 License

[GNU General Public License v3.0](LICENSE).
