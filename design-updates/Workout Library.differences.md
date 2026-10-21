# Workout Library: original mock vs live app

- Original mock: `Workout Library.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Workout Library.dc.html`
- Live source: `frontend/src/screens/workouts/WorkoutLibraryScreen.tsx` (card, row actions, folder picker, mini chart read; toolbar and list view not read line by line). `/workouts` renders this screen; "New workout" opens the editor (see Workout Designer).
- Screenshots: `screenshots/workout-library-live-1380.png` (full page, all 76 workouts), `screenshots/workout-library-design-updated-1380.png` (full page, 8 sample workouts)

Checked: Teal theme, grid view, against the live text
and screenshot and the card source. Not checked: list view, the bulk-action bar,
the Assign modal, Import, folder creation, Violet/Day.

## Page shell (same on every screen)

See "Page shell" in `Dashboard.differences.md`. Applied here: plain sidebar
(Workouts active) with Races and Admin and no icons, inert solid-border bell,
`coachAccount` editor prop (default off), profile chip FTP 260 · LTHR 154. The
theme toggle keeps the mock's own `5px 11px` segment padding here because the
same style also drives the Sport filter; the app uses `6px 12px`.

## Screen-specific differences

| Area | Original mock | Live app |
|---|---|---|
| Card / row actions | Five SVG icons: Assign, Edit (pencil), Duplicate, Export, Delete (trash) | Four text glyphs: ⚲ Assign, ⧉ Duplicate, ↓ Export, ✕ Delete (red `#c4332a`). **No Edit button**; clicking the card opens the detail page |
| Sport badge | "Bike" / "Run" | "Ride" / "Run" (the Sport filter in the left rail still reads All / Bike / Run) |
| Structure chart colours | One colour per sport (blue bike, red run), opacity 0.55 | Each bar is coloured by intensity zone: grey under 56%, blue under 76%, green under 91%, amber under 106%, red-orange under 121%, red above; opacity 0.65. Bar width follows the step's duration |
| Folder select | Shown only when a workout has a folder | Always shown on every card, reading "No folder" when unset |

The first three were applied to the mock. The always-present folder select was
not, because every sample workout in the mock has a folder.

## Sample data

Not re-anchored. The mock shows 8 sample workouts in 4 folders; the live
library has 76 workouts, one folder view ("All workouts 76") and 30 tags such
as yorkshire-2026, marathon-pace and aerobic. See the live screenshot.
