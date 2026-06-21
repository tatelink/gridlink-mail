# Jmail

**A private, modern email app for Android.**

Jmail is an open-source email client built for **JMAP** — a new, faster
internet standard for email that replaces the decades-old IMAP protocol. It is
designed to be private by default, pleasant to use, and fully free software.

> Status: early development. The app currently builds and runs a welcome
> screen; connecting to a real mailbox is the next milestone.

## What is JMAP, and why does it matter to me?

Most email apps talk to your mail server using an old protocol called IMAP.
JMAP is its modern successor: it syncs faster, uses less battery and data, and
was designed for today's phones. Servers like
[Stalwart](https://stalw.art/) speak JMAP. Jmail is built to make the most of
it.

If your provider doesn't offer JMAP yet, that's fine — support for classic
IMAP/SMTP mail is planned for later.

## What Jmail stands for

- **Privacy first.** No tracking, no ads, no analytics. Remote images and
  tracking pixels are blocked by default.
- **Free and open.** Licensed under the GPLv3. Anyone can read, audit, and
  improve the code.
- **Modern and simple.** A clean, fast interface that follows your phone's
  system theme.

## Planned features

- Read, organise, and search your mail
- Write and send messages
- Multiple accounts
- Push notifications for new mail
- Encrypted email (OpenPGP)
- Offline access

## Installing

There is no public release yet. When one is ready, it will be available here
and (later) on [F-Droid](https://f-droid.org/). Until then, the app can only be
built from source — see [CONTRIBUTING.md](CONTRIBUTING.md).

## For developers

Architecture and build instructions live in
[ARCHITECTURE.md](ARCHITECTURE.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[GNU General Public License v3.0](LICENSE).
