# Security

This document describes Sterna's security posture, threat model, and how to report
vulnerabilities. It is aimed at security researchers and contributors.

Sterna is an email client: it renders untrusted content (email is attacker-authored
by definition) and talks to remote mail servers over the network. The two highest-risk
surfaces are therefore **message rendering** and **transport/credentials**, and most of
the hardening below concentrates there.

## Reporting a vulnerability

Please report security issues **privately** rather than opening a public issue:

- Open a confidential/security advisory on the project repository, or
- email the maintainer listed in the repository metadata.

Include a description, affected version (see `versionName` in `app/build.gradle.kts`),
and a proof-of-concept if you have one (e.g. a crafted `.eml`, a server response, or an
`adb`/intent invocation). I aim to acknowledge reports promptly and will credit
reporters who wish to be named once a fix ships.

Please do **not** test against servers or accounts you do not own. A local Stalwart
instance is the easiest safe target.

## Threat model

In scope:

- **Malicious email content** — HTML/CSS, MIME structure, headers, attachments, inline
  images. Assume the sender is hostile and the message is crafted to exploit the client.
- **Malicious or compromised mail server / network attacker** — a hostile server, or an
  active man-in-the-middle on the network path, feeding crafted protocol responses or
  attempting to downgrade/intercept the connection.
- **Local attacker with brief physical access** — recents/screenshots, device backups,
  and another app on the device attempting IPC against Sterna's components.

Out of scope:

- The security of the mail server you choose (Sterna cannot make a hostile provider
  private — see [PRIVACY.md](PRIVACY.md)).
- A fully compromised device / OS, root malware, or hardware attacks.
- Physical attacks with unlimited time against a powered-off device beyond what the
  Android KeyStore provides.

## Hardening measures

### Transport (JMAP / IMAP / SMTP)

- **TLS hostname verification is enforced.** The hand-rolled IMAP/SMTP clients enable
  RFC 2818 endpoint identification (`endpointIdentificationAlgorithm = "HTTPS"`) on every
  `SSLSocket` — for implicit TLS and after every STARTTLS upgrade — so a certificate that
  chains to a valid CA but does not match the server hostname is rejected. Credentials are
  only ever sent after the TLS handshake completes.
- **No TLS downgrade on redirects.** The JMAP HTTP client follows redirects for
  `/.well-known/jmap` discovery but refuses HTTPS→HTTP redirects
  (`followSslRedirects(false)`), so an injected same-host redirect cannot leak the
  `Authorization` header over cleartext.
- Plaintext (`MailSecurity.NONE`) is intended only for local testing and is not selectable
  in the UI.

### Message rendering (WebView)

- **JavaScript is disabled** in the message WebView, there is **no `JavascriptInterface`
  bridge**, and content is loaded with a **null base URL** (opaque origin). File and
  content access are disabled, as are DOM storage, geolocation, and file-URL access.
- **Remote content is blocked by default**, default-deny: only `data:`, `cid:`, and
  `about:` sub-resources load; everything else (including protocol-relative `//host` URLs)
  is refused until the user shows images or allowlists the sender. This prevents tracking
  pixels and read receipts on open.
- A **Content-Security-Policy** meta tag is injected as defense-in-depth, blocking
  scripts, plugins, iframes, and form submissions outright.
- **Link handling is allowlisted.** Only `http`, `https`, `mailto`, `tel`, `sms`, and
  `geo` links are handed to the system; `intent:`, `file:`, `content:`, `javascript:`,
  and `data:` are never forwarded. Auto-navigations (e.g. `<meta refresh>`) without a user
  gesture are ignored, and tracking parameters are stripped from opened links.

### Parsing untrusted input

- IMAP literals are **size-capped** so a hostile `{N}` cannot trigger an out-of-memory
  allocation; oversized literals are drained and discarded.
- MIME parsing is **depth- and part-count-bounded** to prevent stack overflow / quadratic
  blow-up from deeply nested or part-flooded multipart messages.
- Decoded header display values are stripped of **control characters and Unicode bidi
  overrides** to reduce display-name spoofing.
- Outgoing message headers and IMAP command arguments reject embedded **CR/LF**, closing
  SMTP header injection (e.g. hidden `Bcc:`) and IMAP command injection.

### Credentials & data at rest

- Account secrets (passwords / OAuth refresh tokens) are encrypted with an **AES-256-GCM**
  key held in the **Android KeyStore** (non-exportable); only IV + ciphertext are stored.
  Each blob is bound to its account via GCM **AAD** so it cannot be relocated between
  slots, and the key is deleted on a full account reset.
- **Backups are disabled** (`allowBackup="false"`), with backup/data-extraction rules as a
  backstop that exclude the credential store, the Room cache, and settings from cloud
  backup and device transfer.
- With **app lock** enabled, the window is marked `FLAG_SECURE`, keeping message content
  and the credential-entry screen out of the recents thumbnail and screenshots.

### OpenPGP (end-to-end encryption)

- OpenPGP is delegated to the **OpenKeychain** app over the standard `openpgp-api`
  bound-service interface: private keys and passphrases live in OpenKeychain and never
  enter Sterna's process. Reading decrypts + verifies; composing signs and/or encrypts
  as PGP/MIME (RFC 3156), on both JMAP and IMAP/SMTP.
- **Decrypted content is never persisted**: plaintext of an encrypted message is held in
  an in-memory cache only, never written to the Room body cache and never added to the
  local search index (which indexes headers only). An encrypted message in the outbox
  stores only its ciphertext entity; the plaintext body is cleared at rest.
- The message **subject is not encrypted** (sent in the clear, matching common OpenPGP
  practice); protected headers are out of scope for now.

### Android platform surface

- The only exported component is the launcher `MainActivity`, which reads no untrusted
  intent extras or deep links. Push service, the notification receiver, and the
  FileProvider are not exported.
- Notification `PendingIntent`s are `IMMUTABLE` (except the RemoteInput reply, which must
  be mutable and targets a non-exported receiver explicitly).
- The `FileProvider` shares only `cacheDir/attachments/`, with sanitized filenames and
  read-only, single-URI grants.

## Coordinated disclosure

I prefer coordinated disclosure: give me a reasonable window to ship a fix before any
public write-up. Because Sterna is distributed through F-Droid and Obtainium (with the
Codeberg releases as the source of truth), users may take time to update, so please
factor that into disclosure timing.
