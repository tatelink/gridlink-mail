# Architecture

This document describes how Jmail is built. It is aimed at developers.

## Goals

- A production-quality JMAP email client for Android.
- Privacy by default; no telemetry.
- Clean, testable layers so IMAP/SMTP can be added later without rework.

## Stack

- **Language:** Kotlin (K2 compiler).
- **UI:** Jetpack Compose + Material 3 (Material You dynamic color).
- **Architecture:** MVVM with unidirectional data flow (`ViewModel` + `StateFlow`).
- **Async:** Kotlin Coroutines + Flow.
- **Networking:** OkHttp + kotlinx.serialization (JMAP is batched JSON, so a thin
  typed client rather than Retrofit).
- **Persistence (planned):** Room for the offline cache, DataStore for settings,
  AndroidKeyStore-backed encryption for credentials.
- **Background (planned):** WorkManager for sync; JMAP push via EventSource/WebSocket.
- **Build:** Gradle (Kotlin DSL) with a version catalog (`gradle/libs.versions.toml`).

## Module layout

| Module        | Type             | Responsibility                                            |
|---------------|------------------|-----------------------------------------------------------|
| `:app`        | Android app      | Compose UI, navigation, ViewModels, manual DI container.  |
| `:core:jmap`  | Kotlin JVM lib   | JMAP protocol client — session, batched method calls, typed RFC 8620/8621 models. Pure JVM so it is unit-testable without Android. |
| `:core:data`  | Android library  | Repositories, Room offline cache (kept internal behind `DataFactory`), and the AndroidKeyStore-encrypted account store. |

## JMAP layer (`:core:jmap`)

JMAP is defined by **RFC 8620** (core) and **RFC 8621** (mail). The client will:

1. Fetch the **Session** resource (`/.well-known/jmap`) to discover capabilities,
   the account id, and the API / download / upload / EventSource URLs.
2. Authenticate (Basic auth first; Bearer/OAuth-ready).
3. Send **batched method calls** (e.g. `Mailbox/get`, `Email/query`, `Email/get`,
   `Email/set`, `EmailSubmission/set`) with result back-references (`#`).
4. Sync incrementally using per-type `state` strings and the `/changes` methods.

Reference implementations consulted: the `ltt.rs` / `jmap-mua` libraries and the RFCs.

## Storage & data strategy

Local storage is **per-account, protocol-agnostic, and a bounded mirror of the
server with opt-in offline retention**. The cache layer must not bake in JMAP
semantics: IMAP/SMTP support is planned, and IMAP changes the trade-offs (costly
round-trips, no server-side threads/blobs/query, slow search), so it needs
heavier body/attachment caching and makes the **sync window a first-class,
user-facing setting** rather than a JMAP implementation detail.

**What is stored, and where**

| Data | Location | Notes |
|---|---|---|
| Message list (metadata) | Room `jmail.db` (`emails`, `mailboxes`) | id, sender, subject, 1-line preview, flags, date — small rows. |
| Message bodies | Memory on open (today) | Opt-in on-disk persistence planned for offline + IMAP (see retention). |
| Attachments | `cacheDir/attachments/` | Downloaded on demand; opened via `FileProvider`. |
| Inline images (`cid:`) | Memory only | Rendered as data URIs, never persisted. |
| Credentials | SharedPreferences, AndroidKeyStore-encrypted | In `AccountStore`. |
| Settings | DataStore (`SettingsRepository`) | Reactive `Flow` per setting. |

**Retention & eviction**

- **Sync window, per account:** by **count** (50 / 200 / 500 / all) **or age**
  (30 / 90 days / 1 year / all). Default **90 days**. The list is pruned to the
  window (today `replaceMailbox` already deletes rows not in the latest snapshot).
- **Bodies:** not persisted by default (sober, small). A per-account "keep
  messages offline" option persists bodies with an LRU + age cap; this becomes
  important for full offline and for IMAP.
- **Attachments:** `cacheDir/attachments/` is currently **unbounded** — apply a
  size + age cap with LRU eviction (e.g. 200 MB / 30 days). This is the one real
  growth bug today.

**Lifecycle**

- On **sign-out / account removal**, purge that account's Room rows **and** its
  attachment files. Today only credentials are removed, so cached mail and blobs
  survive across accounts — a correctness and privacy gap to close.
- The Room DB uses destructive migration: the cache is disposable and rebuilt
  from the server, so a schema bump simply re-syncs.

**Device vs server**

On-device storage (DB + attachment dir, reportable per account) is distinct from
the **server mailbox quota** (exposable later via the JMAP `Quota` extension).
The storage UI reports device usage; server quota is a separate, optional number.

## Roadmap

- **M0** — Toolchain, project scaffold, "Hello Jmail" running. *(done)*
- **M1** — Connect to a JMAP server (Stalwart), list mailboxes. *(done)*
- **M2** — Read mail: encrypted account persistence, inbox list, message view
  (HTML in a WebView with remote content blocked by default), Room offline
  cache. *(done)*
- **M3** — Actions: read/unread, flag, archive, move, delete.
- **M4** — Compose and send.
- **M5** — Push, notifications, multi-account, incremental sync, CI, F-Droid.

Beyond M5 the work shifts from "core mail client" to "modern, complete app".
The milestones below are ordered by user value against effort; the JMAP-native
items (⭐) are deliberately early because RFC 8620/8621 makes them cheap. The
full catalogue of features and their status lives in [FEATURES.md](FEATURES.md).

- **M6 — JMAP quick wins.** ⭐ Conversation threading (`Thread`), server-side
  search (`Email/query` + `SearchSnippet`), multiple identities + per-identity
  signatures (`Identity`), schedule send (`EmailSubmission` `sendAt`), vacation
  responder (`VacationResponse`). Low effort, high polish — features that are
  hard over IMAP but largely free here.
- **M7 — Triage & organisation.** Unified inbox, configurable swipe actions,
  multi-select / bulk actions, snooze, folder management, quick filters.
- **M8 — Privacy & security.** App lock (biometric / PIN), per-sender image
  allowlist, link/tracking-parameter hardening, OpenPGP via OpenKeychain.
- **M9 — Polish & reach.** Theme toggle and list density, contact avatars,
  home-screen widgets, accessibility pass (TalkBack, font scaling), quota
  display, calendar/`.ics` preview.
