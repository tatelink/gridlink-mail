# Security

This document describes Gridlink's security posture, threat model, and how to report
vulnerabilities. It is aimed at security researchers and contributors.

Gridlink is an email client: it renders untrusted content (email is attacker-authored
by definition) and talks to remote mail servers over the network. The two highest-risk
surfaces are therefore **message rendering** and **transport/credentials**, and most of
the hardening below concentrates there.

## Reporting a vulnerability

Please report security issues **privately** rather than opening a public issue:

- **Preferred:** open a private security advisory at
  <https://github.com/tatelink/gridlink-mail/security/advisories/new>. Private vulnerability
  reporting is enabled, so this is visible only to the maintainer until a fix ships.
- Otherwise email **security@eightpointlabs.com**.

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
  and another app on the device attempting IPC against Gridlink's components.

Out of scope:

- The security of the mail server you choose (Gridlink cannot make a hostile provider
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
- **Certificate authorities installed by the user count as valid.** Since Android 7 an app
  trusts only the system CA store by default, which leaves a self-hosted server with its own
  authority unreachable. The app's network security configuration
  (`app/src/main/res/xml/network_security_config.xml`) adds the user store next to the system
  one, so a certificate chaining to an authority you installed yourself in Android's settings
  is accepted (Codeberg #93). Nothing else moves: the chain and the hostname are still
  verified on every handshake, and there is still no "trust this certificate anyway" prompt
  anywhere in Gridlink. The other side of that choice, plainly: a CA pushed by an employer on a
  managed profile, or one slipped onto the device by someone else, now also validates for
  Gridlink, and whoever controls it can intercept the connection. K-9 Mail and FairEmail decide
  the same way; banking apps decide the opposite way. For a client whose users largely run
  their own servers, trusting the store the user controls is the coherent trade, and the
  decision stays where Android already manages it, with its own permanent warning and a place
  to review and remove the certificate. The configuration is app-wide rather than mail-only,
  so such an authority is equally trusted for any remote content you choose to load inside a
  message (remote content stays blocked until you ask for it).
- **No TLS downgrade on redirects.** The JMAP HTTP client follows redirects for
  `/.well-known/jmap` discovery but refuses HTTPS→HTTP redirects
  (`followSslRedirects(false)`), so an injected same-host redirect cannot leak the
  `Authorization` header over cleartext.
- **Cleartext is never a default and never silent.** The sign-in screen offers no cleartext
  option: an account is created over TLS or STARTTLS. The account editor does offer
  `ConnectionSecurity.NONE`, because a bridge on 127.0.0.1 (Proton Bridge and the like) is a
  legitimate cleartext target, but selecting it puts a warning under the selector naming what
  it costs. A K-9 settings import whose file does not state a connection security I recognise
  falls back to an encrypted setting and reports the account as one to check, rather than
  quietly configuring it in the clear.

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
- **Every path that pulls bytes into memory has a ceiling**, in both directions. Inbound
  (`DownloadLimits`): a JMAP download is refused from the size the message announces, before
  the round-trip is spent, and the cap is applied ahead of the protocol branch so IMAP is held
  to it too. The ceilings differ by consent — an inline image is fetched the moment a message
  is opened and gets 10 MB, an attachment the user tapped gets 50 MB, a calendar invite 2 MB.
  Outbound (`OutgoingLimits`): a file picked to send is capped at 25 MB, checked first against
  the size the content provider declares and then again while reading, because that declared
  size is a hint a provider may omit or under-report, and an unbounded read of a picked URI is
  the app agreeing to allocate whatever it is handed.
- MIME parsing is **depth- and part-count-bounded** to prevent stack overflow / quadratic
  blow-up from deeply nested or part-flooded multipart messages.
- Decoded header display values are stripped of **control characters and Unicode bidi
  overrides** to reduce display-name spoofing.
- Outgoing message headers, the SMTP envelope (`MAIL FROM` / `RCPT TO`) and IMAP command
  arguments all reject embedded **CR/LF**, closing SMTP header injection (e.g. a hidden
  `Bcc:`), envelope injection (a hidden recipient) and IMAP command injection. An address
  carrying a line break is also refused before the message is queued, so the two paths that
  skip the composer's own validation (a notification quick reply, whose address comes from a
  `From` header a hostile server can craft, and a `mailto:` link hiding a `%0D%0A`) fail
  visibly instead of reaching an address the sender never saw.

### Credentials & data at rest

- Account secrets (passwords / OAuth refresh tokens) are encrypted with an **AES-256-GCM**
  key held in the **Android KeyStore** (non-exportable); only IV + ciphertext are stored.
  Each blob is bound to its account via GCM **AAD** so it cannot be relocated between
  slots, and the key is deleted on a full account reset.
- **OAuth refreshes are single-flight per account, and repeated failures back off.** Providers
  that rotate the refresh token retire the old one the instant a new one is issued, so two
  concurrent refreshes spend the same token and the loser is left holding one the server has
  already killed, permanently signing the account out. The JMAP path and the IMAP/SMTP path
  reach a token independently, so that race is reachable in normal use; a process-wide lock per
  account serialises them and the second caller takes the first one's result. A run of failures
  (which is what a revoked token looks like) then earns a growing cooldown, so a dead account
  cannot turn into an unbounded loop against the provider's token endpoint.
- **Backups are disabled** (`allowBackup="false"`), with backup/data-extraction rules as a
  backstop that exclude the credential store, the Room cache, and settings from cloud
  backup and device transfer.
- With **app lock** enabled, the window is marked `FLAG_SECURE`, keeping message content
  and the credential-entry screen out of the recents thumbnail and screenshots.
- **The app lock is a visual barrier, not encryption, and the message cache is not
  encrypted.** `BiometricPrompt` is called without a `CryptoObject`, and nothing is
  re-encrypted when the app locks; the Room database (message headers, cached bodies, the
  search index) is stored unencrypted in the app's private directory. So the lock is worth
  what it claims and no more: it keeps someone who picks up your unlocked phone from reading
  your mail. It does not defend against extraction: root, an ADB or recovery path into the
  app's private directory, and a filesystem image all reach the cache without ever meeting
  the prompt (Android's own backup is disabled, per the bullet above, but that is one route
  out of several). Account secrets are the exception: they stay behind the KeyStore-held key
  described above. I would rather state the real scope than leave a false assurance standing.

### OpenPGP (end-to-end encryption)

- OpenPGP is delegated to the **OpenKeychain** app over the standard `openpgp-api`
  bound-service interface: private keys and passphrases live in OpenKeychain and never
  enter Gridlink's process. Reading decrypts + verifies; composing signs and/or encrypts
  as PGP/MIME (RFC 3156), on both JMAP and IMAP/SMTP.
- **Decrypted content is never persisted**: plaintext of an encrypted message is held in
  an in-memory cache only, never written to the Room body cache and never added to the
  local search index (which indexes headers only). An encrypted message in the outbox
  stores only its ciphertext entity; the plaintext body is cleared at rest.
- The message **subject is not encrypted** (sent in the clear, matching common OpenPGP
  practice); protected headers are out of scope for now.

### Android platform surface

- The only exported component is the launcher `MainActivity`, and it does read untrusted
  input: `mailto:` deep links (VIEW+BROWSABLE and SENDTO filters) plus `ACTION_SEND` /
  `ACTION_SEND_MULTIPLE` in `*/*`, from which it reads `EXTRA_SUBJECT`, `EXTRA_TEXT` and
  `EXTRA_STREAM`. All of it is treated as compose-screen prefill and nothing more. A
  `mailto:` is parsed with the platform `MailTo` parser inside a `runCatching`, so a
  malformed or hostile URI opens nothing at all; the text extras only fill the subject and
  body fields; and of the shared URIs, **only `content:` ones are accepted**, so no app can
  hand over a `file:` path and have Gridlink read its own private storage on the sender's
  behalf. No filesystem path is derived from a shared URI, and the display name is
  CR/LF-filtered before it can reach a MIME header. No intent extra selects an account,
  grants a permission, or sends anything, an address carrying a line break is refused before
  submission, and a consumed payload is stripped from the retained intent so it cannot replay
  on the next read. Push service, the notification receiver, and the FileProvider are not
  exported.
- Notification `PendingIntent`s are `IMMUTABLE` (except the RemoteInput reply, which must
  be mutable and targets a non-exported receiver explicitly).
- The `FileProvider` shares only `cacheDir/attachments/`, with sanitized filenames and
  read-only, single-URI grants.

### Dependencies

- Gridlink bundles no Google Play Services, no Firebase, and no analytics or crash-reporting
  SDK; the dependency list is Android/Compose, OkHttp, kotlinx, Room and the OpenPGP API.
- **Vulnerable dependencies are watched, not scanned in the build.** CI submits the resolved
  dependency graph to GitHub (`gradle/actions/dependency-submission`), which is what makes
  Dependabot's advisory matching see transitive dependencies rather than guessing from build
  files; `.github/dependabot.yml` then raises security and version-update PRs. Scanning
  server-side rather than adding a dependency-check step keeps the vulnerability database
  current without adding minutes to every push.
- The honest limit of that: it is **GitHub-only**. The self-hosted Forgejo mirror runs the
  same build and tests but has no equivalent, so a vulnerable dependency is caught wherever
  this tree is pushed to GitHub and nowhere else.

## Coordinated disclosure

I prefer coordinated disclosure: give me a reasonable window to ship a fix before any
public write-up.

Gridlink itself has no public distribution channel, so a finding here reaches almost
nobody. A finding in code inherited from upstream is a different matter: **[Sterna
Mail](https://codeberg.org/emon/sterna-mail)** is published on F-Droid and has real
users, so please report anything in `core/` or the upstream screens to emon first.
