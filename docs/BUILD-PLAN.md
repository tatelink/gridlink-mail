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
| Tier 2 #9 cache on frame one | to build | **mostly there** — `GridlinkListSkeleton` covers the empty first load and cached mail renders without a network wait. Needs an audit, not a build |
| Tier 2 #13 partial-substring search | to build | **shipped 2026-08-11** |

## Phase 1 — close Tier 0

One item left, and it is a reproduction rather than a change.

1. **Cursor stability in a long quoted reply.** Send itself works (`sendWithUndo` → `sender.check`
   → `enqueueSend`), and attachments now ride along. The unverified half is the review's actual
   complaint: typing above a long quote and having the caret jump or the view scroll away.
   Reproduce on a genuinely long thread before deciding whether anything needs changing. If it
   behaves, Tier 0 closes here with no code written.

## Phase 2 — no foreign screens

✅ **Closed.** Settings and connect were restyled into the Gridlink layer, and the second UI was
retired (`fe6ca5a0`): `ui/message`, `ui/compose`, `ui/connect`, `ui/settings`, `ui/inbox` and
`ui/components/EmailListItem.kt` are gone, along with the parallel bugs and translation load they
carried.

### What that deletion left owing

Neither gap was CAUSED by it. Gridlink's own composer and list were already the only ones running,
so deleting the other UI only made two pre-existing holes impossible to keep ignoring.

- ✅ **Scheduled came back** as `GridlinkScheduledScreen`, a full-screen overlay off the drawer.
- ⛔ **Snooze did not.** `SnoozedDao` is alive, the migration is in, and mail can still be snoozed;
  what is missing is the only screen that ever showed it. So the app can put a message away and
  then offer no way to look at what it is holding. See Phase 3 #3.
- ✅ **The inert settings are all wired**, or removed where they could not be made to mean
  anything. That rule stands: either the feature lands or the switch comes off the screen.

## Phase 3 — Tier 1, the differentiators

2. **Push into subfolders**, not just the inbox. The single most-repeated complaint in the corpus
   about every competitor.
3. **Snooze, its missing screen.** Small next to the rest of this phase and the only item in the
   plan that closes a hole rather than adding a feature: the data layer is already there and
   already working, so this is a list and a route to it.
4. **Contacts and calendar as a real Android account**, so the system's own apps see them.
   Largest item in the plan by some distance: an authenticator, a sync adapter, and provider
   plumbing. Worth its own scoping pass before it starts.
5. **Say the offline-first part out loud.** Already true architecturally (Room + Paging, cached
   attachments) and marketed nowhere. Documentation, not code.

## Phase 4 — Tier 2, the visible polish

✅ Density, the agenda widget and partial-substring search all shipped. One item survives:

6. **Cache-on-frame-one audit.** Confirm no screen waits on the network before drawing what it
   already has; fix what does.

## Phase 5 — FEATURES plan, resumed

Items 1, 2, 3 and 5 shipped 2026-08-09. Item 4 (folder subscribe) is deferred by Tate, and its
drag-to-reorder third is permanently cut.

7. **Read receipts / MDN (item 6).** Carries a decision: recommendation is never auto-send, prompt
   off by default, so the app never confirms you read something without asking.
8. **Calendar conflict detection (item 7).** Unblocked, reads the app's own CalDAV cache, needs no
   Android permission.

## Phase 6 — trust and finish

9. **TalkBack sweep (item 8).** Was waiting on the UI churn of Phases 2 and 4. Both are done, so
   this is now unblocked and will not have to be done twice.
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
