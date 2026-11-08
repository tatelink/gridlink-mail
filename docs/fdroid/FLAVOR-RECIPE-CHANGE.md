# fdroiddata recipe change for the store flavors (proposed, NOT submitted)

Written 2026-11-07 on branch `play-flavor`. Nothing here has been sent to fdroiddata.

## Why the recipe has to change

From the first release built with the `store` flavor dimension (`fdroid` and `play`), the
current recipe stops working:

- Every existing build entry says `gradle: - yes`. fdroidserver turns that into
  `gradle assembleRelease`, which now builds BOTH flavors.
- It then looks for the APK in `app/build/outputs/apk/release/`. With flavors the APKs land in
  `app/build/outputs/apk/fdroid/release/` and `app/build/outputs/apk/play/release/`, so the build
  fails with "Failed to find any output apks" (fdroidserver `build.py`, the `gradle` output
  branch). It fails loudly rather than shipping the wrong APK, but it fails.
- With `gradle: - fdroid` fdroidserver runs `assembleFdroidRelease` only, picks the single APK in
  `outputs/apk/fdroid/release/`, and its scanner only reads the compile lines for the `fdroid`
  flavor plus unflavored ones. Billing is declared as `"playImplementation"(libs.billing.ktx)`, a
  line no `fdroid` regex matches, and the catalog alias is only resolved from matching lines, so
  Play Billing and the Play services it pulls are never seen, downloaded or built.

## The exact diff

Against `metadata/app.gridlink.yml` as it stands on fdroiddata master on 2026-11-07 (last entry
2.1.2, versionCode 1005). `X.Y.Z`, `NNNN` and the commit are the first flavored release's, known
only once it is tagged. Past entries are left alone: they were built from pre-flavor trees, where
`yes` is still correct.

```diff
@@ Builds:
   - versionName: 2.1.2
     versionCode: 1005
     commit: 495ff5f7f71984856d400f5dd9b58da29bc57d7b
     subdir: app
     gradle:
       - yes
 
+  - versionName: X.Y.Z
+    versionCode: NNNN
+    commit: <full sha of the vX.Y.Z tag commit>
+    subdir: app
+    gradle:
+      - fdroid
+
 AllowedAPKSigningKeys: 17fac1d9740cdcf9fdb1e6857831b2fa9873f0869a6432e30980aaad732dca96
 
 AutoUpdateMode: Version
 UpdateCheckMode: Tags
-CurrentVersion: 2.1.2
-CurrentVersionCode: 1005
+CurrentVersion: X.Y.Z
+CurrentVersionCode: NNNN
```

Nothing else changes: `Binaries`, `AllowedAPKSigningKeys`, `AutoUpdateMode` and `UpdateCheckMode`
stay exactly as they are. `AutoUpdateMode: Version` copies the LAST build entry for each new tag,
so once this entry exists every later release inherits `gradle: - fdroid` with no further MRs.

Formatting traps (same as every earlier MR): LF line endings, two-space indent, a blank line
between build entries, field order as above, and run `fdroid rewritemeta app.gridlink` plus
`fdroid lint app.gridlink` before pushing the MR branch.

## Timing

- The MR can only be filed once the first flavored tag exists (it needs the commit).
- File it the same day as the tag. If the checkupdates bot gets there first it copies the 2.1.2
  entry with `gradle: - yes`, that build fails as described above, and the MR then edits that
  bot-added entry's `gradle:` to `- fdroid` instead of adding a new one. Nothing wrong ships
  either way; the release is just late on F-Droid until the MR merges.

## Our side of the reproducible build (must change with the first flavored release)

F-Droid rebuilds the `fdroid` flavor and compares it with our signed APK at the `Binaries` URL
(`gridlink-mail-%v.apk` on the GitHub release). That uploaded APK must therefore be the FDROID
flavor's release output, never the Play build:

- Old path: `app/build/outputs/apk/release/app-release.apk`
- New path: `app/build/outputs/apk/fdroid/release/app-fdroid-release.apk`
- Build it the same way as before, from a clean tree: `clean :app:assembleFdroidRelease --no-build-cache`.

Any release script or checklist that copies the old path needs updating before that release.
The Play build is uploaded to Play Console only, as an AAB from `bundlePlayRelease`, and never to
GitHub releases.
