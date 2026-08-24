# Changelog

All notable changes to Gridlink Mail are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Gridlink is a fork of [Sterna Mail](https://codeberg.org/emon/sterna-mail) by emon. This file
records what **this fork** changed. It is also how the project satisfies GPLv3 section 5(a),
which requires a modified work to carry prominent notices stating that it was changed and the
dates of those changes. Upstream's own history is preserved in the git log and is not repeated
here.

Fork point: upstream Sterna Mail `main` at v1.4.6, commit `7e908319`.

The SHA is written out because the `v1.4.6` tag is Sterna's, not this fork's. Upstream's
release tags were inherited by the fork's remote and have been removed from it, so that the
repository advertises Gridlink's own releases and not another project's. Every commit they
pointed at is still in the history; only the labels are gone, and upstream keeps its own.

## [1.0.0] - 2026-08-23

Feature complete. The version says so.

0.1.0 was published while the front end was still being argued about, and the six days since are
that argument settled: every screen the fork drew has now been used, criticised and rebuilt, the
audit's test gap is closed, and there is nothing queued. Development continues, but not toward
this shape.

🔴 This does **not** supersede the `v0.1.0` tag for F-Droid. That tag is what merge request
!46061 builds and what its reproducible build was verified against, and it is deliberately left
where it is. The recipe checks tags, so `v1.0.0` is picked up on its own once the request merges.

### Added

- **Marking read is the reader's choice.** A Reading setting decides whether opening a message
  marks it read; with it off, only the swipe and the toolbar button do. The swipe, the button and
  the selection action all keep working either way.
- **Filter rules can ask about more than one thing.** A rule now takes several conditions and
  several actions instead of one of each, with an any/all choice between them.
- **Mail tags are read from the server.** Settings asks the server what keywords it actually
  keeps, so a tag made in another client is offered here by name instead of never appearing.
  IMAP answers from `PERMANENTFLAGS`; JMAP has no such method, so it sweeps the mail and says
  when the sweep was cut short rather than reporting a short list as the whole truth.
- **Adopt every unnamed tag at once**, instead of one row at a time.
- **Delete a contact from the edit form**, which previously had no way out but Back.
- **Allowed senders (remote images) live on their own screen**, so a list that grows for years
  stops burying the settings under it.
- **The Accounts row in the menu lands on the accounts list**, not on the settings page the
  reader was already looking at.

### Changed

- **Off switches are drawn in ink.** An off toggle used to render at the same weight as a
  disabled one, so a setting the reader had turned off read as one they were not allowed to
  touch.
- **A contact reads as facts, not as a form.** The viewer no longer draws field boxes around
  values nobody can type into.
- **The panel spends less of itself on saying nothing.** Borders stay, the padding inside them
  goes, which is most of a line of mail back on every screen.
- **The tip jar is drawn in the app's own language**, having been the one card still wearing
  stock Material.
- **Scrolling the agenda moves the month grid's selection with it**, and tapping a day takes the
  reading pane back.
- **A form keeps its pane when the keyboard is up.** Unfolded, the keyboard used to leave the
  event and contact forms an inch tall.
- **Attachments open and save against the message's own account**, and retry once when the
  server has moved the blob.
- **A missing body says why** instead of drawing a blank page.
- **Photos in the formats phones actually shoot** are read, and the ones that cannot be read say
  so.
- **The first launch asks for the account before the server**, and calendar and contact sync
  start switched on, as that screen had always claimed.
- **Notifications are asked for once**, and only once there is mail to notify about; when
  Android is blocking them, the app says so.

### Fixed

- The typed mail password no longer travels in the saved-state `Bundle`.
- A disabled button stays in the accessibility tree.
- Push reads its delivery mode off a warm mirror instead of blocking the main thread.
- The stale toolbar selection, the squeezed Connect pill, the contacts sort landing off the top,
  and the tag editor that would not take focus on its first layout.
- System bars are contrasted against the surface actually painted behind them.

### Internal

- **The audit's test gap is closed**: around 700 new tests across fifteen batches, every screen
  and view model driven on the JVM under Robolectric against the real stores. 2447 tests.
- `tools/testmail`, a seeder for a disposable test mailbox, so live testing stops needing a real
  one.
- The sample corpus is re-themed off the mailbox it came from, and the store screenshots are
  re-shot on it.

## [0.1.0] - 2026-08-17

The first published release of this fork, covering everything written between 2026-08-01 and
2026-08-17.

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

### Added, continued (2026-08-09 to 2026-08-17)

**Mail.**

- Drafts that resume where they were left, a Scheduled screen, and a draft that saves itself.
- Snooze: put a message away, with a place to see what is away.
- A unified inbox that merges every account, with per-account colour coding, and a refresh that
  syncs all of them.
- Quick filters (unread, starred, has attachment), starring, and custom tags: cached keywords,
  colour definitions, chips on the message, dots on the row, a filter chip, and a tag manager.
- Search that indexes the preview, finds partial words, and highlights the match.
- Rich text in the composer: bold, italic, underline, strikethrough, quotes, headings, lists and
  links, from a toolbar above the keyboard.
- Attachments that actually attach: a size cap on outgoing mail, a draft's attachments restored
  when it is reopened, a forward's attachments staged so it can be sent, and Save to Downloads
  without handing the screen to another app.
- Replies and forwards that carry the original.
- One-tap unsubscribe read from the header, and folder rights read from the server.
- Reparent a folder by dragging it onto another (IMAP RENAME).
- Printing.
- `mailto:` links and notification taps route into the Gridlink screens.
- Server autodiscovery over SRV, so a domain can say where its mail lives.
- CONDSTORE: skip folders the server says are untouched.
- Push into subfolders, which the engine never had.
- Read receipts ask before telling a sender you opened their mail.
- S/MIME: the signature row says who really signed it, and the detached signature part is hidden
  from the attachment list.

**Calendar and contacts.**

- JMAP calendars and contacts, read and written natively when the server speaks it, with CalDAV
  and CardDAV as the fallback. The sync algorithm lives in its own `JmapCollectionSync`.
- RFC 8607 managed attachments: attach, open and remove files on an event.
- Meeting invitation cards that say how an event repeats and what it clashes with.
- Repeating events editable with "this event or all of them".
- Calendars and contacts published into Android as a real system account, with a warning before
  the mirror doubles up on DAVx5.
- Month grid with a shaded today and a heat map, two-pane unfolded layouts for calendar and
  contacts, and a scrolling agenda.

**Home screen.**

- Three widgets: recent inbox, unread count, and calendar agenda. Agenda rows open the Calendar
  tab.

**Settings and appearance.**

- Settings and Add account moved inside the Gridlink design layer; the add-account picker is one
  glass list with real logos.
- Configurable swipe actions in all three slots, a live list-density setting, a preview-lines
  setting, a working message text size, a composer signature, real link-privacy switches, a
  palette pin that survives a restart, Auto resolved from the real sun, quiet hours on the phone
  clock, and a launcher-icon setting.

**Engineering.**

- detekt across every Kotlin module, with the full test suite and a dependency scan in CI.
- Dependabot, and the dependency bumps it raised: Kotlin 2.4, AGP 8.13, KSP 2.3, Room 2.8,
  BouncyCastle 1.84, UnifiedPush 3.3.3.
- `-PphoneKey`, so a minified build can be installed over a debug one for testing.

### Changed, continued

- The name is written "Gridlink" everywhere, including the Entra registration and the Outlook
  sign-in screen.
- The second, inherited UI is gone; the mailbox list moved into the drawer.
- Sign-in never hangs, names what went wrong, has its own login field, and stops hammering a
  server that already said no.
- Every message action works in every folder, not just the inbox.
- Archive treats the archive folder as special rather than as an ordinary folder.
- Yandex and Mail.ru presets dropped.
- Sample data has to be asked for and is never defaulted into.
- One field shape across every form, and one shape for pick-one settings.
- The promises screen was deleted rather than left saying things the app does not do.

### Fixed, continued

- Mail the cache lost is put back, and no longer lost.
- Pull-to-refresh no longer reports success it did not have.
- The caret stays on screen while typing in a long form.
- Swipe actions are reachable with a screen reader.
- Two maximize defects found on the Z Fold, and the maximized message keeps the app's own edges.
- The calendar view switcher stays still when the subview changes, opening an event to edit no
  longer raises the keyboard, and the reminder sheet has a way out that is not the scrim.
- The undismissable sync notification now opens the app.
- About's dead links.

### Inherited from upstream, unchanged

The JMAP and IMAP/SMTP mail engine, the sync layer, OpenPGP support and most of the settings
screens are emon's work and are used under the GPLv3. Bugs in `core/` or the upstream screens
should be reported to upstream first (see `SECURITY.md`).

---

## Versioning

Gridlink numbers its own releases and does not continue Sterna's line.

The fork previously shipped upstream's `versionName = "1.4.6"` / `versionCode = 166`, which are
Sterna's numbers for a different application id. That became `0.1.0` / `1000`, and is now
`1.0.0` / `1001`.

1.0.0 is a decision rather than an arrival. The feature set was declared finished on 2026-08-23,
so the number says finished; it does not claim the code is older or more exercised than it is.

`versionCode` starts at 1000 rather than 1 deliberately. It has to stay strictly above upstream's
166, because the production build is tracked by Obtainium and Android refuses to install a lower
version code over a higher one; restarting at 1 would have stranded the existing install with no
upgrade path. The round number leaves room to renumber without colliding.
