# Design

This document defines Jmail's visual and motion language. It is the reference
for building the Compose UI so screens stay consistent. It complements
[ARCHITECTURE.md](ARCHITECTURE.md) (technical) and [FEATURES.md](FEATURES.md)
(scope).

## Direction

**Material Expressive, kept restrained.** We use Material 3 Expressive's
components and motion physics, but dial back colour and bounce. The feel we are
after is **sober, elegant, and responsive (réactif)** — lively, never busy.

Three principles:

1. **Hierarchy comes from space and type, not colour.** One accent colour, lots
   of neutral surface.
2. **Privacy shapes the visuals.** No network requests just to render a list
   (see avatars).
3. **Motion is fast and damped.** Everything ≤ 250ms, ease-out, no overshoot.

## Visual language

### Colour

- Material You dynamic colour, but **desaturated**: warm-neutral surfaces with a
  **single accent** (derived from the wallpaper) for actions and the unread
  state. No multi-colour cards.
- Convey hierarchy through elevation and spacing rather than hue.
- Dark mode uses a near-black `surfaceContainerLowest` — elegant and power-frugal
  on the Pixel 7 OLED.

### Typography

- Material 3 type scale. Lean on contrast: sender in `titleMedium` (medium
  weight), preview in `bodyMedium` with `onSurfaceVariant`. That contrast is what
  reads as "elegant" without ornament.

### Shape & spacing

- Moderate corner radii (12–16dp). **No card per message row** — use airy rows
  separated by whitespace. Reserve a card for the opened message only.
- 8dp grid, generous 16dp margins. Sobriety comes from the whitespace.

### Avatars (privacy)

- No remote photos by default (consistent with blocking remote content).
- Use **coloured monograms**: initial plus a colour derived from a hash of the
  address. Sober, and no network leak.

## Components

| Component | Treatment |
|---|---|
| Top bar | `LargeTopAppBar` that collapses on scroll; search + settings as actions; hamburger opens the folder drawer. |
| Message row | monogram · (sender + subject + 1-line preview) · (time + unread dot). Accent dot for unread, no shouting bold. |
| Folder drawer | `ModalNavigationDrawer` (the M3 drawer we kept): account header, folder tree, selected item in `secondaryContainer`. |
| Compose FAB | `FloatingActionButton` (extended) that shrinks to icon-only on scroll. |
| Opened message | The one place a card is used; HTML in a WebView with remote content blocked. |

## Motion

Golden rule for "réactif": everything **≤ 250ms**, `FastOutSlowIn`, **no
overshoot**. Material 3 Expressive springs are welcome, but tuned **damped** (no
bounce) so it stays alive yet sober.

| Interaction | Compose implementation | Duration / curve |
|---|---|---|
| List → message | `SharedTransitionLayout` / shared element | ~220ms, `FastOutSlowIn` |
| Row appearance | `animateItem()` on `LazyColumn` | default, subtle |
| Swipe actions | `SwipeToDismissBox`, action icon fades in | tracks the finger |
| Top bar / FAB collapse | `enterAlwaysScrollBehavior`, FAB `expanded` | native |
| Delete | row collapses (height → 0) | ~200ms ease-out |
| Pull-to-refresh | `PullToRefreshBox`, settle with no bounce | native, short |

## Settings & secondary screens

This covers everything that is not the inbox or a message: preferences and
account management.

### Model

Settings is a **single hub** with global categories plus a **per-account
section** — the K-9/Gmail two-tier model. The folder drawer keeps only quick
account switching and folders; full account management lives in Settings. The hub
itself stays short and scannable (icon · title · summary of the current value);
depth lives in detail screens. This is **progressive disclosure**: complete
configuration is reachable, but general settings stay on the surface.

### Information architecture

```
Settings (hub)
├─ Accounts
│   ├─ <account>            → per-account detail
│   ├─ …
│   └─ + Add account        → existing connect flow
├─ Appearance               → theme, dynamic colour, list density
├─ Notifications            → push scope, new-mail, quiet hours
├─ Reading                  → swipe actions, threading, mark-as-read
├─ Writing                  → signatures/identities, undo send, receipts, quote
├─ Privacy & Security       → remote images, app lock, link safety
├─ Storage & Sync           → cache, attachment download policy, export/import
└─ About                    → version, source, licences, privacy, report issue
```

### Global vs per-account

| Per-account (Accounts → detail) | Global (top-level categories) |
|---|---|
| Display name, account colour, signature override, per-account notifications, server info (read-only), **Sign out** | Theme, density, dynamic colour, default signature, undo-send, receipts, swipe actions, remote-image policy, app lock, cache/sync, about |

Sign out lives in the per-account detail (not the inbox top bar), consistent with
the hub model.

### Components

Reuse the existing `SettingSwitch`; add a small, consistent kit:

| Component | Role |
|---|---|
| `SettingsCategoryRow` | Hub row: icon, title, value-summary, chevron. |
| `SettingsSection` | Accent-tinted header grouping rows in a detail screen. |
| `SettingSwitch` *(existing)* | Boolean toggle. |
| `SettingChoiceRow` + `SettingChoiceDialog` | Single choice (theme, undo window, image policy, app lock). |
| `SettingNavigationRow` | Navigates to a subscreen or external link. |
| `AccountRow` | Monogram + label + email (reuses the monogram avatar). |

### Visual & motion rules

- **Hub:** a `LazyColumn` of category rows, **no cards**, 16dp margins, icon tint
  `onSurfaceVariant`, summary line in `bodyMedium` / `onSurfaceVariant`.
- **Detail screens:** sectioned lists; section headers in the single accent
  (`labelLarge`); separation by whitespace, not boxes; `LargeTopAppBar` that
  collapses on scroll (same pattern as the inbox).
- Controls use the accent colour; single-choice settings use an M3 dialog or
  bottom sheet.
- Motion follows the table above: ≤ 250ms, `FastOutSlowIn`, damped (no overshoot).

### Feature → setting map

Status mirrors [FEATURES.md](FEATURES.md) (✅ shipped · 💡 future). Future items
are documented here as structure; build the ✅ ones first.

- **Accounts (per-account):** display name ✅ · account colour 💡 · signature / identities 💡 · per-account notifications (push ✅) · server + username read-only ✅ · sign out ✅
- **Appearance:** theme Auto/Light/Dark 💡 · dynamic colour (Material You) ✅ *(new toggle)* · list density Comfortable/Compact 💡
- **Notifications:** push for all accounts ✅ · new-mail master + system-channels link · quiet hours 💡
- **Reading:** configurable swipe actions 💡 · conversation threading toggle (threading ✅) · mark-as-read on open · remote images (see Privacy)
- **Writing:** default signature 💡 · undo send Off/5/10/30s 💡 · read receipts Never/Ask/Always 💡 · quote-on-reply · "forgot attachment?" reminder 💡
- **Privacy & Security:** remote image policy Never *(default)* / Ask / Always ✅ · per-sender allowlist 💡 · app lock ✅ (biometric + PIN/pattern/password fallback) · confirm external links 💡 · "Jmail collects no data" → [PRIVACY.md](PRIVACY.md)
- **Storage & Sync:** sync / push on-off ✅ · download attachments on Wi-Fi only 💡 · cache size + Clear cache (Room cache ✅) · settings export / import 💡
- **About:** version · source (Codeberg) · GPLv3 licence · privacy policy ([PRIVACY.md](PRIVACY.md)) · OSS licences · report issue / security

### Storage note

App preferences are backed by a reactive DataStore `SettingsRepository`
(`Flow` per setting), separate from `AccountStore`, which keeps accounts,
credentials, and per-account metadata. See the implementation plan for details.
