# Handoff: Fitness Viewer

## Overview
**Fitness Viewer** ("Cadence") is a multi-sport training analytics workspace for endurance athletes and coaches. It ingests activity files (`.fit` / `.gpx` / `.tcx`, single or bulk `.zip`) from sources like Garmin Connect and Zwift, parses them into a normalized data model, and surfaces deep analysis: power/HR duration curves, time-in-zone, training load (PMC: fitness/fatigue/form), best efforts, hydration (sweat rate / % weight lost), gear wear, and a planning layer (designed workouts, scheduling, auto-matching completed activities to plans). It is **multi-user**: one account can both train and coach, grant other users Viewer or Coach access, and a coach sees their athletes' recent activity.

## About the Design Files
The files in this bundle are **design references created in HTML** — prototypes showing intended look, layout, and behavior. They are **not production code to copy directly**. The task is to **recreate these designs in the target codebase's environment** using its established patterns and libraries. If no codebase exists yet, choose an appropriate stack (a React + TypeScript SPA against a REST API backend is the assumed target — see Tech Stack) and implement there.

Each design file is a self-contained `.dc.html` prototype. Open any of them in a browser to see the intended result. They share a token-driven theming system (three modes: **Teal**, **Violet**, **Day**) and a common sidebar/topbar shell.

## Fidelity
**High-fidelity.** These are pixel-level mockups with final colors, typography, spacing, component styling, and working interactions (filtering, querying, tab switching, modals, theme toggle). Recreate the UI faithfully using the codebase's component library, but match the visual tokens (below) exactly.

## Tech Stack (intended)
- **Frontend**: React + TypeScript SPA. Let React own the DOM.
- **Data visualization**: **D3** for the bespoke charts — PMC (dual-axis fitness/fatigue/form), log-scale power & HR mean-maximal duration curves, time-in-zone bars, hydration trends. In React, use D3 for **scale & shape math only** (`d3-scale`, `d3-shape`, `d3-array` — e.g. `d3.scaleLog()`, `d3.line()`, `d3.area()`) and render the resulting `<path>`/`<rect>` as JSX. Do **not** use `d3.select()` to mutate nodes React controls. Simple sparklines/small bars can stay plain SVG/CSS.
- **Backend**: REST API — see `openapi.yaml` (OpenAPI 3.1, ~63 operations). JSON over HTTPS, OAuth 2.0 + scoped JWT (delegation via `sub` + `athlete_id`), cursor pagination, a JQL-style `q` query language on `/v1/activities`, async upload jobs with polling + webhooks.
- **Persistence**: normalized relational DB via an ORM. The object/entity layer is documented in the Data Dictionary and ER diagram (`Data Schema.dc.html`). Entities: `user`, `user_relationship`, `zone_set`, `activity`, `lap`, `record` (1 Hz stream), `duration_curve`, `tag`, `activity_tag`, `best_effort`, `workout`, `workout_step`, `scheduled_workout`, `bike`, `component`, `shoe` (+ `shoe_model` → `shoe_model_version` catalog), `webhook`.

## Key Domain Concepts
- **Multi-role users**: a single `user` row carries the athlete profile plus `is_coach`. `user_relationship` (owner → grantee, role `viewer|coach`, status `pending|active`) grants one user access to another's data; `coach` role additionally allows scheduling/assigning workouts. Same table powers both "share my data" and "coach me".
- **Indoor vs outdoor**: `activity.environment` (`outdoor|indoor`) + `has_gps`. Indoor/treadmill activities omit GPS streams; the analysis screen hides the route map and shows an indoor panel instead.
- **Hydration**: optional `start_weight_kg` (defaults to the user's profile weight), `end_weight_kg`, `fluids_ml` → derive **sweat rate** (L/hr) and **% body-weight lost**.
- **Workout matching**: completed activities link to designed workouts (`activity.workout_id`) automatically (lap-structure inference) or manually; auto-matched activities are auto-tagged. `scheduled_workout.activity_id` resolves a planned session to what was actually done.
- **w/kg everywhere** derives from the profile `weight`.

## Screens / Views
All screens share: a 228px left sidebar (brand + nav + appearance toggle), a sticky top bar (title + actions + notifications bell + profile/Preferences chip + context switcher), and the gradient canvas. Athlete-facing nav: Dashboard, Activities, Analysis, Calendar, Workouts, Import, Gear, Notifications.

- **Login** — split layout; brand panel + auth form. Sign in / Create account tabs; social providers **Strava, Google, Apple**; email/password.
- **Dashboard** — greeting, stat row, PMC training-load chart, volume bars, time-in-zone distribution, Best efforts (power/HR/pace, selectable over 3 months / 1 year / all-time, includes running power), recent activities. In **coach context**: a "Your athletes" feed (coached athletes' recent activities + compliance) and a context switcher in the top bar.
- **Activities** — list with **advanced search** (JQL/CQL-style: field comparisons, AND/OR, ORDER BY, e.g. `runs tagged race and distance > 10km ordered by tss`), sort control, type/tag filter chips, tag chips (manual + auto), indoor badges. Second tab **Best efforts** (cycling mean-maximal power curve + table; running best times & power by distance).
- **Activity Analysis** — single-activity deep dive. Tabs (Stats / Curves). Route map (outdoor) OR indoor "no GPS" panel; lap table; time-in-zone; power & HR **duration curves** (extend to full length when activity > 60 min); hydration (sweat rate, % weight lost); editable tags; **coach comment thread** (coach/athlete avatars, role badges, composer).
- **Calendar** — month grid, planned vs completed sessions, vertically aligned day columns, weekly summary column, type/tag filter, **schedule-workout modal** (pick designed workout → drop on day → time of day → assignee).
- **Workout Designer** — build structured workouts (steps: time / distance / manual end types), interval profile chart, link/match to activities, **Export modal** (Zwift `.zwo`, Garmin `.tcx` — live preview, copy, download), Send to calendar.
- **Import** — expects a `.fit` (or bulk `.zip`); upload modal with optional fields (start/end weight, fluids, shoe for runs). Async processing.
- **Gear** — bike garage (component wear bars, service-due alerts, maintenance history), run shoes (manufacturer / model / version / colourway, mileage vs limit, optional image), shoe catalog picker.
- **Notifications** — grouped feed (Today / This week / Earlier), filter tabs (All / Coaching / Gear / Achievements), unread dots + counts, mark-all-read, deep links.
- **Preferences** (modal, shared across screens) — Profile (name, age, **weight**, FTP, critical run power, threshold pace, LTHR, max HR), editable zone sets (HR / bike power / run power / pace — recompute from thresholds), **Sharing** (invite a coach; grant others Viewer/Coach), **API tokens** (personal access tokens: create with name/scopes/expiry, show-once secret, rotate, revoke).
- **Coach Roster / Athlete** — coach's view of their athletes (compliance, form, next workout).
- **Data Schema** — ER diagram + Data Dictionary (reference, not an app screen).
- **API Reference** — browsable docs for the REST API (reference, not an app screen).

## Design Tokens
The app is fully token-driven via CSS custom properties. Three themes:

**Teal (primary dark)**
- canvas: radial gradient — `rgba(150,42,108,0.30)` top-right + `rgba(18,134,122,0.32)` bottom-left over `#080b11`
- card `rgba(15,24,33,0.60)`, elev `rgba(24,35,46,0.50)`
- ink `#eaf2f4`, ink2 `#93a3b0`, ink3 `#5c6b78`, line `rgba(255,255,255,0.08)`
- accent (ember) `#2dd4bf`, accentSoft `rgba(45,212,191,0.13)`

**Violet (alt dark)**
- canvas: `rgba(168,85,247,0.28)` + `rgba(20,120,120,0.24)` over `#07080d`
- card `rgba(18,19,31,0.62)`, ink `#eef0f6`, accent `#c084fc`

**Day (light)**
- canvas: `rgba(192,132,252,0.10)` + `rgba(13,148,136,0.12)` over `#f3f6f8`
- card `#ffffff`, elev `#f2f6f7`, ink `#0e1820`, ink2 `#51606b`, ink3 `#8a97a1`, line `rgba(14,24,32,0.10)`, accent `#0d9488`

**Data-viz palette** (sports & zones): ride `#3d7fd6`, run `#ec4a26`, swim `#2fa66a`, walk `#8b95a1`; zone ramp recovery→neuromuscular grey→blue→green→amber→orange→red→purple; coach `#c084fc`.

**Typography**
- UI/headings: **Hanken Grotesk** (400–800). Display headings 800, tight letter-spacing (~-0.02em).
- Numbers/labels/code: **JetBrains Mono** (400–700). Uppercase eyebrow labels ~10–11px, letter-spacing ~0.06–0.09em.

**Shape & spacing**
- Card radius 12–18px; chips/buttons 7–9px; pills 20px.
- Hairline borders use `--line`. Cards are translucent "glass" over the gradient (dark themes).
- Sidebar 228px; content padding ~22–32px; card padding ~16–24px.

## Interactions & Behavior
- Theme toggle (Teal / Violet / Day) persisted per screen.
- Activities advanced search parses natural-ish queries into filter chips + match count; composes with sort and tag/sport filters; `ORDER BY` in the query overrides the sort control.
- Modals (schedule, export, upload, preferences) use a dim backdrop + click-outside to close; export shows live file preview with copy/download.
- Duration curves conditionally extend past 60 min; route map conditional on `has_gps`.
- Notifications: unread dots; mark-all-read; filter tabs with per-filter unread counts.

## Files
Design prototypes (in this bundle):
- `Login.dc.html`, `Dashboard.dc.html`, `Activities.dc.html`, `Activity Analysis.dc.html`, `Calendar.dc.html`, `Workout Designer.dc.html`, `Import.dc.html`, `Gear.dc.html`, `Notifications.dc.html`, `Preferences.dc.html`, `Coach Roster.dc.html`, `Athlete.dc.html`
- `Data Schema.dc.html` — ER diagram + Data Dictionary (the ORM/entity layer)
- `API Reference.dc.html` — REST API docs (human-browsable)
- `Design Brief.dc.html` — product/design overview
- `openapi.yaml` — machine-readable API spec (OpenAPI 3.1)

These `.dc.html` files are self-contained; open in a browser to view. They reference a small runtime (`support.js`) — included for completeness, but it is **not** part of the target implementation.
