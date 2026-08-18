# F-Droid submission

## Where it goes

Two routes, and the second is faster if it is accepted:

1. **Request For Packaging**: open an issue at <https://gitlab.com/fdroid/rfp/-/issues/new>
   using the "Request For Packaging" template, and wait for a volunteer to package it.
2. **Merge request**: add `metadata/app.gridlink.yml` (the file beside this one) to
   <https://gitlab.com/fdroid/fdroiddata> and open a merge request. This is packaging it
   yourself rather than asking, and it is the route to prefer when the recipe is already
   known to build.

Both need a GitLab account. There is no API-key path that avoids one.

## Inclusion criteria, checked against this tree

| Requirement | Status |
|---|---|
| Free software licence | GPL-3.0-only, `LICENSE` at the root |
| Source publicly available | <https://github.com/tatelink/gridlink-mail>, public |
| No proprietary dependencies | No Play Services, no Firebase, no ML Kit. Push is UnifiedPush |
| No prebuilt binaries in the repo | Only `gradle/wrapper/gradle-wrapper.jar`, which F-Droid allows |
| Builds from source | `./gradlew :app:assembleRelease` |
| A tagged release | `v0.1.0`, versionCode 1000 |
| Fastlane metadata | `fastlane/metadata/android/en-US/`, with icon, four screenshots and a changelog |

## Reproducible builds

Two clean `assembleRelease` runs from the `v0.1.0` tree produce **byte-identical** APKs:

    sha256  2b39fd579223e6f7f0e184536e2bc305c7fe1c5fc34595da2f8604e51c970e89

This matters because it is what lets F-Droid ship the developer's own signature rather than
re-signing with theirs, so an F-Droid install and a direct install are the same artefact and
can replace each other. Two things in `app/build.gradle.kts` make it hold, and both must stay:

- `vcsInfo { include = false }` in the release build type, so `META-INF/version-control-info.textproto`
  is not embedded. Its content depends on whether AGP can read git in the build environment, and
  F-Droid's rebuild of upstream 1.1.3 differed in exactly that file (`NO_VALID_GIT_FOUND` against an
  embedded revision). There is a comment on the `signingConfig` line beside it warning that F-Droid's
  reproducible-build signing strip is line-based, so that expression must stay on ONE line.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }`, so no Google
  dependency-metadata blob is written.

To offer the developer signature, add to the metadata once F-Droid has built it and matched:

    AllowedAPKSigningKeys: 17fac1d9740cdcf9fdb1e6857831b2fa9873f0869a6432e30980aaad732dca96

## Known risk on their builder

The build emits `e:` lines reading "Module was compiled with an incompatible version of
Kotlin. The binary version of its metadata is 2.4.0, expected version is 2.2.0" for the core
modules and kotlin-stdlib. It exits 0 here, but this tree is on Kotlin 2.4 / AGP 8.13 / KSP
2.3, which is newer than F-Droid's build image usually carries. If their build fails, this is
the first thing to look at.
