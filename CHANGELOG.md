# Changelog

All notable changes to Gridlink Mail are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Gridlink is a fork of [Sterna Mail](https://codeberg.org/emon/sterna-mail) by emon. This file
records what **this fork** changed. It is also how the project satisfies GPLv3 section 5(a),
which requires a modified work to carry prominent notices stating that it was changed and the
dates of those changes. Upstream's own history is preserved in the git log and is not repeated
here.

Fork point: upstream Sterna Mail `main` at v1.4.6.

## [Unreleased]

No release of this fork has been published yet. Everything below has landed on the working
branch between 2026-08-01 and 2026-08-08 (73 commits).

### Added

**A new front end (the bulk of the fork).** Gridlink replaces Sterna's message list, reading
view, composer and navigation with a purpose-built Compose UI: an aurora backdrop with a glass
list panel, a three-mode display ladder, a nav pill, and a design-token set driving the whole
surface. The Outfit typeface is used throughout.

- Swipe actions on every message row, rebuilt three times to fix dropped pointer deltas,
  cancelled commits, and rows that animated but never left the list. Dismissal cut from 450ms
  to 200ms.
- Selection mode: a slide-open list, a selection action bar, and Back clearing a selection
  rather than leaving the app.
- Compose window with a detached compose button, schedule sheet, and an undo-send window with
  a countdown ring.
- Message view with a scrubbable back gesture, real thread actions behind a More sheet, and
  real HTML body rendering with remote images blocked by default.
- Folder tree with inline folder creation, long-press rename/delete, and folders that open onto
  the mail inside them.
- Contacts tab: full viewer/editor with photos, custom fields, grouped vCard properties, ADR
  parsing, a First/Last sort toggle, and row fallbacks to email then phone.
- Calendar: split month view (grid two thirds, the day's appointments one third), read-only
  event cards, full event editing, paging swipes, and a continuous agenda window.
- Unfolded two-pane layout for large screens, with contact view/edit/create in the right pane.
- Pull to refresh, a loading skeleton, and an empty-inbox tap-to-refresh mark.
- Setup screen as the first launch, naming JMAP alongside IMAP and Outlook.
- An animated intro on cold launch that builds the Gridlink mark, played once and only after
  the system splash is gone.
- Side-by-side test app: build any variant with `-PtestApp` for `app.gridlink.test`, which
  installs next to the production app with its own data, launcher entry and greyscale icon.

### Changed

- **Renamed from Sterna to Gridlink, application ID included.** `applicationId` and `namespace`
  are both `app.gridlink`. The Sterna tern launcher icon is replaced with the Gridlink mark.
- Server writes are real, not mocked: send, CalDAV event creation, and contact writes to JMAP
  with CardDAV fallback. Synced events are edited in place, preserving every byte the form does
  not touch.
- Folders, Calendar and Contacts are wired to the live JMAP mailbox tree and CalDAV/CardDAV
  rather than sample data.
- A rejected credential is named instead of quoting the raw HTTP status code.
- Header collapsed onto a single chrome line so both panes stand taller.
- Reproducible-build hardening for F-Droid: the compiled ART baseline profile is not packaged,
  and no Google dependency-metadata blob is emitted into the APK or bundle.

### Fixed

- `gridlinkGlow` cropping the light to its element's rectangle.
- A crash when entering selection mode.
- The composer opening onto the schedule sheet.
- Taps falling through the thread view into the list underneath.
- The reading pane still showing a message after that message was filed.
- The sync chip never clearing on the signed-in home.
- Images never loading after choosing "always allow".
- Tapping a message inside a folder doing nothing.
- The keyboard not raising on text fields, and Back not dismissing it.
- The setup screen's keyboard stealing focus before the intro finished.

### Inherited from upstream, unchanged

The JMAP and IMAP/SMTP mail engine, the sync layer, OpenPGP support and most of the settings
screens are emon's work and are used under the GPLv3. Bugs in `core/` or the upstream screens
should be reported to upstream first (see `SECURITY.md`).

---

## Versioning

Gridlink numbers its own releases and does not continue Sterna's line.

The fork previously shipped upstream's `versionName = "1.4.6"` / `versionCode = 166`, which are
Sterna's numbers for a different application id. That is now `0.1.0` / `1000`.

`versionCode` starts at 1000 rather than 1 deliberately. It has to stay strictly above upstream's
166, because the production build is tracked by Obtainium and Android refuses to install a lower
version code over a higher one; restarting at 1 would have stranded the existing install with no
upgrade path. The round number leaves room to renumber without colliding.
