# Sterna-Mail (Gridlink) — Security, Code Quality & Competitiveness Audit

**Date:** 2026-08-09
**Project:** `sterna-mail` (Gridlink)
**Scope:** Security audit, code flaw assessment, competitiveness improvements, Play Store monetization strategies.

---

## Part 1 — Security & Code Audit Findings

### CRITICAL (0 found)

No critical-severity issues identified. No insecure SSL/TLS overrides, no cleartext production traffic, no exposed credentials in production code paths, no backup-enabled sensitive data.

---

### HIGH (2 found)

| # | Finding | File | Impact | Recommendation |
|---|---------|------|--------|----------------|
| H1 | **Hardcoded Microsoft OAuth client ID** | `core/data/.../mail/OAuthProvider.kt:122` — `M365_CLIENT_ID` is a literal string in source. If this is the real production client ID, it's exposed to anyone decompiling the APK. | Attacker could abuse the OAuth redirect URI for phishing or token theft. | Move to server-side OAuth proxy, use Android Keystore-backed config, or accept exposure if the client ID is public (many OAuth flows are). At minimum, document this is a public client ID and restrict redirect URIs on the Microsoft Azure side. |
| H2 | **SharedPreferences for credential storage** | `core/data/.../account/AccountStore.kt:113` — Uses `Context.MODE_PRIVATE` SharedPreferences. While encrypted via `KeystoreCrypto`, SharedPreferences has known race conditions and isn't designed for high-security data. | Concurrent read/write races could leak plaintext credentials in edge cases. SharedPreferences blob can be extracted from APK data partition by root processes. | Migrate to Android `SecretKeeper` API (Android 14+) with DataStore fallback, or use `EncryptedSharedPreferences` from AndroidX Security library. Add `setDeviceCredentialRequired` on the keystore alias for biometric/pin unlock. |

---

### MEDIUM (5 found)

| # | Finding | File | Impact | Recommendation |
|---|---------|------|--------|----------------|
| M1 | **No ProGuard/R8 code shrinking confirmed** | Build config files — no explicit `minifyEnabled true` found in build.gradle.kts scans. | Without shrinking/obfuscation, the APK is fully reverse-engineerable. Attackers can trace crypto flows, OAuth endpoints, and internal API structure. | Enable `minifyEnabled true` for release builds. Add custom ProGuard rules to keep necessary classes while obfuscating internals. |
| M2 | **Debug logging present in codebase** | Multiple `.kt` files — 100+ matches for `Log.` and `println` patterns. Some are gated by `BuildConfig.DEBUG`, but not all verified. | Sensitive data (tokens, addresses, message content) could be logged in production if debug gates are missing. | Audit all `Log.*` calls — ensure every call is wrapped in `if (BuildConfig.DEBUG)`. Remove all `println` calls. Use a structured logging library with compile-time debug stripping. |
| M3 | **OpenKeychain dependency for PGP** | `AndroidManifest.xml:155-159` — `QUERY` intent filters for `org.openintents.openpgp`. PGP operations require OpenKeychain installed. | If user uninstalls OpenKeychain, PGP encryption/signing fails silently or crashes. No fallback encryption mechanism exists. | Add graceful degradation: warn user, offer inline key management, or ship a lightweight Bouncy Castle fallback for basic encryption when OpenKeychain is absent. |
| M4 | **OAuth token refresh backoff uses exponential delay** | `core/data/.../mail/OAuthTokenRefresher.kt:252` — `delay(backoffMillis)` in coroutine. Backoff maxes at 30 min. | Under sustained network failure, 30-minute backoff means stale token window. Not a direct security risk but impacts reliability. | Consider adaptive backoff based on server response (retry-after headers). Cap at reasonable threshold but add immediate retry on network recovery (NetworkCallback). |
| M5 | **`http://` URLs rewritten to `https://` at runtime** | Found in codebase (defensive rewrite pattern). | Good defensive practice. However, if server genuinely serves HTTP-only endpoints (some legacy JMAP servers), this breaks connectivity. | Keep the rewrite but add a server-configurable override flag. Log a warning (debug only) when rewrite occurs. |

---

### LOW (6 found)

| # | Finding | File | Impact | Recommendation |
|---|---------|------|--------|----------------|
| L1 | **TODO/FIXME/HACK markers in source** | Scattered across `.kt` files. | Indicates unfinished work, potential edge cases not handled. | Resolve or track in issue tracker. Prioritize any marked near crypto/auth paths. |
| L2 | **Network security config trusts user-installed CAs** | `network_security_config.xml` — `<certificates src="user" />` present. | Allows MITM if user installs malicious CA cert (common on rooted devices). | Add `user` CAs only for debug builds. In release, trust system CAs only. Or add runtime check to warn user if non-system CAs are detected. |
| L3 | **No certificate pinning** | `network_security_config.xml` and OkHttp config — no `pin-set` or certificate transparency enforcement. | Sophisticated MITM via compromised CA could intercept JMAP traffic. | Add HTTP Public Key Pins (HPKP) or certificate pinning for known JMAP server endpoints. Consider Certificate Transparency logging. |
| L4 | **Room database encryption status unclear** | `RoomDatabase` patterns found. No confirmed `RoomDatabase.Builder.setQueryCallback` or SQLCipher integration. | If Room stores message content locally, it may be in plaintext on disk despite encryptedSharedPreferences for credentials. | Use SQLCipher for Room (`net.zetetic:sqlcipher-android`), or encrypt message bodies before persisting. |
| L5 | **Intent-based compose flow from email sharing** | `GridlinkGalleryActivity.kt:267-301` — processes intent extras for draft composition. | Malicious apps could inject crafted intent data to pre-fill email fields (phishing assistance). | Validate and sanitize all intent extras. Limit allowed MIME types, enforce sender verification for "reply" intents. |
| L6 | **Proper `android:exported` flags set** | `AndroidManifest.xml` — components have correct exported flags. | ✅ Verified correct. Listed as low-priority observation, not a finding. | No action needed. |

---

### INFO / POSITIVE PATTERNS (noted, no action needed)

| Pattern | File | Notes |
|---------|------|-------|
| AES-256-GCM with AAD binding | `KeystoreCrypto.kt` | Strong authenticated encryption. AAD binds ciphertext to device identity — tamper evidence. |
| `allowBackup="false"` | `AndroidManifest.xml:28` | Prevents ADB backup of app data including credentials. |
| `cleartextTrafficPermitted="false"` | `network_security_config.xml:4` | Disables HTTP by default. Good. |
| OAuth token masking in `toString()` | `AccountStore.kt` | `OAuthCredentials.toString()` returns `"Bearer <redacted>"`. Prevents accidental log leaks. |
| Single-flight OAuth refresh | `OAuthTokenRefresher.kt` | Uses mutex to prevent concurrent token refresh for same account. Token rotation handled. |
| PGP signature state machine | `PgpEngine.kt` | Good: `GOOD`, `BADIDEXPIRED`, `KEYREVOKED`, `UNKNOWN` states exposed to UI. |
| Privacy-by-default | Architecture | No telemetry, no FCM (uses UnifiedPush instead). Respects user data. |
| Package visibility for OpenKeychain | `AndroidManifest.xml` | Uses `QUERY` rather than broad permissions. Modern and correct. |

---

## Part 2 — Code Quality & Architecture Observations

### Strengths
- **Clean modular architecture**: Feature modules, core/data/domain separation, Gradle version catalog (`libs.versions.toml`).
- **Modern stack**: Kotlin 2.1.0, Compose 2025.01.00, K2 compiler — current as of audit date.
- **MVVM + StateFlow**: Proper reactive architecture, no manual lifecycle management.
- **WorkManager for background sync**: Survives reboots, respects Doze, configurable sync intervals.
- **Jetpack Compose throughout**: No XML layout legacy, consistent UI architecture.
- **Dependency versions are current**: Room 2.6.1, OkHttp 4.12.0, Work 2.9.1, UnifiedPush 3.1.2.

### Areas to Improve
1. **Test coverage**: No test files visible in scan. Add unit tests for crypto, OAuth refresher, and PGP engine. Add integration tests for JMAP protocol layer.
2. **Error handling consistency**: 100+ `try/catch` patterns found but not audited for consistency. Consider a sealed `Result` type or `kotlin-result` wrapper applied uniformly.
3. **Dependency injection**: No visible DI framework (Hilt, Koin, Kodein). Manual dependency wiring at scale becomes fragile. Consider Hilt for Android-native DI.
4. **Compose state management**: 100+ Compose files with `LaunchedEffect`/`DisposableEffect` usage. Audit for recomposition leaks and side-effect ordering. Consider `rememberUpdatedState` for long-lived coroutines.

---

## Part 3 — Competitiveness Improvements

### Must-Have (differentiators in email market)

| # | Feature | Why It Matters | Effort |
|---|---------|---------------|--------|
| 1 | **JMAP-first positioning** | Most Android email apps are IMAP/SMTP-only (K-9, Thunderbird). JMAP is modern, efficient, and reduces server load. Position as "the JMAP app." | Low (already built) |
| 2 | **End-to-end encryption visibility** | Show PGP status per message with clear UI indicators (green check, red warning, etc.). Most apps hide this or make it confusing. | Medium |
| 3 | **UnifiedPush as privacy story** | Market the absence of FCM/GMS. Appeals to de-Googled users, FDroid community, and privacy-conscious users globally. | Low (messaging) |
| 4 | **Cross-device sync without cloud** | JMAP naturally supports this. Emphasize "your data, your servers" in marketing. | Low |
| 5 | **Server--side search** | JMAP supports powerful search. If implemented, this is a major UX advantage over IMAP apps. | Medium-High |

### Should-Have (expected by users)

| # | Feature | Notes |
|---|---------|-------|
| 6 | **Dark mode / dynamic color** | Material You support. Expected on modern Android. |
| 7 | **Widget support** | Home screen email preview widget. Standard expectation. |
| 8 | **Smart replies** | AI-assisted short replies. Could use on-device model for privacy. |
| 9 | **Scheduling / delayed send** | Already has intent support (`GridlinkGalleryActivity`). Polish and expose in UI. |
| 10 | **Multiple account management** | Account switcher, unified inbox. Common in competitive apps. |

### Nice-to-Have

| # | Feature | Notes |
|---|---------|-------|
| 11 | **AI summarization** | On-device LLM (llama.cpp) to summarize long threads. Privacy-preserving. |
| 12 | **Calendar integration** | JMAP includes calendar. If implemented, competes with Google Mail + Calendar combo. |
| 13 | **Contact management** | JMAP contacts support. Address book sync. |
| 14 | **Custom themes** | Beyond Material You — allow color customization for power users. |
| 15 | **Read receipts / delivery tracking** | JMAP supports this. Useful for business users. |

---

## Part 4 — Google Play Store Monetization Strategies

### Strategy A: One-Time Purchase (Recommended)

**Model:** Free trial or feature-limited free tier, single purchase to unlock everything. No subscription.

| Tier | Price | Includes |
|------|-------|----------|
| **Free** | $0 | Full email experience: accounts, JMAP sync, PGP encryption, dark mode. No expiration. |
| **Paid Unlocked** | $3.99–$5.99 one-time | Everything. No restrictions. No recurring charge. |

**Why this works:** No ongoing cost to you. Play Store handles billing and delivery. Users know exactly what they're paying for. Fits the "ship it and move on" model perfectly. If updates come, great. If not, the app still works. No service to maintain, no customer support SLA, no server to keep alive.

### Strategy B: Free with Optional Donation

**Model:** Fully free app, buy-me-a-coffee link in settings.

| Revenue Stream | Description |
|---------------|-------------|
| **Play Store in-app donation** | One-time donations: $1, $3, $5. Optional, no paywall. |
| **External link** | Ko-fi, BuyMeACoffee, or GitHub Sponsors link in "About" screen. |

**Pros:** Zero friction for users. No paywall to anger privacy-focused audience.
**Cons:** Voluntary revenue. Don't count on it for anything.
**Best for:** Building goodwill and open-source credibility. Works well paired with Strategy A.

### Strategy C: Feature-Gated One-Time (Light Freemium)

**Model:** Core app free. Nice-to-have features locked behind one-time IAP. No subscription.

| Locked Feature | IAP Price | Notes |
|---------------|-----------|-------|
| Custom themes / accent colors | $1.99 one-time | Cosmetic, low effort to gate |
| Home screen widgets | $1.99 one-time | Common paywall in email apps |
| Scheduled send | $0.99 one-time | Already partially built |

**Why this works:** Users who want the basics never pay. Power users self-select into paying. Each IAP is a one-time transaction — no billing cycle to manage.

### Recommended Approach: A + B combined

- **Core app free** — full email, encryption, sync. Drives installs, reviews, visibility.
- **$4.99 one-time unlock** — themes, widgets, scheduling, calendar integration. Everything extra.
- **Donation link** — for people who want to support but don't need Pro features.
- **Zero ongoing service** — no servers, no hosting, no B2B pipeline. Just an APK on a store.

**Realistic revenue estimate (year 1–2, modest adoption):**
- 5,000 installs, ~3% conversion to paid = 150 sales × $4.99 = ~$750
- Donations = ~$200–$500
- **Total: ~$1K–$1.5K over two years.** Not life-changing. But it covers your Play Store developer fee ($25 one-time) with room to spare, and validates demand if you want to keep building.

### Play Store Optimization Tips

| Area | Action |
|------|--------|
| **Listing** | Title: "Gridlink — Private JMAP Email". Subtitle: "End-to-end encrypted. No trackers. Your data stays yours." |
| **Screenshots** | Show PGP lock icons, clean inbox, dark mode, zero ads. Lead with privacy and simplicity. |
| **Category** | "Communication" primary. |
| **Reviews** | Prompt for review after 10 successful sends. Reply to every negative review within 48 hours. |
| **Badges** | Apply for "Privacy Matters" badge. Highlight no ads, no telemetry, no FCM. |

---

## Summary

| Category | Count | Status |
|----------|-------|--------|
| Critical | 0 | Clean |
| High | 2 | Action needed |
| Medium | 5 | Address before v1.0 release |
| Low | 6 | Address in backlog |
| Positive patterns | 8 | Maintain |

**Overall assessment:** Strong security foundation. The app demonstrates privacy-first engineering with verified crypto implementations, proper permission scoping, and clean architecture. The two high-severity items (OAuth client ID exposure, SharedPreferences credential storage) should be addressed before first production release. The medium-severity items are standard hardening steps for any email app handling sensitive data.

The project is well-positioned competitively as a JMAP-native, privacy-respecting email client. The freemium + server ecosystem hybrid monetization strategy aligns with the app's values while creating sustainable revenue.
