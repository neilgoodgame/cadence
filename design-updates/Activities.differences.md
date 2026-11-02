# Activities: original mock vs live app

- Original mock: `Activities.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Activities.dc.html`
- Live source: `frontend/src/screens/ActivitiesScreen.tsx`, `screens/activities/ActivityCard.tsx`
- Screenshots: `screenshots/activities-live-1380.png`, `screenshots/activities-design-updated-1380.png` (1380px wide, full page; the live capture has every activity loaded by scrolling the infinite list to the end, 90 cards)

Checked: Teal theme, visually in Safari and against the React source for both
files above. Not checked: Violet/Day, the expanded "Show zones" panel in the
app (`ZoneBar.tsx`, `LinkedActivityRow.tsx` not read), narrow viewports.

## Page shell (same on every screen)

The sidebar, notification bell, theme-toggle padding and coach switcher differed
from the app in the same way as on the Dashboard. See "Page shell" in
`Dashboard.differences.md`. Applied here: plain sidebar with Races and Admin
items and no icons, inert solid-border bell, `padding: 6px 12px` toggle
segments, coach switcher behind the `coachAccount` editor prop (default off).

## Screen-specific differences

| Area | Original mock | Live app |
|---|---|---|
| Date format on cards and linked rows | "29 Jul 2026"; linked rows sliced to "29 Jul" | "Sun 18 Oct" (weekday, day, month) in both places |
| Card tag row | Wraps (`flex-wrap`) | Does not wrap (`display:flex; gap:6`) |
| Search-help modal surface | `var(--card)` with backdrop blur | `var(--surface)`, no blur |
| Search-help field table | CSS grid | HTML `<table>` (same columns and text) |
| Zones toggle | `div` | `button` (same look) |
| Profile chip | FTP 265 · LTHR 155 | FTP 260 · LTHR 154 (live account) |

The tag-row wrap and modal surface differences were not copied into the mock.
Decide whether the app should wrap long tag rows.

## Sample data

Re-anchored to the live account's latest nine activities (18 Oct back to
10 Oct 2026), including names, metrics, tags, matched/indoor flags and the two
"1 linked" Easy Bike rows. The sample covers runs and rides, with and without a
workout: an unmatched run (18 Oct), matched runs, matched indoor rides and an
unmatched indoor ride ("Bike on 2026-10-10"). The tag filter now lists the live account's 30 tags
with their counts (Auto-matched 50 … strides 1). The previous swim, row,
multisport and "Heat Training" sample rows are gone because the live account
has none in this window. The expanded zone percentages are still sample values.
