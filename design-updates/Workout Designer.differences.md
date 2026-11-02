# Workout Designer: original mock vs live app

- Original mock: `Workout Designer.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Workout Designer.dc.html` (**shell only**, see below)
- Live source: `frontend/src/screens/WorkoutDesignerScreen.tsx` (read), `workouts/WorkoutEditor.tsx` (not read), `workouts/StepDrawer.tsx`, `TemplatesPanel.tsx` (not read)
- Screenshots: `screenshots/workout-designer-live-1380.png` (live editor, "+ New workout")

This screen needs a dedicated redesign pass. It was compared visually and by
text with the live editor, and only the shared shell changes were applied.

Checked: the live editor's top level (new workout, list view) by screenshot and
text, against the mock's text. Not checked: Chart-first layout, the step
drawer's fields, the Templates panel, the Export and Publish & assign modals,
nested repeat groups, Violet/Day.

## Page shell

Sidebar and theme toggle changed as on every screen (see "Page shell" in
`Dashboard.differences.md`; Workouts active), plus the `isAdmin` flag. The
standard top bar was **not** applied: this mock's sticky page header is its own
control bar (see below), and the app's editor has no equivalent.

## What the live editor looks like

Under the standard top bar (+ Import activity, bell, profile chip), one large
card contains, top to bottom:

1. **Name** (text input, "New workout") and **Sport** (a select reading `bike`), then **Start from ▾**, **Templates** and **Export** buttons on the same row.
2. Summary stats **DURATION / EST. TSS / STEPS** on the left, a **List view / Chart-first** switch on the right.
3. The intensity chart on the left, and on the right a placeholder panel: "Select a step in the structure to edit its target, duration and notes." (the step drawer opens here).
4. **Structure**: step rows, each with a coloured bar, name, "00:10:00 · 50→70% FTP" and ↑ ↓ ⧉ ✕ icon buttons.
5. Add buttons: "+ Warm-up", "+ Work block", "+ Recovery", "+ Cooldown", and a dashed ember "+ Repeat group".
6. **Save** (ember), **Publish & assign** and **Back to list** at the bottom.

A new workout starts with two steps (Warm-up 00:10:00 50→70% FTP, Cooldown
00:05:00 55→40% FTP), 15 minutes, TSS 8.

## Differences

| Area | Original mock | Live app |
|---|---|---|
| Top of page | A sticky custom bar: workout title (an inline text field), Bike / Run toggle, a monospace summary ("00:55:00 · 73 TSS · 12 steps · Bike"), then ← Analysis, Start from ▾, Templates, Export, **Save** and **Publish & assign** at the top right. It replaces the app's top bar (no Import activity button, no profile chip) | The standard top bar; the controls sit inside the editor card (Name field, Sport select, Start from, Templates, Export) |
| Sport control | Bike / Run segmented toggle | A `bike` select |
| Save / Publish & assign | Top bar, right | Bottom of the card, with a "Back to list" button |
| Analyze mode | A second mode ("← Analysis" / "Build") that shows the workout with Send to calendar and Export | No such mode in the editor. The read-only view is the Workout Detail page (Edit button, structure, linked activities, upcoming, candidate matches) |
| Layout | Stats card, chart card and structure card stacked, with the step editor as a tall right-hand column | One card; chart and step panel side by side; structure below |
| Empty step panel | The right column shows an editor for a pre-selected step | A placeholder until a step is chosen |
| Back navigation | "← Analysis" | "Back to list" (returns to the Workout Library) |

## Sample data

The mock shows "VO2 Max · 5×5" (12 steps, one repeat group with a coach note);
the live editor opens a blank two-step workout. The mock's nested repeat-group
controls ("+ step in group", "+ nested repeat group") and cadence secondary
targets were not seen on the live blank editor.
