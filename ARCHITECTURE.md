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

## Roadmap

- **M0** — Toolchain, project scaffold, "Hello Jmail" running. *(done)*
- **M1** — Connect to a JMAP server (Stalwart), list mailboxes. *(done)*
- **M2** — Read mail: encrypted account persistence, inbox list, message view
  (HTML in a WebView with remote content blocked by default), Room offline
  cache. *(done)*
- **M3** — Actions: read/unread, flag, archive, move, delete.
- **M4** — Compose and send.
- **M5** — Push, notifications, multi-account, incremental sync, CI, F-Droid.
