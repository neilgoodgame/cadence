repo: neilgoodgame/cadence
branch: main

## Last sync
date: 2026-10-01T10:24:56Z
branch: main (tree 31f3fc75a616)

### Updated in this project
- "Thresholds & zones" is shipped: the Dashboard `ThresholdSummaryCard.tsx` dropped its "Show zones" expander for a single "Zones & history →" link shown on all four tabs (LTHR included); zones moved to `/thresholds/:field` (`ThresholdHistoryScreen.tsx`), which gained field tabs (including LTHR), a Current zones card (now vs. before the previous threshold), and a redesigned `ThresholdHistoryChart.tsx` (range toggle, year bands, best-in-range line, improvement/drop dot styling, hover tooltip). LTHR shows the zones card plus a note - no chart or ledger.
- Dropped the PROPOSED marker/dashed border from `ThresholdHistory.dc.html`'s header and the "(PROPOSED refresh)" chart comment - both are shipped now, not proposed.
- Backend: none (frontend-only feature).

## Sync history

### 2026-09-30T14:07:18Z
branch: main (tree 9768ad174651)

### Updated in this project
- Dashboard "Top efforts this week" is shipped (`dashboard/TopEffortsCard.tsx`, `WeekCalendar.tsx` badges, `components/BestEffortRow.tsx`, `lib/bestEfforts.ts`): dropped the PROPOSED marker/dashed border, adopted the app's trophy icon, sample data limited to v1 kinds (Power/Pace/HR; Distance/Elevation deferred).
- Activity Analysis: added the per-activity "Top efforts" card (`activity-analysis/ActivityBestEffortsCard.tsx`) after the matched-workout card.
- Endpoint shipped as `GET …/best-efforts/ranks` with explicit activity ids (not `recent-ranks?days=7`).

### 2026-09-26T15:51:21Z
branch: main (tree 750c6bb6e985)

### Updated in this project
- Full design refresh against `frontend/src` (app wins; originals kept in `Pre-sync Sep 2026/`). Shell on every screen: nav = Dashboard, Activities, Best efforts, Import, Calendar, Workouts, Gear, Preferences (+Admin), theme toggle + Log out, actions-only top bar, context switcher, email-verification banner (Dashboard `emailVerified` prop).
- Rebuilt from source: Dashboard (order, Thresholds card), Activities (CQL help, Row/Multisport, tag chips, sort), Best Efforts, Calendar (+ Schedule/Add race modals), new `Scheduled Workout.dc.html`, Gear, Preferences (full 12-tab page), Admin (shoe catalog), Import (single + zip batch).
- Patched: Activity Analysis (duplicates card/banner, multisport legs + leg banner via `variant` prop, comment Reply), Workout Library (Import button, per-card folder picker, Folder column).
- Kept as PROPOSED (design-only): Dashboard New-PR callout, Notifications bell, Admin Users/Grants/Audit tabs.
- 2026-09-27: pushed to repo via handoff `design_handoff_design_refresh/` → PR `design/refresh-sep-2026`, merged into `main`. Repo-root `.dc.html` files now match this project.


### 2026-09-13T00:00:00Z
- The design `.dc.html` files now live at the repo root (20 of them). Pulled in the three that did not exist in this project: `ThresholdHistory.dc.html`, `Workout Comparison.dc.html`, `Workout Detail.dc.html`.
- Left the other repo copies alone — this project's versions are ahead of them (e.g. `Activity Analysis.dc.html` local ~84KB vs repo 76KB, which predates the Stats-tab sync below). `Admin.dc.html`, `Best Efforts.dc.html`, `REST API.dc.html` exist only in this project.

### 2026-09-12T14:40:14Z
- `Activity Analysis.dc.html` synced to the repo's current `screens/activity-analysis/*`: added the threshold-history ledger indicator row above the header, the `↺ Recompute stats` button on the Stats tab, Best 20/60-min power in the POWER card, Best 60-min HR, Total Ascent in ELEVATION · ENERGY, and the ENVIRONMENT card inside the Stats grid (replacing the prototype's top conditions bar, which the repo does not have). Heat Strain Index re-attributed from HRM-Pro to CORE, matching `HeatStrainCard.tsx`.
- Still unrepresented in the design (read this pass, not drawn): `DuplicatesCard`/`DuplicateBanner`, `MultisportLegs` + `MultisportStreamChart`, and the Environment card's manual air-temp/humidity entry state.

### 2026-08-14T14:02:03Z
commit: main (92 commits since 58b900204ea6)
- Linked activities on matched workouts: confirmed implemented exactly per handoff spec — shared `frontend/src/components/LinkedActivityRow.tsx` (row + list + count badge), used by `MatchedWorkoutCard.tsx` (Activity Analysis) and `ActivityCard.tsx` (Activities list), collapsed by default in both. No design changes needed.

### Updated in this project
- Workout Library/Builder now has a real implementation (`backend/workouts/*`, `frontend/src/screens/workouts/*`, `frontend/src/api/workouts.ts`) built from this project's handoff. Notable deltas from the prototype worth folding back into the design:
  - **Folder is single-select per workout** (FK, not tags-style), changed inline via a dropdown on each card/row — not just the left-rail filter.
  - **Import** added to the library toolbar (.zwo/.xml/.json) alongside Export — prototype only had Export.
  - **"Most used" sort** = count of times scheduled to an athlete (`scheduled_workouts`), not a random mock value.
  - List view has a **Folder column**, no Steps column (prototype had Steps, no Folder).
  - Workout **matching** (auto/manual, confidence/compliance vs. activities) is a real `GET /v1/workouts/{id}/matches` endpoint, matching the Analyze-mode concept from the Builder.
  - `chart_preview` is computed server-side from the step tree on every save, not client-supplied.

### Not yet synced (open in CHANGES.md, not implemented in either backend)
- Replaced `openapi.yaml` with the repo's current version (92KB, both backends implemented against it)
- `Data Schema.dc.html`: activity gains device, aerobic/anaerobic training effect, training_effect_label, parent_activity_id, primary_activity_id; new `race` entity; removed speculative `lr_balance` from `record` (not yet implemented)
- `Data Dictionary.dc.html`: same activity columns + invariants added; new `race` table; `shoe.role` column added; sport enum extended with row/multisport/transition

### Not yet synced (open in CHANGES.md, not implemented in either backend)
- `record.left_right_balance`, `activity.avg_lr_balance`, `activity.trimp`, `user.resting_hr`
- Activity `Comment` resource (new entity + endpoints, permission model still undecided)
- Design prototypes (Dashboard, Activities, Gear, etc.) not yet updated to match repo's shipped features (weekly calendar + 16-week histogram replacing PMC, HR zone distribution, race calendar UI, CQL help modal, rowing sport in UI)

### Not synced yet — large surface added since last full design pass (not read in detail this turn, flagging for a future sync pass)
- Admin backend fully implemented (`backend/adminapi/*`, `backend_java/.../admin/*`): shoe catalog CRUD, user role toggles, coach-athlete relationship oversight, audit log — matches `Admin.dc.html` intent, not diffed line-by-line.
- New `EnvironmentCard`, `HeatStrainCard`, `CommentsSection`, `StatsTab` on Activity Analysis — check against `Activity Analysis.dc.html` next pass.
- Export/Import job system (`backend/dataexport/*`, `frontend/src/api/export.ts`, `dataImport.ts`) — not represented in any design file.
- `TrainingContextSwitcher`, `ProfileChip` added to `AppShell` — check against `Dashboard.dc.html`/nav design next pass.

## Screen map
| Screen | Repo source |
|---|---|
| Data Schema.dc.html | `CHANGES.md`, `SCHEMA_COMPARISON.md`, `openapi.yaml` |
| Data Dictionary.dc.html | `CHANGES.md`, `SCHEMA_COMPARISON.md`, `openapi.yaml` |
| Activity Analysis.dc.html | `frontend/src/screens/ActivityAnalysisScreen.tsx`, `screens/activity-analysis/*` (StatsTab, EnvironmentCard, HeatStrainCard, HydrationBlock, ThresholdHistoryIndicator, MultisportLegs) |
| Dashboard.dc.html (refreshed) | `screens/DashboardScreen.tsx`, `screens/dashboard/*`, `layout/*` |
| ThresholdHistory.dc.html (refreshed) | `screens/ThresholdHistoryScreen.tsx`, `screens/ThresholdHistoryChart.tsx`, `lib/thresholdFields.ts` |
| Activities.dc.html | `screens/ActivitiesScreen.tsx`, `screens/activities/*`, `components/LinkedActivityRow.tsx` |
| Best Efforts.dc.html | `screens/BestEffortsScreen.tsx` |
| Calendar.dc.html | `screens/CalendarScreen.tsx`, `screens/calendar/*` |
| Scheduled Workout.dc.html | `screens/ScheduledWorkoutScreen.tsx` |
| Gear.dc.html (refreshed) | `screens/GearScreen.tsx`, `screens/gear/*`, `lib/gear.ts` |
| Preferences.dc.html | `screens/PreferencesScreen.tsx`, `screens/preferences/*` |
| Admin.dc.html | `screens/AdminScreen.tsx`, `screens/admin/ShoeCatalogTab.tsx` |
| Import.dc.html | `screens/ImportScreen.tsx`, `screens/import/*` |
| Workout Library.dc.html (refreshed) | `screens/workouts/WorkoutLibraryScreen.tsx` |
| API Reference.dc.html | `openapi.yaml` (not yet re-synced this pass) |
| Dashboard.dc.html | `backend/*`, CHANGES.md "Dashboard redesign" + "HR zone distribution" (not yet re-synced) |
| Gear.dc.html | CHANGES.md "Gear: retired shoes / role" (not yet re-synced) |
| Workout Designer.dc.html | `CHANGES-workout-builder.md` (workout_step schema + Build mode) |
| Workout Library.dc.html | `CHANGES-workout-builder.md` §4-5 |
