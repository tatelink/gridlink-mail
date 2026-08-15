# Build plan

Everything currently on the app's to-do, in the order it should be built, with the evidence for
each claim. Three sources feed this: the competitor review's tiered build order, the nine-item
FEATURES plan, and the standing UI rule that no screen is allowed to look like it came from
another app.

Written 2026-08-11, **restated 2026-08-14** against the tree. Statuses come from reading the code,
not from the review's assumptions, and not from the last version of this file: between the two
dates the whole of Phase 4 shipped, and this document went on listing it as open.

🔴 A status here is a claim about the tree, so it goes stale the moment something lands. Check
before trusting a line, and correct the line rather than working around it.

## Closed since the last restatement

| Item | Where it landed |
| --- | --- |
| Phase 4 #9 density on the live list | `c4374ebc`. `LocalListDensity` is read by `ui/gridlink`, not just the retired upstream list |
| Phase 4 #10 calendar agenda widget | `1748655f`, the third widget. Rows open the Calendar tab (`ee54011c`), and they are drawn from sample data in the debug gallery (`17dcc90e`) |
| Preview lines | `2f1ee82b`. Wired, with the stored default flipped ONE → NONE so no existing install's list changed under it |
| Rich text in the composer | `572f485c` (underline, strikethrough, quotes, headings). Never appeared in this plan at all |
| The "settings that nothing reads" list | signature `ef2a3a1c`, swipe actions `719da57e`, message text size `09ff49a0`, confirm links + strip tracking `58f73766`, contact suggestions `0af8650e`. The theme controls that dressed nothing came off the screen instead (`eea3f291`), which was the other half of the rule |
| Tier 0 #1 composer attachments | shipped end to end: `GridlinkAttacher` stages them, `GridlinkSender` sends them, and `parseShare` → `pendingShareUris` → `GridlinkHomeHost` carries a shared photo into a real draft |
| Casing | `ada01478`. One deliberate exception, documented in `OAuthProvider` |
| detekt | `e9260743`, against a frozen baseline of the 925 pre-existing findings |

## What the code says that the review does not

| Item | Review said | Tree says |
| --- | --- | --- |
| Tier 1 #8 remote images | to build | **shipped** — "Images blocked so UltaHost can't tell you opened this. Show / Always" |
| Tier 2 #11 links to browser | to build | **shipped** — `GridlinkMessageBody.kt` fires `ACTION_VIEW`, zero `CustomTabs` under `ui/gridlink` |
| Tier 2 #12 widgets | to build | **shipped, all three** — unread count, scrolling inbox, calendar agenda |
| Tier 2 #10 density | to build | **shipped** — see above |
| Tier 2 #9 cache on frame one | to build | **audited and closed 2026-08-15**, see Phase 4. It was true; the audit found one silent empty state and fixed it |
| Tier 2 #13 partial-substring search | to build | **shipped 2026-08-11** |

## Phase 1 — close Tier 0

✅ **Closed 2026-08-15, commit `b86c1af7`.** It did not behave, and the reproduction is what said so.

1. ✅ **Cursor stability in a long quoted reply.** Send itself works (`sendWithUndo` → `sender.check`
   → `enqueueSend`), and attachments ride along. The review's actual complaint reproduced on the
   first try against the new `--es compose reply-long` fixture: with the caret at the end of a body
   that already filled the panel, six phrases typed at it went five lines **below the visible edge**
   and the view never moved. "The view scrolls away" was the same fault seen from the other side —
   the view refuses to **follow**, and you type blind.
   `GridlinkFormTextRow` now brings the caret rect into view itself. Every field in this app sits in
   a `verticalScroll` column, where the incoming max height is infinite, so a `BasicTextField` grows
   to its content, never scrolls, and has nothing to keep the caret inside; the enclosing scroll was
   the only thing that could move and nothing told it to.

## Phase 2 — no foreign screens

✅ **Closed.** Settings and connect were restyled into the Gridlink layer, and the second UI was
retired (`fe6ca5a0`): `ui/message`, `ui/compose`, `ui/connect`, `ui/settings`, `ui/inbox` and
`ui/components/EmailListItem.kt` are gone, along with the parallel bugs and translation load they
carried.

### What that deletion left owing

Neither gap was CAUSED by it. Gridlink's own composer and list were already the only ones running,
so deleting the other UI only made two pre-existing holes impossible to keep ignoring.

- ✅ **Scheduled came back** as `GridlinkScheduledScreen`, a full-screen overlay off the drawer.
- ✅ **Snooze came back too**, `e48643d0`: `GridlinkSnoozeScreen`, reached from the drawer beside
  Scheduled, because they are the same kind of thing (mail waiting on a clock). Phase 3 #3 is
  therefore closed; this file went on listing it as open for a day.
- ✅ **The inert settings are all wired**, or removed where they could not be made to mean
  anything. That rule stands: either the feature lands or the switch comes off the screen.

## Phase 3 — Tier 1, the differentiators

2. ✅ **Push into subfolders**, not just the inbox. The single most-repeated complaint in the corpus
   about every competitor, and the tree said the engine had been built and left unreachable: the
   push layer has read `AccountStore.watchedFolders` since issue #16, and the only things that ever
   WROTE that set were cleanup paths (a folder deleted server-side unwatches itself, a rename
   re-keys it). Nothing in the app could add a folder, so on every install the set was empty and
   only the inbox ever notified, whatever Sieve did with the mail.
   Now a **Notify me here** switch in the folder long-press sheet, and a bell on the row so the
   state is visible without opening the sheet. `GridlinkFolder.watched` carries it, and
   `GridlinkFolderEdit.Watch` is deliberately in the same sealed set as rename/delete despite
   touching nothing on the server, because the sheet is where a user goes to say something about a
   folder.
   Two rules came out of building it, both stated in the code: **watching is not editing**, so a
   mailbox the server has locked down (`myRights`, a role, the roleless Archive) still offers the
   switch, which is why `mayEdit` split off `hasActions`; and the **inbox has no switch**, because
   OFF there would be a lie and ON a preference nothing reads.
   ⚠️ Honest about latency rather than quiet about it: JMAP's `StateChange` covers the whole
   account so a watched folder is as live as the inbox, but IMAP IDLE selects one mailbox and this
   app selects the INBOX, so on IMAP a watched folder rides `MailFetchWorker`'s ~30-minute cycle.
   The switch's subline says so on IMAP accounts (`GridlinkFolderContent.watchIsInstant`).
   Turning a watch ON never floods the shade: `seedsSilently` takes a first-seen folder's contents
   into the baseline without announcing them.
   ⛔ **Decided 2026-08-15 (Brandon): IMAP stays on the 30-minute poll.** A second IDLE connection
   doubles the sockets held per account against servers that cap them, round-robin re-selecting
   breaks IDLE on every hop and puts gaps in the inbox itself, and a tighter poll costs battery on a
   schedule for a case that is not his own mail. The subline is the answer: say the latency rather
   than engineer around it. Do not re-propose.
3. ✅ **Snooze, its missing screen.** Closed by `e48643d0`, see Phase 2.
4. ✅ **Contacts and calendar as a real Android account.** Done 2026-08-15. An `AccountManager`
   account per mail login (`GridlinkSystemAccount`), a credential-free authenticator
   (`GridlinkAuthenticator`, which issues no tokens on purpose), and two sync adapters that fetch
   over CardDAV/CalDAV and then publish the Room cache into `ContactsContract` and
   `CalendarContract`. Off by default, one switch in Settings → Privacy and security, which is also
   where the four contacts/calendar permissions are asked for: a sync adapter has no Activity to
   attach a prompt to, so it can only check.
   ⛔ **Decided 2026-08-15 (Brandon): read-only, both providers.** Rows are written with
   `CAL_ACCESS_READ` / `RAW_CONTACT_IS_READ_ONLY`, so an edit made in the system Contacts app is not
   pushed back to the server. Two-way would need conflict handling against a DAV server the app does
   not own, for a case DAVx5 already covers.
   🔴 The account IS the anchor: both providers delete rows whose account is not registered, so
   turning the switch off removes the account and the rows go with it. There is deliberately no
   per-account switch; `SyncSelection` already answers that question.
   The conversion rules live alone in `CalendarMirrorTimes` (all-day is midnight **UTC**; a
   recurring row carries `RRULE` + `DURATION` and a **null** `DTEND`; an `EXDATE` on a timed event
   must match the occurrence to the second; a detached override's `ORIGINAL_INSTANCE_TIME` comes
   from the **master**, not from its own moved start) and are unit-tested.
5. ✅ **Say the offline-first part out loud.** Closed 2026-08-15, documentation only, as planned.
   A "Works with no signal" section in the README that states the actual architecture in the user's
   terms (every screen reads the cache and only the cache; the network writes into it and draws
   nothing), and the four consequences that follow: opens on mail rather than a spinner, a refresh
   never blanks the screen, messages open with no round trip, calendar and contacts the same. The
   one limit is stated too, because a claim with no edge is not believed: mail that arrived while
   you were offline is mail the phone has never seen.
   `FEATURES.md`'s one-line "Offline reading (Room cache)" was the whole of it before, which sold a
   cache rather than a guarantee.

## Phase 4 — Tier 2, the visible polish

✅ **Closed 2026-08-15.** Density, the agenda widget and partial-substring search shipped earlier;
the audit is now done too.

6. ✅ **Cache-on-frame-one audit.** The claim holds. Every list screen reads Room and only Room, and
   each one carries a `primed` flag that latches true on the **first** emission and is reset only by
   an account switch, so a refresh over drawn content never blanks back to a skeleton:
   `GridlinkMailViewModel.primed` for mail, `folderPrimed` for the tree, `calendarPrimed` and
   `contactsPrimed` in `GridlinkDavViewModel`. Every one of those flows opens on `loading = true`
   rather than an empty list, which is what stops the first frame flashing "Inbox zero" at somebody
   with four hundred messages, and the skeleton is tested **before** the empty state so "no mail" is
   only ever claimed once something has actually answered. `MailRepository.openMessage` returns a
   cached body with no network round trip and marks it read out of band.
   Proven live, not just read: airplane mode on, force-stop, cold launch. The inbox drew real cached
   mail with an Offline chip, and Calendar and Contacts drew their cached state, with no network
   available at any point.
   One defect found and fixed, in what a screen says when the answer is genuinely nothing:
   **Contacts had no empty state at all**, so a signed-in account with an empty book got a bare panel
   with a dead alphabet rail down the side, which reads as broken rather than as empty. Now
   `GridlinkEmptyContacts`, whose affordance creates a contact rather than offering a sync, because
   contacts do not arrive the way mail does. Gated on `!contactsLoading` so the first frame after a
   cold launch cannot claim "No contacts yet" about a book nothing has read.

## Phase 5 — FEATURES plan, resumed

Items 1, 2, 3 and 5 shipped 2026-08-09. Item 4 (folder subscribe) is deferred by Brandon, and its
drag-to-reorder third is permanently cut.

7. ✅ **Read receipts / MDN (item 6).** Closed 2026-08-15. Brandon's decision: **ask, off by
   default**. A message that carries `Disposition-Notification-To` draws one quiet line saying who
   asked, with a Send receipt button. Nothing is ever sent unless that button is tapped, and there
   is **no setting that turns auto-send on**, so the setting cannot be flipped by accident or by
   somebody else holding the phone. Reading a message is not consent to tell the sender you read it.
   Surfaced on both protocols: a JMAP header property (`header:Disposition-Notification-To:asText`,
   also added to `EMAIL_BODY_PROPERTIES`) and, on IMAP, lifted from the raw source `openEmailImap`
   already has in hand.
   The rules live in `core/data/.../mail/Mdn.kt` and are unit-tested. 🔴 **Only the first address**
   in the header is used: a header naming three parties is asking one tap to tell three people, and
   nobody pressing a button labelled "Send receipt" agreed to that. 🔴 CR/LF in either the address
   or the message id is **refused**, not stripped-and-sent: both values come off a stranger's
   message and end up in mail this app sends, so a surviving newline would let the sender write
   headers of their choosing. A header with nothing usable in it draws no button at all, rather than
   a button that fails at send time. The disposition is always
   `manual-action/MDN-sent-manually; displayed`, which is the format's own way of saying a human did
   this; the app cannot produce any other value. Never offered on drafts or on the user's own mail.
   One deliberate deviation, documented at the call site: the receipt goes out as `multipart/mixed`
   with the notification as a part, following the calendar-reply precedent, rather than a strict
   `multipart/report` that would mean rewriting the send pipeline.
8. ✅ **Calendar conflict detection (item 7).** Closed 2026-08-15, on the back of the rebuilt
   invitation card. Opening a meeting request now reads the app's own CalDAV cache for that day and
   names what is already booked over it, above the RSVP row so it is read before the thumb reaches
   Accept, in caution amber rather than destructive red — a clash is something to weigh, and plenty
   of them are deliberate.
   🔴 The app's OWN cache, not the system calendar: reading the provider would need a runtime
   calendar permission for one line of text, and it works with no signal. Same reason Add to
   calendar hands an intent to the calendar's own editor instead of writing through the provider.
   The rules live in `gridlinkInviteConflicts` and are unit-tested: overlap is **half-open**, so
   back-to-back meetings do not clash (the case that decides whether the feature is usable at all);
   all-day entries are ignored on both sides, since "Alice on leave" spans a day without occupying
   it; the invitation never clashes with **itself** (matched by UID, for one already accepted or
   re-sent); a zero-length span counts only where it touches, rather than being widened to an
   invented default. Past three clashes the rest are counted rather than dropped.

## Phase 6 — trust and finish

9. ⏸️ **TalkBack sweep (item 8).** Started 2026-08-15 and **paused by Brandon**, part done. The
   audit found the app in better shape than expected: icon-only controls are labelled, decorative
   icons are correctly `contentDescription = null` beside real text, and message rows already merge
   into one node through `combinedClickable`, with the accent bar carrying the account name aloud.
   Three real gaps found and fixed in that pass:
   - 🔴 **Swipe actions were unreachable.** TalkBack owns the horizontal swipe, so with the screen
     reader on there was no way to archive, delete, snooze or mark from the list. Not a worse way,
     none. `GridlinkSwipeRow` now publishes its resolved slots as accessibility custom actions.
   - **The alphabet rail** is hidden from the reader outright: it is a press-and-drag scrub TalkBack
     cannot operate, and 26 `Text` letters otherwise sit as dead stops between the list and
     everything after it. It duplicates navigation the list already offers, so nothing is lost.
   - `role = Role.Button` on the three full-panel empty states, whose affordance was carried only by
     "it is the only object on screen", which is a layout argument a screen reader never hears.

   ⚠️ **Not verified live.** The custom actions are believed-correct from the code, not seen in
   TalkBack's actions menu. Do that on the Z Fold. What remains after that: a walk of the reading
   pane, composer and settings, and a decision about the scrub-driven pickers elsewhere.
10. **S/MIME (item 9).** Last, with a key-custody decision to make: recommendation is verify-only
    first.
11. **Tier 3, which is policy rather than code.** Don't break gestures people already have in their
    fingers; a public tracker with visible responses; no client-side feature ever behind a paid
    unlock. Already how this app is being built, so the work is writing them down where a user can
    read them.
12. **The 49 Dependabot advisories.** Triage, not a blanket bump.

## Outside the app, still open

- The **Entra app registration's display name** still reads "GridLink Mail" in the Azure console,
  so Microsoft's device-code sign-in page shows the old casing. A console change, not a code one.
  🔴 The display name only: the `clientId` is what every stored Outlook refresh token was issued
  to, and changing it signs every Outlook account out.

## Notes that outlive this plan

- Room schema is at **22**. Anything needing a migration goes 22 → 23.
- `TranslationParityTest` gates every new `<string>` across nine locales. `ui/gridlink` uses
  hardcoded English and is exempt.
- detekt runs against a **baseline**. Regenerating one forgives whatever is currently broken;
  never run `detektBaseline` to make a red build green.
- CI before every commit: `:core:jmap:test :core:imap:test :core:dav:test
  :core:data:testDebugUnitTest :app:testDebugUnitTest` then `:app:assembleDebug`.
- The debug gallery is the place to LOOK at something: `GridlinkGalleryActivity` for the screens,
  `GridlinkWidgetGalleryActivity` for the home-screen widgets. Both draw sample data, so neither
  needs a real mailbox and neither can put one in a screenshot.
