# Encrypted email with OpenPGP

Sterna can read and send **OpenPGP** mail: messages that are signed (so the
reader can prove they really came from you) and/or encrypted (so only the
intended recipient can read them). This works on both JMAP and IMAP/SMTP
accounts.

This page explains, in plain terms, how it works and how to set it up.

## How it works, in one minute

OpenPGP gives every person **two matching keys**:

- a **public key**, which you hand out freely. Think of it as an open padlock:
  anyone can snap it shut, but only you hold the key that opens it.
- a **private key**, which never leaves your device and is protected by a
  passphrase. It is the only thing that can open padlocks made with your public
  key.

From that, two things follow:

- **Encrypting.** To send Bob a message only he can read, you lock it with
  *Bob's* public padlock. From that moment only Bob's private key can open it,
  not even you, and certainly not the mail servers the message passes through.
- **Signing.** To prove a message is really from you, you seal it with *your*
  private key. Anyone can then check the seal against your public key. If a
  single character was changed in transit, the seal no longer matches and the
  reader is warned.

So to **send someone encrypted mail you need their public key**, and to let
people **send encrypted mail to you (or check your signature) they need yours**.
Public keys are meant to be shared: by email, on a website, or on a key server.

Two limits worth knowing up front:

- **The subject line is not encrypted.** OpenPGP protects the body and
  attachments, not the envelope. Keep sensitive details out of the subject.
- **Sterna never stores decrypted content.** A decrypted message lives only in
  memory while you read it. It is not written to disk, not cached, and not
  search-indexed. Close and reopen the message and it is decrypted again.

## What you need: two apps

Sterna does not handle your secret keys itself. Instead it talks to
**[OpenKeychain](https://f-droid.org/packages/org.sufficientlysecure.keychain/)**,
a dedicated, open-source key-manager app, over Android's OpenPGP interface (the
same one K-9 Mail / Thunderbird for Android uses). Your private key and its
passphrase stay inside OpenKeychain and never enter Sterna's process. Sterna
only ever asks OpenKeychain to sign, encrypt, or decrypt on its behalf, and
OpenKeychain prompts you when it needs your passphrase.

So the setup is two apps:

1. **Sterna** — your mail client (this app).
2. **OpenKeychain** — your keyring.

Install OpenKeychain from
[F-Droid](https://f-droid.org/packages/org.sufficientlysecure.keychain/) (or
from inside Sterna: **Settings → your account → OpenPGP encryption → Get
OpenKeychain**).

## Step 1 — Create your key in OpenKeychain

Open OpenKeychain and either create a new key or import one you already have.

**To create one:**

1. Tap **Create my key**.
2. Enter your name and the email address of the account you will use it with.
   The address matters: Sterna matches keys to recipients by email.
3. Set a strong passphrase. This protects your private key; OpenKeychain will
   ask for it (and can remember it for a while) whenever a message needs your
   private key.
4. Finish. OpenKeychain now holds your key pair.

**If you already have a key** (for example a `.asc` / `.gpg` file, or one on a
key server), use OpenKeychain's **import** option instead and point it at the
file or key.

## Step 2 — Connect the key to your account in Sterna

1. In Sterna, go to **Settings → your account → OpenPGP encryption**.
2. Turn on **Use OpenPGP**.
3. Tap **Your signing key → Choose key**. Sterna asks OpenKeychain to show your
   keys; pick the one for this account. (This does not copy the key into Sterna,
   it just records which key to use.)
4. Optionally turn on **Encrypt by default** so new messages start with
   encryption on whenever every recipient's public key is available.

That is the whole setup. You only do it once per account.

## Step 3 — Send signed / encrypted mail

When composing, a **padlock button** in the toolbar cycles through three modes,
and a short banner explains each as you switch:

| Icon | Mode | What it means |
|------|------|---------------|
| open padlock | **Off** | Not encrypted. Anyone handling the mail can read it. |
| pen / seal | **Sign** | Readable by everyone, but proves it comes from you. |
| closed padlock | **Encrypt** | Encrypted **and** signed. Only the recipient can read it. |

To **encrypt**, Sterna needs a public key for every recipient. A recipient
without a known key is flagged, and you will not be able to send encrypted until
their key is imported into OpenKeychain (ask them for it, or fetch it from a key
server). **Signing** has no such requirement: you can sign to anyone.

A few things behave differently while a message is set to encrypt: it cannot be
saved as a plaintext draft, and scheduled send is unavailable (both would put
readable content on the server). Your own **Sent** copy stays readable, because
Sterna also encrypts it to your own key.

## Step 4 — Read encrypted / signed mail

When you open an OpenPGP message, Sterna asks OpenKeychain to decrypt and verify
it (OpenKeychain may prompt for your passphrase the first time). Above the body
you will see the result:

- a **padlock** if the message was encrypted, and
- a **signature badge** coloured by trust:
  - 🟢 **green** — valid signature from a confirmed key.
  - 🟡 **yellow** — valid, but from a key you have not confirmed, or the signer
    does not match the sender address.
  - 🔴 **red** — the signature does not verify: the content may have been
    altered, or the key is revoked / expired / insecure.
  - ⚪ **grey** — the signer's public key is not in OpenKeychain, so the
    signature cannot be checked. Import their key to verify it.

Sterna also reads legacy **inline PGP** messages, not just modern PGP/MIME.

## Troubleshooting

- **"OpenKeychain is not installed."** Install it from
  [F-Droid](https://f-droid.org/packages/org.sufficientlysecure.keychain/).
- **Can't turn on encryption for a recipient.** Their public key isn't in
  OpenKeychain yet. Import it (from a file they sent, or a key server) and try
  again.
- **Grey signature badge.** You don't have the sender's public key. Import it to
  turn the check green (or yellow until you confirm it).
- **No passphrase prompt / it stopped asking.** OpenKeychain caches your
  passphrase for a configurable time. Adjust or clear that in OpenKeychain's
  settings.
