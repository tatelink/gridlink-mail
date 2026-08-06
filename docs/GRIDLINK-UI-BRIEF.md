# Design Brief: Android Email Client (JMAP)

## What this is

A native Android email client for a self-hosted Stalwart mail server over JMAP. Kotlin, Jetpack Compose, Material 3. This is a UI layer being built on top of an existing, working mail engine (a fork of Gridlink Mail), so nothing here depends on inventing protocol behavior. Design the interface only.

**Single user. Single job.** A multi-unit restaurant operations director processing 80 to 150 messages a day, most of them automated reports, on a Galaxy Z Fold 7. The app's one job is to let him find the four messages that need a human response and dispatch the rest in under a minute, one-handed, often while walking.

**This is not a general-purpose email client.** Do not design for onboarding, marketing, multiple personas, or first-run delight. Design for the 400th session.

---

## 1. Visual direction: inherit an existing system

The user already runs a Home Assistant dashboard with a three-mode theme ladder. The email client must feel like a sibling app, not a cousin. Three attached screenshots show Day, Night, and OLED. Study them; the palettes below are extracted from them.

**Critical constraint: the three modes differ ONLY in color.** Same layout, same spacing, same type scale, same component geometry in all three. A mode switch should feel like the lights changing in a room, not like a different app. Design the layout once, then produce three palette applications.

Mode switching is automatic on a time-of-day ladder: Day, then Night at dusk, then OLED late at night. Provide a manual override pill in settings matching the dashboard's pill (labeled `Auto · Day`, `Night`, `OLED`).

### Palette: Day

| Role | Value |
|---|---|
| Background | Gradient, cyan `#4DD5F0` top-left to blue `#2F6FE0` bottom-right |
| Surface | Translucent white over gradient, `#FFFFFF` at 55%, 1px `#FFFFFF` at 70% border |
| Surface raised | `#FFFFFF` at 72% |
| Text primary | `#0A0F1A` |
| Text secondary | `#3A4A5F` |
| Accent interactive | `#1B7FE8` |
| Positive | `#16A34A` |
| Attention | `#D97706` |

### Palette: Night

| Role | Value |
|---|---|
| Background | `#050A14`, faint blue-violet radial glow behind the primary element only |
| Surface | `#0D1524` at 85%, border `#5A78B4` at 18% |
| Surface raised | `#141E31` |
| Text primary | `#FFFFFF` |
| Text secondary | `#8CA0BC` |
| Accent interactive | `#3B82F6` |
| Accent warm | `#F6B87C` |
| Positive | `#34D399` |
| Attention | `#FBBF24` |

### Palette: OLED

| Role | Value |
|---|---|
| Background | `#000000`, true black, no gradient, no glow |
| Surface | `#000000` with a `#F97316` at 22% hairline border only |
| Surface raised | `#0A0604` |
| Text primary | `#E9A87F` |
| Text secondary | `#9C8574` |
| Accent interactive | `#F97316` |
| Accent warm | `#FB923C` |
| Positive | `#34D399` |
| Attention | `#FB923C` |

OLED mode earns its name: surfaces are literally black so pixels stay off. Definition comes from hairline borders and text color, never from a lighter fill. Remove all glows and shadows in this mode.

### The one new color

The dashboard uses no red anywhere. This app needs one, for delete only. Introduce `#DC2626` (Day/Night) and `#B91C1C` (OLED) and use it for **nothing else in the entire app**. Because it is the only hue outside the inherited system, it will read as an alarm without needing size or weight to carry the warning. Do not use it for errors, badges, or unread counts.

### Semantic logic to preserve

The dashboard already encodes meaning in hue, and email must follow the same grammar:

- Green: settled, safe, done. Therefore **archive**.
- Amber/orange: needs attention. Therefore **unread**.
- Blue (or orange in OLED): interactive, tappable.
- Red: destructive. **Delete only.**

---

## 2. Typography

The dashboard uses a geometric sans with high x-height and heavy display weights. Match it with **Outfit** (variable, good weight range) or **Poppins**.

| Role | Spec |
|---|---|
| Screen title | 32sp / ExtraBold / -1% tracking |
| Section label | 13sp / SemiBold / +6% tracking / uppercase |
| Sender name | 15sp / SemiBold |
| Subject line | 15sp / Regular |
| Snippet + metadata | 13sp / Regular / secondary color |
| Timestamp | 12sp / Medium / **tabular numerals** |
| Counts and badges | 12sp / Bold / tabular numerals |

Tabular numerals on timestamps and counts are non-negotiable. Proportional digits make a scrolling list of times visibly jitter.

---

## 3. Geometry and density

**Chrome is spacious. The list is dense.** This is the central tension to resolve and the brief resolves it this way deliberately.

Spacious (inherit the dashboard): the top header area, the floating bottom navigation pill, mode toggles, sheets and dialogs, empty states. Corner radius 28dp on cards, fully rounded on pills. Generous 20dp padding.

Dense (do not inherit): the message list itself. Rows are 64dp tall, 16dp horizontal padding, 12dp vertical, separated by a 1px hairline at 12% opacity rather than by gaps or cards. **No card-per-message.** Target 13 rows visible on a folded Fold screen.

Spacing scale: 4 / 8 / 12 / 16 / 20 / 28 / 40.

The gradient and glow live in the header and behind the floating nav bar. The list scrolls on a flat surface so scrolling costs nothing per frame.

---

## 4. Message row anatomy

No avatar circles. Sender identity is carried by a 3dp vertical color bar on the leading edge, colored by sender domain, which gives instant visual grouping in a list dominated by a handful of repeat senders.

```
┌──────────────────────────────────────────────────┐
│▌ Tallyman                            7:14 AM  │
│▌ Daily Sales Summary 2043 HILLCREST 07/30      📎  │
└──────────────────────────────────────────────────┘
```

Two lines maximum. Line 1: sender, right-aligned timestamp. Line 2: subject, truncated with ellipsis, attachment glyph if present. Snippet text is **omitted** at this density; it is the first thing that pushes rows to three lines and it is rarely useful for automated mail.

Unread state: sender and subject at full weight and primary color, plus a 6dp amber dot in place of the timestamp's leading space. Read state: subject drops to secondary color and Regular weight. Do not use background fills to indicate unread.

---

## 5. Automated-sender bundling (structural, not decorative)

Most of this inbox is machine-generated. The list must separate people from robots.

Messages from senders marked automated collapse into a single row that expands in place:

```
┌──────────────────────────────────────────────────┐
│▌ Reports                            14 new    ⌄  │
│▌ Tallyman, Power BI, Verdant                │
└──────────────────────────────────────────────────┘
```

Expanding pushes the bundle's messages in below it, indented 12dp, with a continuous vertical rule at the indent to show containment. Collapsed by default. The bundle row itself supports the same swipe actions, applying to every message inside it, which is the fastest way to clear a morning's reports.

Design both states and the mid-expansion frame.

---

## 6. Hard requirements

These four are the reason this app is being built. Each needs designed states, not just a mention.

### 6a. Swipe actions

Three actions across two directions, no menus:

| Gesture | Action | Track color | Icon |
|---|---|---|---|
| Swipe right past 25% | Archive | Positive green | Box with down arrow |
| Swipe left, 25% to 60% | Mark unread | Attention amber | Filled dot |
| Swipe left past 60% | Delete | Red | Trash |

The row translates and reveals a full-bleed colored track beneath it. The icon sits at the revealed edge and scales from 0.8 to 1.0 as the threshold is crossed. On the left swipe, crossing 60% swaps the icon and track color from amber to red in a single spring, paired with a haptic tick, so the escalation is felt as well as seen.

**Deliver mid-gesture frames**, not just before and after. Specifically: right swipe at 40%, left swipe at 40%, left swipe at 75% immediately after the amber-to-red swap. These frames are the design.

Releasing before threshold springs the row back. Completing an action collapses the row height to zero over a spring and surfaces an undo snackbar.

### 6b. Selection toolbar

When one or more messages are selected, a toolbar appears at the bottom and **stays fixed while the list scrolls**. It must never scroll away or hide on scroll.

Make it a transformation, not an arrival: the floating navigation pill morphs in place into the action bar, same height, same corner radius, same horizontal inset. Contents cross-fade while the container's shape holds.

Four actions, evenly spaced, icon over 11sp label: **Reply, Archive, Delete, Spam.**

Reply is disabled and dimmed to 38% when more than one message is selected. Delete is the only red element. The top bar simultaneously becomes a selection header showing "3 selected" with a close control on the leading edge and select-all on the trailing edge.

Design: toolbar with 1 selected (Reply active), toolbar with 5 selected (Reply dimmed), and the mid-morph frame.

### 6c. Delayed send

Two separate mechanisms, both needed:

**Undo window.** After tapping send, a snackbar appears with a countdown ring draining around the undo control over 10 seconds. The mail has not left. Tapping undo returns to the composer with the draft intact and no "draft saved" interruption. Design the ring at full, half, and nearly drained.

**Schedule send.** A long-press on the send control opens a bottom sheet. Preset chips sized to this user's actual working day:

- Tonight, 6:00 PM
- Tomorrow, 7:00 AM
- Monday, 8:00 AM
- Pick a time

Scheduled messages live in their own tree node with a small clock glyph and show their send time in place of a timestamp. Opening one offers "Send now" and "Reschedule."

### 6d. Folder tree management

Full screen, not a cramped drawer. The JMAP mailbox tree with direct create, rename, delete, and reparent.

Each row: disclosure chevron if it has children, folder name, unread count right-aligned in tabular numerals. Children indent 16dp per level with a continuous vertical rule at each indent so deep nesting stays readable.

- **Create:** a persistent "New folder" row at the bottom of each expanded level, so the folder's parent is unambiguous from where you tapped. Tapping it turns the row itself into an inline text field with the keyboard already up. No dialog.
- **Rename:** long-press, then the row becomes an inline field with text preselected.
- **Delete:** long-press menu, confirmation required, and the confirmation must state the message count and whether children will also be deleted. This is the one place a modal is correct.
- **Reparent:** long-press and drag. Valid drop targets get a 2dp accent outline; the dragged row lifts to a raised surface at 4dp elevation.

Design: the tree collapsed, the tree with two levels expanded, inline create in progress with keyboard visible, and the delete confirmation.

---

## 7. Fold adaptive layout

Folded (compact width): single pane, list only, tapping opens the thread as a new screen with a shared element transition where the tapped row's color bar and sender line morph into the thread header.

Unfolded (expanded width): two panes, list at 380dp fixed on the left, thread filling the remainder. Selection state persists across the fold and unfold transition. The selection toolbar spans only the list pane.

---

## 8. Motion

Springs only, no fixed-duration easing curves. Specify motion as stiffness and damping, not milliseconds.

- Standard transition: stiffness 380, damping ratio 0.85
- Swipe release and snap-back: stiffness 500, damping ratio 0.75
- Toolbar morph: stiffness 300, damping ratio 0.9
- Row collapse after action: stiffness 400, damping ratio 1.0 (no overshoot on destructive actions)

Target 120Hz. Nothing may block a frame. Respect the system reduced-motion setting by dropping to opacity-only transitions.

---

## 9. Anti-requirements

Do not include any of the following. Each is a default that would make this look like every other mail app:

- Avatar circles or sender initials in the list
- A floating action button
- Card-per-message with drop shadows
- Live/real-time blur behind scrolling content (per-frame GPU cost, guaranteed jank)
- Snippet preview text in list rows
- Swipe-to-reveal action menus (actions must complete on the swipe itself)
- Lorem ipsum, placeholder names, or invented sample content
- Pull-to-refresh (JMAP pushes; a refresh gesture would be theater)
- Onboarding, welcome, or upsell screens

---

## 10. Use this real content

Every mockup must use these actual senders and subject lines. They are long, ugly, and repetitive on purpose, and a design that only looks good with short subjects is not finished.

| Sender | Subject | Time |
|---|---|---|
| Tallyman | Daily Sales Summary 2043 HILLCREST 07/30 | 7:14 AM |
| Power BI Service | Refresh failed: District7_P7_Rollup (dataset) | 6:52 AM |
| Verdant | ACTION REQUIRED: Corrective Action Plan due 08/04 Store 604 | 6:31 AM |
| Jonah | did you feed the dogs | 6:22 AM |
| Larkfield HR | Open Enrollment closes Friday, action needed for all salaried TMs | Yesterday |
| Tallyman | Labor Variance Exception Report 2096 FERNHILL RD Week 30 | Yesterday |
| M. Ridley | Callout Saturday AM, need coverage 2071 Kirkwood | Yesterday |
| Northgate Group Talent | Franchise Business Consultant, next steps and availability | Tue |
| Verdant | Pest Sighting Report filed 2118 ELLSWORTH | Tue |
| Power BI Service | Your subscription: District 7 Weekly Scorecard | Mon |

---

## 11. Deliverables

**First, the token file.** A single Kotlin-ready token set: every color role in all three modes, spacing scale, type scale with size and weight and tracking, corner radii, and motion specs as stiffness and damping. This file governs everything else, so produce it before any screen.

**Then, screens.** Design each in **Night** as primary:

1. Message list, mixed read/unread, one bundle collapsed
2. Message list, bundle expanded
3. Swipe mid-gesture, three frames per section 6a
4. Selection toolbar, 1 selected and 5 selected
5. Thread view
6. Compose
7. Schedule send sheet
8. Undo-send snackbar with countdown ring
9. Folder tree, collapsed and expanded
10. Folder tree, inline create in progress
11. Empty inbox
12. Loading (skeleton, not a spinner)
13. Unfolded two-pane

**Then, palette variants.** Screens 1, 4, and 9 in both Day and OLED, to prove the palette holds at the extremes.

**Empty and loading states are not optional.** They are where apps quietly feel cheap and they are the first thing most designs skip.