# Scheduled Workout: original mock vs live app

- Original mock: `Scheduled Workout.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Scheduled Workout.dc.html`
- Live source: `frontend/src/screens/ScheduledWorkoutScreen.tsx` (lines 100-243 read); `workouts/WorkoutStructureList.tsx` not read
- Screenshots: `screenshots/scheduled-workout-design-updated-1380.png` only. **No live screenshot**: the live dev account has no planned workouts (every calendar entry is completed), and creating one would change local data. This comparison is from the source code.

Checked: the page body against the component source (labels, copy, layout
styles). Not checked in a browser: any live rendering, the Zwift export
modal, validation. Violet/Day not compared.

## Page shell

Sidebar, bell and theme toggle changed as on every screen (see "Page shell" in
`Dashboard.differences.md`; Workouts active). `coachAccount` editor prop
(default off), profile chip FTP 260 · LTHR 154.

## Screen-specific differences

The mock's layout, copy and field styles already match the component
(Date, Time of day Morning / Midday / Evening, Notes with "e.g. swap if it
rains", Save changes, Structure card, Unschedule, "Scheduled by {coach}",
"↓ Garmin (.tcx)"). Applied to the mock:

| Area | Original mock | Live app (by source) |
|---|---|---|
| Structure strip | 2px gaps, rounded bars at 0.85 opacity, no background | The library's `MiniChart`: `--elev` strip, 6px radius, 1px gaps, 0.65 opacity, bars coloured by intensity zone |

Not applied (described only):

| Area | Original mock | Live app (by source) |
|---|---|---|
| Structure list | Flat rows: dot, label, duration, target | `WorkoutStructureList` in read-only mode, the same rows as Workout Detail, including repeat groups and step notes |
| Zwift export | Absent | "↓ Zwift (.zwo)" button for bike workouts, opening an export modal |
| AI scheduler | Absent | An "AI" chip after "Scheduled by {name}" when the scheduler is a virtual coach |
| Approximate-target note | Absent | A hint under the download buttons when FTP / LTHR / threshold pace are unset ("Set your FTP / LTHR / threshold pace in Settings for accurate targets in the .tcx - falling back to an approximate placeholder for now.") |
| Save errors | Absent | Red "Failed to save. Try again." beside Save changes |
| Unschedule | Immediate | Browser confirm ("Unschedule {name}?") first; button reads "Unscheduling…" while pending |
| Save button | Always enabled in the mock's logic | Disabled until a field changes |

## Sample data

Left as the mock's own: "Long run — easy", scheduled by Mara Køhl, 27 Sep
2026, AM, note "Hilly route if dry".
