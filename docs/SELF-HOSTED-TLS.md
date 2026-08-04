# Self-hosted server with a self-signed certificate

Sterna checks that the certificate really belongs to the server you asked for, and it
offers no way to accept one that does not match. That is deliberate: an "accept anyway"
button is how interception gets waved through.

If you run your own mail server with a certificate you issued yourself, two things have
to be true for sign-in to work: the certificate must name the host you connect to, and
the authority that signed it must be trusted by the phone. This page covers both.

## 1. Name the host in the certificate

Most self-signed certificates predate this rule and only carry a Common Name, so sign-in
fails with:

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

## 2. Trust the authority on the phone

A correctly named certificate still has to be trusted, which is the other half of the
job. Android trusts the authorities that ship with the system, so vouching for your own
server means installing your own authority on the phone: **Settings → Security →
Encryption & credentials → Install a certificate → CA certificate**, then pick the
certificate that signed your server's one (a bare self-signed certificate can be
installed this way only if it is marked as a CA). Sterna accepts authorities you
installed yourself alongside the system ones, so sign-in then works with the chain and
the host name still checked.

Android keeps a permanent warning while such a certificate is installed, saying a third
party may be able to monitor the network. That warning is expected: the decision to trust
an authority belongs to the system, where you can review and remove it, rather than to a
button inside a mail app. The reasoning behind that choice, and what it costs, is spelled
out in [SECURITY.md](../SECURITY.md#transport-jmap--imap--smtp).

Thanks to [KaKeBr](https://codeberg.org/KaKeBr) for the diagnosis and the recipes
(Codeberg issue #71).
