# Calendar: original mock vs live app

- Original mock: `Calendar.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Calendar.dc.html`
- Live source: `frontend/src/screens/CalendarScreen.tsx` (lines 150-443 read), `calendar/AddRaceModal.tsx`, `calendar/ScheduleModal.tsx` (not read)
- Screenshots: `screenshots/calendar-live-1380.png`, `screenshots/calendar-design-updated-1380.png`

Checked: Teal theme, the month grid, week summary column and header controls,
visually in Safari and against `CalendarScreen.tsx`. Not checked: the Schedule
workout and Add race modals against their components, Violet/Day.

## Page shell (same on every screen)

See "Page shell" in `Dashboard.differences.md`. Applied here: plain sidebar
with Races and Admin and no icons, inert solid-border bell, `6px 12px` toggle
segments, `coachAccount` editor prop (default off), profile chip FTP 260 ·
LTHR 154.

## Screen-specific differences

The mock already matched the live component's structure: month heading and
prev / Today / next controls, Add race and Schedule workout buttons, the 7-day
grid plus a "Week" column with PLANNED / ACTUAL / RUN, time-of-day badges, the
note icon, dashed outlines for planned and missed sessions, unplanned activities
and race entries. The only per-screen differences found:

| Area | Original mock | Live app |
|---|---|---|
| Race entry link | Goes to `Preferences.dc.html` (placeholder) | Goes to `/races/:id`; there is no Races mock yet |
| Race entry fill | `--emberSoft` tint | Background is the string `var(--ember)22`, which is invalid CSS, so the entry has a teal left border and **no fill** |
| Week RUN pace | Hard-coded "5:12 /km" | Computed from the week's run distance and time |

The invalid race background looks unintentional in the app and was not copied
into the mock. The fix would be an `rgba` or `color-mix` value.

## Sample data

Not re-anchored. The mock's sample month is September 2026 with "today" on
26 Sep and fictional workouts (Threshold 3×10, Sweet Spot 3×12, City Marathon
on 4 Oct). The live account's calendar shows October 2026 with real entries
(Easy Bike / Easy Run sessions, the Yorkshire Marathon 2026 on 18 Oct, week
totals such as PLANNED 747 TSS · 12.3h). Re-anchoring would need per-entry
planned durations and TSS that the live grid does not display, so the mock's
data model would have to change. See `screenshots/calendar-live-1380.png` for
the real month.

## Live app changes since 21 Oct 2026 (refreshed 29 Oct)

The live screenshots were retaken at 1380px (Teal) against `main` at 9158def with the local backend rebuilt from the same commit. **The mock itself has not been updated for these changes.**

- **Schedule workout** now opens a dialog with a "Choose a workout..." button that opens a filterable picker (#470): search box, All / Bike / Run filter, grid of workout cards with sport, duration, TSS and a zone-coloured profile.
- Screenshots: `calendar-schedule-modal-live-1380.png` (dialog) and `calendar-workout-picker-live-1380.png` (picker). The mock has neither.
