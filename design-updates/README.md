# design-updates

Updated copies of the design mocks (`*.dc.html` in the repo root), brought in
line with the live React app. The originals in the repo root are **unchanged**.

Each updated mock `Name.dc.html` has a `Name.differences.md` next to it that
records exactly how the original differed from the app, what was changed, and
what was not checked. Comparison screenshots are in `screenshots/`
(`<screen>-live-1380.png` is the live app, `<screen>-design-updated-1380.png`
the updated mock; both at a 1380px-wide viewport, Teal theme).

**The screenshots are on a separate branch**, `design-updates-screenshots`
(67 images, about 78 MB). The records here refer to them as
`screenshots/<name>.png`; merge or check out that branch to get them into
`design-updates/screenshots/`. The mocks and records do not need them to render.

The updated files load from this folder (`../support.js`, `../<Screen>.dc.html`
links), so keep it next to the originals.

Live data is the local dev account on 21 Oct 2026. The shell differences that
apply to every screen (sidebar, bell, theme toggle, coach switcher) are written
up once in `Dashboard.differences.md`.

## Status

| Screen | Status |
|---|---|
| Login | Done |
| Dashboard | Done (content, shell, data re-anchored) |
| Activities | Done |
| Best Efforts | Done for Records; the live **Durability** view is not mocked |
| Calendar | Done for layout; sample data not re-anchored |
| Gear | Done for Cards. The live **Table** view is captured (live only); the Table view, CSV import panel and photo timeline are not mocked |
| Import | Done (idle state) |
| Admin | Done (added CSV import card) |
| Preferences | Profile and the new **Courses** tab done. The other 11 tabs were captured and compared by text; their differences are recorded but **not applied** (Heart rate, Best efforts and Races differ most) |
| Workout Library | Done for grid view; list view and modals not compared |
| Workout Detail | Done (top bar, empty-state bugs) |
| Workout Comparison | Done (top bar, labels, two mock bugs fixed) |
| Scheduled Workout | Compared from source only (no planned workouts in the live data); small fix applied |
| ThresholdHistory | Done for FTP (live ledger table); Suggestions section not compared |
| Workout Designer | **Shell only.** The mock's layout differs substantially; see its differences file |
| Activity Analysis | **Shell, a `matched` prop and a `sport` prop (bike or run).** About 5,000 lines of live code were not read. Live and mock captures cover a run and a bike, each with and without a workout; the live-only cards (Elevation sections, Aerobic decoupling, Durability) are still not mocked. See its differences file |

## Not touched

- **Coach Roster, Athlete, Notifications**: proposed screens with no live route in the app.
- **API Reference, Data Dictionary, Data Schema, Design Brief**: reference pages, not UI screens, and not compared against `openapi.yaml` or the schema.

## Live screens with no mock

Captured live only; see `Screens without mocks.md`.

Races (`/races`), Race Detail (`/races/:raceId`), Public Race
(`/public/races/:id`) and Verify Email (`/verify-email`). The sidebar mocks
carry a "Races" item that links nowhere for this reason.

## Known gaps that affect every screen

- Only the Teal theme was compared; Violet and Day were not.
- Narrow viewports, hover and focus states were not compared.
- The Workout Library, Detail, Comparison and Designer mocks keep their own
  `5px 11px` theme-toggle padding (the app uses `6px 12px`) because the same
  style drives other controls there.

## How the screenshots were taken and checked

- Every image is a full-page capture at a 1380px-wide viewport.
- Each capture's height was checked against the page's scroll height (image height = scroll height x the screen scale); the long pages match.
- The live pages were swept for inner scroll boxes that a full-page capture would cut off. Only one was found: the Splits table on Race Detail, which was expanded for its capture. The Activities list loads 30 rows at a time, so it was scrolled to the end first.
- A scroll-and-join test on a long Preferences tab (three scrolled frames stitched) gave the same height as the full-page capture, with the same content.
- Not kept: a live API tokens capture (it shows the start of real tokens). Not captured: the shared public race page and the email-verification success states (see `Screens without mocks.md`).

## Refresh of 29 Oct 2026

Live screenshots retaken for Import, Preferences (Profile, Tags, new Garmin tab), Gear (cards and table), Activity Analysis (four samples), Workout Library, Workout Designer, and new ones for the Calendar schedule dialog and workout picker. Each affected `*.differences.md` has a section listing what changed. The mocks themselves were not updated and the existing `-design-updated` screenshots are from 21 Oct.
