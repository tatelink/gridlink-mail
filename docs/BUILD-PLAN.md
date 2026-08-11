# Build plan

Everything currently on the app's to-do, in the order it should be built, with the evidence for
each claim. Three sources feed this: the competitor review's tiered build order, the nine-item
FEATURES plan, and the standing UI rule that no screen is allowed to look like it came from
another app.

Written 2026-08-11. Statuses below come from reading the tree, not from the review's assumptions,
and four of them disagree with the review.

## What the code says that the review does not

| Item | Review said | Tree says |
| --- | --- | --- |
| Tier 1 #8 remote images | to build | **shipped** — live screenshot shows "Images blocked so UltaHost can't tell you opened this. Show / Always" |
| Tier 2 #11 links to browser | to build | **shipped** — `GridlinkMessageBody.kt:346` fires `ACTION_VIEW`, zero `CustomTabs` anywhere under `ui/gridlink` |
| Tier 2 #12 widgets | to build | **two thirds shipped** — `UnreadWidgetProvider` (count) and `InboxWidgetProvider` (scrolling recent mail) both exist and update live; only the calendar agenda widget is missing |
| Tier 2 #10 density | to build | **exists on the wrong UI** — `ListDensity` / `LocalListDensity` is read only by `ui/components/EmailListItem.kt`, the upstream Sterna list. The live Gridlink list ignores it, so the setting does nothing a user can see |
| Tier 2 #9 cache on frame one | to build | **mostly there** — `GridlinkListSkeleton` covers the empty first load, cached mail renders without a network wait. Needs an audit, not a build |

## Phase 1 — close Tier 0

Tier 0 is "the app is not credible without this". Items 1 and 2 are done (`cda34885`, verified),
item 4's save half landed in `d9daa60f`.

1. **Composer attachments, end to end.** The blocker for share-into-app is that the Gridlink
   composer has no attachment path at all: `GridlinkComposeScreen.kt:771`'s attach button is an
   empty lambda, and `GridlinkSender.kt:114` refuses any draft carrying one. Work: pick files,
   consume `pendingShareUris` stashed by `MainActivity.parseShare`, stage into the existing
   `OutboxAttachment` descriptor (`KIND_IMAP_FILE` path / `KIND_JMAP_BLOB` blobId), drop the
   refusal. Sharing a photo into the app then produces a real draft with the photo on it.
2. **Cursor stability in a long quoted reply.** Send itself works (`sendWithUndo` →
   `sender.check` → `enqueueSend`). The unverified half is the review's actual complaint: typing
   above a long quote and having the caret jump or the view scroll away. Reproduce on a genuinely
   long thread before deciding whether anything needs changing.

Tier 0 closes here.

## Phase 2 — no foreign screens

The standing rule, and the reason the document picker came out of the save flow. Two screens still
break it, and both are reachable in normal use.

3. **Settings, restyled into the Gridlink layer.** Currently upstream Sterna: wrong type scale,
   wrong spacing, wrong colour.
4. **Setup / connect, same.** First thing a new user sees.
5. **Retire the dead upstream screens.** `AppNavHost.kt:306` already marks `ui/inbox` unreachable
   for a signed-in user. `ui/message`, `ui/compose`, `ui/connect`, `ui/settings`, `ui/inbox`,
   `ui/components/EmailListItem.kt` are a second parallel UI carrying its own bugs and its own
   translation load. Delete what nothing reaches, once 3 and 4 replace what does.

Doing 5 after 3 and 4 is deliberate: the density setting in Phase 4 is only worth building against
one list, and this decides which list survives.

## Phase 3 — Tier 1, the differentiators

6. **Push into subfolders**, not just the inbox. The single most-repeated complaint in the corpus
   about every competitor.
7. **Contacts and calendar as a real Android account**, so the system's own apps see them.
   Largest item in the plan by some distance: an authenticator, a sync adapter, and provider
   plumbing. Worth its own scoping pass before it starts.
8. **Say the offline-first part out loud.** Already true architecturally (Room + Paging, cached
   attachments) and marketed nowhere. Documentation, not code.

## Phase 4 — Tier 2, the visible polish

9. **Density on the live list.** Rebuild the control against the Gridlink list, or cut it. Depends
   on Phase 2 item 5.
10. **Calendar agenda widget.** The one missing third of Tier 2 #12.
11. **Cache-on-frame-one audit.** Confirm no screen waits on the network before drawing what it
    already has; fix what does.

Tier 2 #13 (partial-substring search) shipped 2026-08-11.

## Phase 5 — FEATURES plan, resumed

Items 1, 2, 3 and 5 shipped 2026-08-09. Item 4 (folder subscribe) is deferred by Tate, and its
drag-to-reorder third is permanently cut.

12. **Read receipts / MDN (item 6).** Carries a decision for Tate: recommendation is never
    auto-send, prompt off by default, so the app never confirms you read something without asking.
13. **Calendar conflict detection (item 7).** Unblocked, reads the app's own CalDAV cache, needs no
    Android permission.

## Phase 6 — trust and finish

14. **TalkBack sweep (item 8).** After the UI churn of Phases 2 and 4, not before, or it gets done
    twice.
15. **S/MIME (item 9).** Last, with a key-custody decision to make: recommendation is verify-only
    first.
16. **Tier 3, which is policy rather than code.** Don't break gestures people already have in their
    fingers; a public tracker with visible responses; no client-side feature ever behind a paid
    unlock. Items 14 and 16 are already how this app is being built, so the work is writing them
    down where a user can read them.
17. **The 49 Dependabot advisories.** Triage, not a blanket bump.

## Notes that outlive this plan

- Room schema is at **22**. Anything needing a migration goes 22 → 23.
- `TranslationParityTest` gates every new `<string>` across nine locales. `ui/gridlink` uses
  hardcoded English and is exempt, which is another reason Phase 2 item 5 pays for itself.
- CI before every commit: `:core:jmap:test :core:imap:test :core:dav:test
  :core:data:testDebugUnitTest :app:testDebugUnitTest` then `:app:assembleDebug`.
