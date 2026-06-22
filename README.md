# Jmail

[![CI](https://codeberg.org/emon/jmail/actions/workflows/ci.yml/badge.svg?branch=main)](https://codeberg.org/emon/jmail/actions)

**A private, modern email app for Android.**

Jmail is an open-source email client built for **JMAP** — a new, faster
internet standard for email — with full support for classic **IMAP/SMTP** too.
It is designed to be private by default, pleasant to use, and fully free
software.

> Status: pre-release. Jmail is a working email client — read, organise,
> search, write, and send mail (with attachments) over JMAP or IMAP/SMTP,
> across multiple accounts, fully offline-capable, with push notifications and
> remote content blocked by default. There is no public build yet — see
> [Installing](#installing).

## About JMAP

Most email apps talk to your mail server using an old protocol called IMAP.
JMAP is its modern successor: it syncs faster, uses less battery and data, and
was designed for today's phones. Servers like [Stalwart](https://stalw.art/)
speak JMAP, and Jmail is built to make the most of it.

Jmail also speaks classic **IMAP and SMTP**, so it works with any standard mail
provider — you pick the protocol when you add an account.

## What Jmail stands for

- **Privacy first.** No tracking, no ads, no analytics. Remote images and
  tracking pixels are blocked by default.
- **Free and open.** Licensed under the GPLv3. Anyone can read, audit, and
  improve the code.
- **Modern and simple.** A clean, fast interface that follows your phone's
  system theme.

## Features

- Read, organise, and search your mail — works offline (local cache)
- Write, reply, forward, and send — with attachments and drafts
- Multiple accounts (JMAP and/or IMAP/SMTP), with a unified inbox
- Push notifications for new mail (JMAP), no Google services required
- Configurable swipe actions, folders, flags, archive/delete with undo
- Large folders stay smooth — pages load as you scroll, including older mail
  fetched from the server
- Privacy by default: remote images and tracking pixels blocked, app lock, no
  telemetry

Planned: encrypted email (OpenPGP), multiple identities and signatures, message
snooze, richer search filters, and more — tracked in [FEATURES.md](FEATURES.md).

## Installing

There is no public release yet. When one is ready, it will be available here
and (later) on [F-Droid](https://f-droid.org/). Until then, the app can only be
built from source — see [CONTRIBUTING.md](CONTRIBUTING.md).

## For developers

Architecture and build instructions live in
[ARCHITECTURE.md](ARCHITECTURE.md) and [CONTRIBUTING.md](CONTRIBUTING.md). The
planned and shipped feature set is tracked in [FEATURES.md](FEATURES.md).

## License

[GNU General Public License v3.0](LICENSE).
