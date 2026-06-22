# Fastlane store metadata

This directory holds the app listing text and images in the standard
[Fastlane Supply](https://docs.fastlane.tools/actions/supply/) layout. It is
read **as-is** by both [IzzyOnDroid](https://android.izzysoft.de/articles/named/izzyondroid-api)
and [F-Droid](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/),
so no separate store copy is needed.

## Layout

```
metadata/android/<locale>/
  title.txt              # app name
  short_description.txt  # one line, <= 80 chars
  full_description.txt   # listing body (plain text)
  changelogs/<code>.txt  # per versionCode; highest matching file is shown
  images/
    icon.png             # 512x512 PNG  (TODO)
    featureGraphic.png   # 1024x500 PNG (optional)
    phoneScreenshots/    # 1.png, 2.png, ...  (TODO)
```

Locales present: `en-US`, `fr-FR`. The changelog file is named after the
`versionCode` in `app/build.gradle.kts` (currently `62` → `changelogs/62.txt`).
Add a new `<code>.txt` for each release.

## TODO before publishing (needs the physical Pixel 7)

Images are not yet committed (binary, captured on-device). Capture on the
Pixel 7 (see project memory `jmail-environment`), light **and** dark, with a
demo account (no real mail):

1. Unified inbox            5. Undo send / schedule send
2. Conversation / thread    6. Privacy settings (remote images blocked)
3. Compose                  7. App lock
4. Multi-select bar         8. Dark mode body

Drop them in `images/phoneScreenshots/` as `1.png`, `2.png`, … and add a
512×512 `images/icon.png` (render from the adaptive launcher icon in
`app/src/main/res/`). Reuse the same screenshots under each locale, or
symlink/duplicate per locale as needed.
