# Contributing to Sterna

Thanks for your interest. Sterna is in early development; expect rapid change.

## Requirements

- JDK 17
- Android SDK (compileSdk 36, build-tools 36.0.0)
- An Android device or emulator running Android 8.0 (API 26) or newer

The Gradle wrapper (`./gradlew`) pins the Gradle version, so you don't need
Gradle installed.

## Build

```sh
# Run unit tests for the JMAP layer
./gradlew :core:jmap:test

# Build a debug APK
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Install on a connected device/emulator
./gradlew :app:installDebug
```

## Behind a proxy

Put proxy settings in `~/.gradle/gradle.properties` (not in the repo):

```properties
systemProp.https.proxyHost=HOST
systemProp.https.proxyPort=PORT
systemProp.https.proxyUser=USER
systemProp.https.proxyPassword=PASS
systemProp.jdk.http.auth.tunneling.disabledSchemes=
```

## Project layout

See [ARCHITECTURE.md](ARCHITECTURE.md). In short:

- `:app` — Compose UI and app wiring.
- `:core:jmap` — pure-Kotlin JMAP protocol client (unit-tested without Android).

## Conventions

- Kotlin official code style (`kotlin.code.style=official`).
- Keep `:core:jmap` free of Android dependencies so it stays testable on the JVM.
- **FOSS dependencies only.** Every library must be free/open-source
  (OSI/FSF-approved, GPLv3-compatible). No proprietary or closed-source libraries,
  no Google Play Services / Firebase / GMS, no analytics or tracking SDKs, and
  nothing that requires a non-free service to function. This keeps Sterna
  F-Droid-eligible (build-from-source, no anti-features) and Google-free.
- No telemetry, ever. Don't add tracking or analytics.

## License

By contributing, you agree your contributions are licensed under the GPLv3.
