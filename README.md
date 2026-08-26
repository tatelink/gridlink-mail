<h1 align="center">Gridlink Mail</h1>

<p align="center">
  <b>All your mail. None of the baggage.</b><br>
  A fast, private <b>JMAP email client for Android</b>, fluent in classic <b>IMAP/SMTP</b> too.<br>
  No ads. No tracking. No Google.
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: GPLv3" src="https://img.shields.io/badge/license-GPLv3-blue"></a>
</p>

Gridlink Mail is open-source and built for **JMAP**, the new and faster internet
standard for email, with full support for classic **IMAP/SMTP** too. Just your
mail, on the server *you* choose.

## Fork notice

Gridlink Mail is a fork of **[Sterna Mail](https://codeberg.org/emon/sterna-mail)**
by **emon**, licensed under the GPLv3 and reused under its terms. The mail engine,
the sync layer, the OpenPGP support and most of the settings are emon's work. What
this fork changes is the front end and the setup flow.

This is not an official Sterna release and emon does not support it. Bugs you hit
here are this fork's problem, not theirs. If you want the original, upstream is the
better-tested, actively maintained app and you should go get it.

## Download & install

**Signed APK:** grab the latest from
[Releases](https://github.com/tatelink/gridlink-mail/releases). Android will warn you about
installing outside a store; that is expected for a sideloaded app.

Verify what you installed before you trust it:

```
apksigner verify --print-certs gridlink-mail-1.0.0.apk
```

The signing certificate SHA-256 is:

```
17:FA:C1:D9:74:0C:DC:F9:FD:B1:E6:85:78:31:B2:FA:98:73:F0:86:9A:64:32:E3:09:80:AA:AD:73:2D:CA:96
```

The release APK is reproducible: a clean build from the matching tag produces a byte-identical
file, so you do not have to take my word for what is in it. See
[CONTRIBUTING.md](CONTRIBUTING.md) to rebuild it and compare.

**F-Droid:** submitted and under review
([fdroiddata!46061](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/46061)). Not available
there yet. This section will say so when it is.

**From source:** see [CONTRIBUTING.md](CONTRIBUTING.md).

## Why JMAP?

Most email apps talk to your server using an old protocol called IMAP. **JMAP** is
its modern successor: it syncs faster, uses less battery and data, and was designed
for today's phones. Servers like [Stalwart](https://stalw.art/) speak JMAP, and
Gridlink is built to make the most of it.

Gridlink also speaks classic **IMAP and SMTP**, so it works with any standard mail
provider. Pick the protocol when you add an account, or just type your email and
let autodiscovery find the server for you.

### Self-hosted server with a self-signed certificate

Gridlink checks that the certificate really belongs to the server you asked for, and it
offers no way to accept one that does not match. That is deliberate: an "accept anyway"
button is how interception gets waved through. Most self-signed certificates predate
this rule and only carry a Common Name, so sign-in fails with:

```
No subjectAltNames on the certificate match
```

The fix is on the server, not in the app: regenerate the certificate with a
**subjectAltName** covering the exact host name or IP address you connect to. With
`keytool`, add the extension when you generate the key pair:

```
keytool -genkeypair -keyalg RSA -alias myserver -validity 365 \
  -dname "CN=mail.example.org, O=Example, C=FR" \
  -ext "SAN=DNS:mail.example.org,IP:192.168.1.10"
```

With `openssl`, put `subjectAltName = @alt_names` in the `v3_req` section of your
config and list every name and address under `[alt_names]` (`DNS.1 =`, `IP.1 =`).

A correctly named certificate still has to be trusted, which is the other half of the
job. Android trusts the authorities that ship with the system, so vouching for your own
server means installing your own authority on the phone: **Settings → Security →
Encryption & credentials → Install a certificate → CA certificate**, then pick the
certificate that signed your server's one (a bare self-signed certificate can be
installed this way only if it is marked as a CA). Gridlink accepts authorities you
installed yourself alongside the system ones, so sign-in then works with the chain and
the host name still checked. Android keeps a permanent warning while such a certificate
is installed, saying a third party may be able to monitor the network. That warning is
expected: the decision to trust an authority belongs to the system, where you can review
and remove it, rather than to a button inside a mail app.

Thanks to [KaKeBr](https://codeberg.org/KaKeBr) for the diagnosis and the recipes
(upstream Codeberg issue #71).

## What Gridlink stands for

- **Privacy first.** No tracking, no ads, no analytics, no Google services.
  Remote images and tracking pixels are blocked by default; tapped links can be
  stripped of tracking parameters and confirmed before opening.
- **Free and open.** Licensed under the GPLv3, so anyone can read, audit, and
  improve the code.
- **Modern and calm.** A clean, fast Material 3 interface that follows your
  phone's theme and language.
- **Offline by construction.** Your mail is on your phone, not fetched when you
  look at it. See below.

## Works with no signal

Most mail apps are online apps with a cache bolted on: they show you a spinner,
ask the server, and draw whatever comes back. Gridlink is the other way round.
Every screen reads the local database and only the local database. Syncing writes
into that database; it never draws anything.

What that means in practice:

- **The app opens on your mail, not on a spinner.** The first frame is the mail
  you already had, whether or not the phone has signal, and whether or not the
  server answers.
- **A refresh never blanks the screen.** New mail appears in a list that stayed
  readable the whole time. Nothing collapses back to a loading state over content
  you were already reading.
- **Messages open offline**, body and all, along with any attachment you have
  opened before. Opening a message costs no round trip.
- **Calendar and contacts work the same way**, from the same cache.
- **Anything you do offline is queued, not lost.** Mail you send goes into a
  persistent outbox that survives the app being killed and retries when the
  network comes back.

The one honest limit: mail that arrived while you were offline is mail the phone
has never seen. Everything you already had is there.

## Features

**Reading & organising**
- Unified inbox across multiple accounts (JMAP and/or IMAP/SMTP), fully offline
  (local cache); large folders page in as you scroll, fetching older mail from
  the server
- Conversation view, so threads collapse into one row with a message count
- Nested folders shown as a collapsible tree; create / rename / delete, plus
  subfolders
- Configurable swipe actions, star, archive and delete with undo, multi-select
  bulk actions, snooze, report spam, empty trash (with undo), sort and unread-only
  filter
- Search-as-you-type (server-side plus instant local results) with advanced filters
  (from, recipient, subject, has-attachment, date); Trash and Spam are left out
- Calendar invites preview as an event card; one tap adds them to your calendar
  app (no calendar permission needed)

**Writing & sending**
- Compose with recipient chips, autocomplete and email validation, multiple
  identities each with its own signature, attachments, and drafts
- Undo send, schedule send (with a screen to view and cancel pending sends), and
  "forgot attachment?" / large-recipient-list reminders
- Opens `mailto:` links from any app or browser, prefilled (addresses, subject,
  body, cc/bcc)

**Accounts & setup**
- Add an account with just email and password (`/.well-known/jmap` autodiscovery),
  password-free **OAuth 2.0** sign-in (device flow), or manual IMAP/SMTP with
  provider presets; a "test connection" button before saving
- Per-account colour, sync window, and notification toggle; encrypted credential
  storage (AndroidKeyStore); app lock (biometric / PIN)
- Optional: publish the account's CardDAV/CalDAV contacts and calendars into
  **Android itself**, as a real system account, so caller ID, the Calendar app,
  widgets and any people picker see them. Off by default; read-only (edits made
  elsewhere are not sent to the server), and turning it off removes the account
  and everything Gridlink put there

**Encryption**
- **OpenPGP** (via [OpenKeychain](https://f-droid.org/packages/org.sufficientlysecure.keychain/)):
  read and send signed and/or encrypted mail (PGP/MIME) on both JMAP and
  IMAP/SMTP. Decrypted content is never written to disk. See the
  [encryption guide](ENCRYPTION.md) to get started.

**Notifications & server power**
- Push for new mail with **no Google / FCM** (JMAP EventSource, IMAP IDLE or
  **UnifiedPush**), grouped per account, with quiet hours
- Server-side **vacation responder** and **Sieve filter rules**, mailbox quota
  display

The full, tracked feature set lives in [FEATURES.md](FEATURES.md).

## Languages

The interface is translated into nine languages:

English · French · German · Spanish · Italian · Portuguese · Dutch · Russian · Polish

Translations were written for upstream and still say Gridlink where the English
does, but any string this fork adds is English-only until it is translated.

It also respects your system language out of the box, and you can override it
per-app in Settings → Appearance.

## For developers

Architecture and build instructions live in [ARCHITECTURE.md](ARCHITECTURE.md)
and [CONTRIBUTING.md](CONTRIBUTING.md). The planned and shipped feature set is
tracked in [FEATURES.md](FEATURES.md).

## Acknowledgements

Upstream is **[Sterna Mail](https://codeberg.org/emon/sterna-mail)** by emon, and
essentially everything under `core/` is theirs. Thank you for building it, and for
licensing it so it could be built on.

Sterna's interface was in turn freely inspired by
**[K-9 Mail](https://github.com/thunderbird/thunderbird-android)** (now Thunderbird
for Android), the venerable open-source Android mail client. Many of the interaction
patterns Gridlink inherits, the way you swipe, triage and read your mail, are the
fruit of years of work by the K-9 community.

## License

[GNU General Public License v3.0](LICENSE).
