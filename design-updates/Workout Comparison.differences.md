# Workout Comparison: original mock vs live app

- Original mock: `Workout Comparison.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Workout Comparison.dc.html`
- Live source: `frontend/src/screens/workouts/WorkoutComparisonScreen.tsx` (lines 90-224, METRICS and ColHeaders read); `WorkoutComparisonChart.tsx` not read
- Screenshots: `screenshots/workout-comparison-live-1380.png` (live, a workout with two linked rides), `screenshots/workout-comparison-design-updated-1380.png`

Checked: Teal theme, page header, metric buttons, ranking table, against the
live page and screen source. Not checked: the trend chart's drawing and hover
against `WorkoutComparisonChart.tsx`, the regenerate button's error state in a
browser, Violet/Day.

## Page shell

Sidebar, bell and theme toggle changed as on every screen (see "Page shell" in
`Dashboard.differences.md`; Workouts active). As on Workout Detail, this mock
also had its own breadcrumb bar ("Workouts / ..."), with an empty box where the
profile chip belongs. The live page uses the standard top bar, so the mock now
does too. Page padding is the app's `32px` and the content is `max-width: 900px`
(mock: `960px` with `24px 32px`).

## Screen-specific differences

| Area | Original mock | Live app |
|---|---|---|
| Metric buttons | Nine: Aerobic Efficiency, Avg Power, Avg HR, Work-block Power, **Block HR**, **Avg Core Temp**, **Avg Air Temp**, **Avg Humidity**, **TSS** | Eight: Aerobic Efficiency, Avg Power, Avg HR, Work-block Power, **Work-block HR**, **Core Temp**, **Air Temp**, **Humidity**. No TSS metric (TSS is only a table column) |
| First column header | "Rank" | "#" |
| Date format in the ranking | ISO ("2026-08-30") | "Sun, 30 Aug 2026" |
| Table min width | 900px | 830px |

All four were applied to the mock.

## Bugs fixed in the mock

- "Not enough matches yet to chart." and "No matches have this metric yet." were
  bound to the same flags as the chart and the ranking, so they showed together
  with real content. They now use `noChart` and `noRanking`.
- The Work-block power value was bound to the Regenerate button's flag, so it
  never rendered next to a real value (the column was blank for every row that
  had data). It now has its own `showWorkBlockPower` flag.

## Sample data

Left as the mock's own: "The Gorby" with six matched activities, one of them
missing work-block data (shows the Regenerate button). The live workout in the
screenshot has two linked rides.
