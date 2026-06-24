# Privacy

**Short version: Sterna collects nothing. It talks only to the mail server you
configure, and everything else stays on your device.**

Sterna is free software (GPLv3) with no business model built on your data. There
is no Sterna account, no Sterna server, and no company in the middle. This document
explains exactly what that means, in plain terms. Last updated: 2026-06-21.

## What Sterna does NOT do

- **No analytics or telemetry.** No usage statistics, no event tracking, nothing
  phoned home — ever.
- **No advertising and no ad SDKs.**
- **No third-party trackers.** The app contains no tracking or profiling
  libraries.
- **No crash reporting to anyone.** Crashes are not sent off your device.
- **No Google services.** Sterna does **not** use Firebase, Google Play Services,
  or Firebase Cloud Messaging (FCM). Push works over the JMAP standard
  (EventSource) directly with your own server, so the app runs fully on
  de-Googled devices.
- **No proprietary dependencies.** Sterna is built only from free/open-source
  libraries.

## Data stored on your device

Everything Sterna keeps lives locally on your phone and is never uploaded anywhere
except, where relevant, back to your own mail server:

- **Account credentials** — stored encrypted, protected by the Android KeyStore.
- **Cached mail** — messages, threads, and mailboxes are cached locally so the
  app works offline. This cache is removed when you sign the account out.
- **Settings** — your preferences (e.g. notification scope).

This data is removed when you remove the account or uninstall the app, subject to
normal Android storage behaviour.

## Network connections

Sterna makes network connections to **one place only: the JMAP mail server you
enter when you add an account.** It does not contact any other host, server, or
third-party service.

Concretely, the app:

1. Discovers your server's capabilities at `/.well-known/jmap`.
2. Sends JMAP requests (to read, search, send, and sync mail) to that server.
3. Holds an EventSource (push) connection to that server to learn about new mail.

There is no intermediary. Your mail never passes through any infrastructure
operated by the Sterna project.

## Remote content is blocked by default

Many emails contain remote images and **tracking pixels** that report back to the
sender when (and sometimes where) you open a message. Sterna **blocks remote
content in messages by default**, so simply opening an email does not leak that
you read it. Inline images that are part of the message itself are rendered from
the message, not fetched from the network.

## What your mail server can see

Sterna cannot make your email provider private. Whatever server you connect to
necessarily processes your messages and sees your requests — that is true of any
email client. If this matters to you, connect Sterna to a server you trust or one
you run yourself (for example, a self-hosted [Stalwart](https://stalw.art/)
instance). Choosing and trusting your provider is the one privacy decision Sterna
leaves in your hands.

## Permissions and why they are needed

Sterna requests the minimum set of permissions:

| Permission | Why |
|---|---|
| `INTERNET` | Connect to your JMAP mail server. This is the only outbound network use. |
| `POST_NOTIFICATIONS` | Show new-mail notifications (Android 13+). Optional — you can deny it. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Keep the JMAP push connection alive to deliver new mail without Google's FCM. |

Sterna asks for no other permissions — no contacts, no location, no storage
beyond the app's own sandbox, no microphone or camera.

## Changes to this policy

If this policy changes, the change will be visible in the project's Git history,
and the "Last updated" date above will be revised.

## Contact

Questions about privacy can be raised as an issue on the project repository. For
security-sensitive reports, see [SECURITY.md](SECURITY.md) when available.
