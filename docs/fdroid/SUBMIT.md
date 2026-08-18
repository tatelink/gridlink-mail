# Filing the submission

Everything below is ready to use. The only step that is not automatable is the account, because
account creation and password entry are done by a human by policy, not by tooling.

## Step 1, the account (yours to do)

1. Go to <https://gitlab.com/users/sign_up>.
2. Use **hello@eightpointlabs.com**, not a personal address. It is the address
   `SECURITY.md` points at, so a packager who replies reaches the project rather
   than a person, and it can be filtered or retired without touching anyone's mail.
3. Suggested username: `tatelink`, to match the GitHub org. Take whatever is free.
4. GitLab sends a confirmation mail and may show a captcha. Both need a human.

## Step 2, a token (yours to do, one click)

<https://gitlab.com/-/user_settings/personal_access_tokens>, create a token with scope **`api`**.
That single scope covers forking, pushing and opening a merge request.

Store it beside the other credentials rather than pasting it around.

## Step 3, the merge request (automatable once the token exists)

Prefer this over the RFP issue. An RFP asks a volunteer to package the app and then waits; a merge
request is packaging it, and the recipe in `app.gridlink.yml` is already known to build here.

The mechanical steps, all `api`-scope calls:

1. Fork <https://gitlab.com/fdroid/fdroiddata>.
2. Add `metadata/app.gridlink.yml` (copy of the file beside this one) on a branch.
3. Open a merge request against `master` with the description below.

## Merge request description, paste as-is

> ### Gridlink Mail (`app.gridlink`)
>
> A fork of Thunderbird for Android / K-9 Mail, adding JMAP support alongside IMAP, plus CalDAV
> calendar and CardDAV contacts in the same app.
>
> - Source: https://github.com/tatelink/gridlink-mail
> - Licence: GPL-3.0-only
> - Version: 0.1.0 (versionCode 1000), tagged `v0.1.0`
>
> **No proprietary dependencies.** No Play Services, no Firebase, no ML Kit, no analytics. Push is
> UnifiedPush.
>
> **Reproducible.** Two clean `./gradlew :app:assembleRelease` runs from the `v0.1.0` tree produce
> byte-identical APKs, `sha256 2b39fd579223e6f7f0e184536e2bc305c7fe1c5fc34595da2f8604e51c970e89`.
> Happy to add `AllowedAPKSigningKeys` once you have built it and matched, so an F-Droid install and
> a direct install are interchangeable.
>
> **One thing to flag before you build it.** The tree is on Kotlin 2.4.10, AGP 8.13 and KSP 2.3,
> which is likely newer than the build image. It compiles and exits 0 here, but emits
> `Module was compiled with an incompatible version of Kotlin ... metadata is 2.4.0, expected 2.2.0`
> for the internal modules and kotlin-stdlib. If the build fails on your side, that is the first
> place to look, and I will pin versions to whatever the image carries.
>
> Fastlane metadata (icon, four phone screenshots, changelog) is at
> `fastlane/metadata/android/en-US/`.

## Fallback, the RFP issue

If the merge request is turned away, <https://gitlab.com/fdroid/rfp/-/issues/new> with the
"Request For Packaging" template. Same description works.
