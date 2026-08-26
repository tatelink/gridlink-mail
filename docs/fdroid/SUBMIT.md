### Gridlink Mail (`app.gridlink`)

A fork of **[Sterna Mail](https://codeberg.org/emon/sterna-mail)** (`app.sterna`, already in
F-Droid) by emon, GPL-3.0. The fork keeps emon's mail engine, sync layer and OpenPGP support and
replaces the front end and the setup flow; it also adds CalDAV calendar and CardDAV contacts in the
same app. The fork notice and attribution are at the top of the README.

- Source: https://github.com/tatelink/gridlink-mail
- Licence: GPL-3.0-only
- Version: 1.0.0 (versionCode 1001), commit `8b166926236b922ea469857547716c1467fca475` (tag `v1.0.0`)

**No proprietary dependencies.** No Play Services, no Firebase, no ML Kit, no analytics. Push is
UnifiedPush. The only tracked binary is `gradle/wrapper/gradle-wrapper.jar`.

**Reproducible build, verified against the published binary.** The APK attached to the GitHub
release rebuilds byte-for-byte from the tagged tree:

```
3ca4b25dc6a9d91f353624e8078073482caeb17076982b9bcac707eb5f418575  gridlink-mail-1.0.0.apk (release asset)
3ca4b25dc6a9d91f353624e8078073482caeb17076982b9bcac707eb5f418575  app-release.apk (clean rebuild from 8b16692)
```

`Binaries` and `AllowedAPKSigningKeys` are now set, so you can publish my signature and users can
move between an F-Droid install and a direct install. Signing cert SHA-256
`17fac1d9740cdcf9fdb1e6857831b2fa9873f0869a6432e30980aaad732dca96` (RSA 4096, v2 scheme, minSdk 26).

Three settings in `app/build.gradle.kts` exist purely to hold reproducibility and are commented as
such, so please do not read them as cruft: `vcsInfo { include = false }` (otherwise the APK carries
`META-INF/version-control-info.textproto`), `dependenciesInfo { includeInApk = false }`, and the
`ArtProfile` task disable (the compiled `assets/dexopt/baseline.prof` is not byte-identical across
build environments even when `classes.dex` is).

**Toolchain note.** The tree is on Kotlin 2.4.10, AGP 8.13 and KSP 2.3. `fdroid build` passed on
your runner for the earlier revision of this MR, so the image copes, but the log carries
`metadata is 2.4.0, expected 2.2.0` lines from the Kotlin stdlib. They are noise: the build exits 0
and the APK is identical to a cache-disabled build.

Fastlane metadata (icon, phone screenshots, changelog, descriptions) is at
`fastlane/metadata/android/en-US/` and is present at the tag.

## Required

* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy)
* [ ] The original app author has been notified (and does not oppose the inclusion)
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request <!-- there are none: no RFP or fdroiddata issue was ever opened for this app, this MR is the first request -->
* [x] Builds with `fdroid build` and all pipelines pass
* [x] There is an issue tracker and contact info of the author so that we can report bugs and contact the author.

## Strongly Recommended

* [x] The upstream app source code repo contains the app metadata in a Fastlane folder structure
* [x] Releases are tagged and auto update is enabled

## Suggested

* [x] External repos are added as git submodules instead of srclibs <!-- n/a: no submodules and no srclibs, every dependency comes from Maven -->
* [x] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds)
* [ ] Multiple apks for native code <!-- n/a: the only native code is androidx.graphics.path and datastore_shared_counter, ~60 KB of .so across all four ABIs in a 6.2 MB universal APK, so a split would save nothing worth the complexity -->
