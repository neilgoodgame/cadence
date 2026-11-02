# Best Efforts: original mock vs live app

- Original mock: `Best Efforts.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Best Efforts.dc.html`
- Live source: `frontend/src/screens/BestEffortsScreen.tsx`, `screens/bestEfforts/*`
- Screenshots: `screenshots/best-efforts-live-1380.png`, `screenshots/best-efforts-design-updated-1380.png`, `screenshots/best-efforts-durability-live-1380.png`

Checked: Teal theme; the Records view visually and the page header, tabs and
card sections against `BestEffortsScreen.tsx` (lines 1020-1152) and its
`segStyle`; the Durability view visually and its `DurabilityView.tsx`.
Not checked line by line: the individual card components inside
`BestEffortsScreen.tsx` (RunPaceCard, BikePowerCard, LongestRidesCard, ...).
Violet/Day not compared.

## Page shell (same on every screen)

See "Page shell" in `Dashboard.differences.md`. Applied here: plain sidebar
with Races and Admin and no icons, inert solid-border bell, `6px 12px` toggle
segments, `coachAccount` editor prop (default off), profile chip FTP 260 ·
LTHR 154.

## Screen-specific differences

| Area | Original mock | Live app |
|---|---|---|
| View switch | None | **Records / Durability** segmented control in the header, left of the period tabs. The view is stored in the URL (`?view=durability`) |
| Header subtitle | Always "Personal bests across running and cycling · {period}" | On Durability: "Aerobic decoupling, efficiency and best power once tired · {period}" |
| **Durability view** | Not designed | A whole second view, not mocked: a trend chart of aerobic decoupling or efficiency factor (metric switch Decoupling / Efficiency, sport switch All / Ride / Run, legend for Ride, Run, Hot session, 28-day average), then "Cycling · best power after work done" and "Running · best power after time running" tables with Fresh / After 1,000 kJ / 2,000 / 3,000 columns (After 60 / 90 min for running) |
| Sticky header offset | `top: 63px` (sits under the app header) | `top: 0` with `z-index: 5`, below the app header's `z-index: 10` |

The updated mock adds the Records / Durability control (Records active). The
Durability tab is inert and its tooltip points at the live screenshot. Designing
the Durability view is left open.

Looks unintentional in the app (not copied): the page bar sticks at `top: 0`
but the app header is also sticky at `top: 0` with a higher z-index, so by the
code the page bar should slide under the app header while scrolling. This was
not confirmed by scrolling in a browser.

## Sample data

Left as generated. The mock builds its tables from formulas (names such as
"Threshold 3×10", dates in September 2026), so it does not mirror the live
account's records. The live screen shows real efforts, for example 1 km best
4:06 and 5 s power 531 W. The date format in the mock ("Sep 24, 2026") matches
`toLocaleDateString` for an en-US browser; the app follows the browser locale
(this capture was en-GB: "13 Jul 2026"), so it is not a design difference.
