# Workout Detail: original mock vs live app

- Original mock: `Workout Detail.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Workout Detail.dc.html`
- Live source: `frontend/src/screens/workouts/WorkoutDetailScreen.tsx` (lines 40-156 read); `WorkoutStructureList.tsx`, `WorkoutMatchScanCard.tsx` (not read)
- Screenshots: `screenshots/workout-detail-live-1380.png` (a live "Easy Bike — 60min Zone 2" workout), `screenshots/workout-detail-design-updated-1380.png` (the mock's sample "The Gorby")

Checked: Teal theme, read-only view, against the live page and the screen
source. Not checked: the scan-for-matches configure / results states, the
structure rows for steps with notes (the live page shows a ✎ and the note
inline), repeat-group styling against `WorkoutStructureList`, Violet/Day.

## Page shell

The sidebar, bell and theme toggle changed in the same way as on every screen
(see "Page shell" in `Dashboard.differences.md`; Workouts active). The theme
toggle keeps the mock's own `5px 11px` padding here.

This page also differed from the app in its own header:

| Area | Original mock | Live app |
|---|---|---|
| Top bar | A breadcrumb bar ("Workouts / The Gorby", sticky) with the bell and an **empty box** where the profile chip belongs (the chip's name and metrics were never bound) | The standard top bar: + Import activity, bell, divider, profile chip |
| Back link | "← Workout Library" above the title | None |
| Structure card heading | "STRUCTURE" label | No label; the step list starts at the top of the card |
| Page padding and width | `24px 32px 64px`, `max-width: 680px` | The app's `32px` page padding, content `max-width: 640px` |

## Empty states

The mock's two empty-state messages were tied to the same flags as the lists,
so "No activities linked to this workout yet." and "Not currently scheduled."
showed together with the lists. The live page shows each only when the list is
empty. The mock now uses separate `noLinked` / `noUpcoming` flags. Verified
both are hidden with the sample data.

## Sample data

Left as the mock's own: "The Gorby" with a repeat group and six linked
activities. The live account's workouts differ (for example the Easy Bike —
60min Zone 2 shown in the live screenshot has two steps, one with a note, two
linked activities and nothing scheduled).
