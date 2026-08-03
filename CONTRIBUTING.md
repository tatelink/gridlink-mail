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

## Tests, and what they cannot see

Every test in this repository is a **JVM unit test**. There is **no instrumented test and no
Robolectric**, in any module. Nothing here composes, lays out or renders a composable, and nothing
instantiates an `AndroidViewModel`, a DataStore or a Room database. Read a green suite as exactly
that much.

Two habits follow from it, and both are load-bearing:

- **A decision that matters is extracted as a pure function and RUN by a test** — not described by
  one. A test that recomputes the rule from the same inputs in order to choose what to assert is a
  copy of the rule: invert the shipped condition and it stays green.
- **Where the decision cannot be reached (a composable, a ViewModel), a SOURCE LINT pins the call
  site**: it reads the file as text and checks that the screen calls the tested function with the
  right arguments. Such a lint proves that a call is *written*, never that it *works*. Every one of
  them says so in its own header, and each says what it does not cover.

A source lint must pin **whole arguments and whole calls**, not substrings. A rule looking for
`f(a,` accepts anything after the comma, and that is not theoretical: pinning only the first
argument of one call let its last two be swapped, which put about 72 dp of blank space at the end of
every message with the whole suite green.

**No fix is complete without a test that was seen to FAIL before it and pass after.** If the fix is
a new function, stub it to reproduce the shipped behaviour, watch the test go red, then write it.

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
