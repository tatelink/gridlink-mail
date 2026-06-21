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
