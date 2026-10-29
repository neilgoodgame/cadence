# Activity Analysis: original mock vs live app

- Original mock: `Activity Analysis.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Activity Analysis.dc.html` (shell changes plus a `matched` prop; the page content is otherwise unchanged)
- Live source: `frontend/src/screens/ActivityAnalysisScreen.tsx` and `activity-analysis/*` (about 5,000 lines). **None of it was read**; this comparison is from the live pages' text and full-page screenshots.
- Screenshots (all full page, 1380px wide):
  - Live: `activity-analysis-run-workout-live-1380.png` (Trail Long Run — 16km Z2 + 18km MP + 1km CD, matched), `activity-analysis-run-no-workout-live-1380.png` (Run on 2026-10-18, no workout, linked to a race), `activity-analysis-bike-workout-live-1380.png` (Easy Bike — 60min Zone 2, indoor, matched), `activity-analysis-bike-no-workout-live-1380.png` (Bike on 2026-10-10, indoor, no workout)
  - Mock (ride is the default; run is `sport = run`; "no workout" is `matched` off): `activity-analysis-bike-workout-design-updated-1380.png`, `activity-analysis-bike-no-workout-design-updated-1380.png`, `activity-analysis-run-workout-design-updated-1380.png`, `activity-analysis-run-no-workout-design-updated-1380.png`

This screen needs a dedicated pass. The mock has several variants (a `variant`
editor prop: standard, duplicate, multisport and others) that were not compared
at all. The list below is from the page text only and was not checked
pixel by pixel.

## Page shell

Sidebar (Activities active), bell, theme toggle, `isAdmin` flag, `coachAccount`
editor prop (default off) and profile chip FTP 260 · LTHR 154 were updated as
on every screen (see "Page shell" in `Dashboard.differences.md`).

## Activity types covered

| Live activity | Sport | Workout | Notes |
|---|---|---|---|
| Trail Long Run — 16km Z2 + 18km MP + 1km CD | Run | Matched (99% compliant) | Outdoor; map, elevation sections (5), decoupling not scored (too intense), durability, a coach comment |
| Run on 2026-10-18 | Run | None | Linked to a race ("🏁 Yorkshire Marathon 2026 3:20:53 Unlink"); no Matched card, no tags; 31 elevation sections; durability |
| Easy Bike — 60min Zone 2 | Ride (indoor) | Matched (61% compliant) | "Indoor activity · no GPS" instead of a map; hydration block; "+ Add temperature & humidity"; decoupling scored (1.6%, Good) |
| Bike on 2026-10-10 | Ride (indoor) | None | Same indoor layout, no Matched card; temperature and humidity present; a coach comment |

What changes with the workout: the Matched card ("Matched {workout} · {n}%
compliant", **Unlink**) and "Other activities matched to this workout" appear
only for matched activities. With no workout there is nothing in their place.
A race link row appears under the title when the activity is linked to a race;
otherwise "+ Mark as race" shows.

The live local data has **no outdoor ride with GPS**; both rides are indoor.
Elevation, ascent and the map are therefore not shown for rides above.

## Mock coverage

The mock's default state is a virtual ride matched to a workout. It has three
editor props that change what the page shows:

- **`sport`** (bike by default, or run): a run sample built from the live "Trail Long Run — 16km Z2 + 18km MP + 1km CD". For a run the header (name, a Run badge and a runner icon, date / device line, the live account's six tags), the metric strip (35.01 km, 3:00:01, 244 w, 246 w, 0.88, 232 TSS, 130 bpm, 45 m), the Matched card (99% compliant), "other activities matched" (21 Sep and 24 Aug), top efforts (pace: 30 km, Half marathon, 10 mile, +1 more), duplicate recordings (a same-day run candidate), the map weather label, the training effect values, the Stats tab (power, heart rate, **pace**-led speed group, elevation, environment) and the comment thread all switch to the run's values. The chart's distance axis ends at 35 km. The ride-only panels (time in power zones, hydration, the revealed-FTP banner) are hidden for a run, as on the live page.
- **`matched`** (on by default): hides the Matched card and the linked list. An **Unlink** control was added to the Matched card.
- `variant` (standard, duplicate, multisport, multisport-leg) and `indoor` were already there and were not compared.

Placeholders in the run sample: the fourth top effort (shown behind "+1 more
effort" on the live card, so its metric was not visible; the mock uses a 10 km
49:30) and the Training effect "Anaerobic" label (the live strip shows none).
The run's route line is the mock's generated route, not the real trail.

## Sections on the live page, top to bottom

Header (name with ✎ rename and ✕ delete, tag chips with × and "+ Tag", sport
badge, date and device line, **Export**, **Share**, **+ Mark as race**) · stat
row (Distance, Moving time, Avg power, Norm. power, Intensity, TSS with ↺
recompute, Avg HR, Ascent, **Aerobic TE** with ✎ and a label, **Anaerobic TE**)
· Matched workout card with compliance and **Unlink** · Other activities matched
to this workout · Top efforts · Duplicate recordings · route map with Power /
Heart Rate / Cadence charts (map attribution, "16.2°C · 51% RH") · **Elevation
sections** card (Descent / Flat / Climb chart, "Adjust in Preferences ›
Courses", section list with "Show all 5 sections") · tabs **Stats / Laps / Zones
/ Curves** with ↺ Recompute stats · Stats grid · Environment · **Aerobic
decoupling** card · **Durability · best power once tired** card · Comments.

## Differences seen (mock vs live)

| Area | Original mock | Live app |
|---|---|---|
| Elevation sections card | Absent | Present (new feature; see Preferences › Courses) |
| Aerobic decoupling card | Absent | Present: "Not scored — too intense (IF 0.88, limit 0.85)", with a checklist of VI, IF, steady minutes and HR / power coverage |
| Durability card | Absent | Present: "Durability · best power once tired", "Durability trends →" link, After 60 / 90 min rows |
| Header actions | Not found in the mock's text | Export, Share and "+ Mark as race" buttons; ✎ rename and ✕ delete next to the title |
| Training effect / Time in power zones panel | Present beside the map in the mock | Not present on the live page (zones live under the Zones tab) |
| Chart set | Power, Heart Rate, Cadence, **Elevation** | Power, Heart Rate, Cadence |
| Revealed-FTP banner | Always shown in the mock's text ("Importing this activity revealed an earlier effort as your current FTP") | Not present on this live activity; may be conditional |
| Hydration block | Present (sweat rate, body weight lost, fluids) | Not present on this activity (likely only when hydration data exists; `HydrationBlock.tsx` exists) |
| Duplicate recordings | "Make primary" / "Unlink" on a linked duplicate, "Link as duplicate" on a candidate | "Link as duplicate" on a candidate shown |
| Comments | Coach and athlete threads, "Posting as Mara (coach)" | AI coach comment with "COACH" tag, Reply, Add a comment… / Post |

None of these rows were applied to the mock.

## Sample data

Left as the mock's own: "Big Loop in Watopia" (a virtual ride, Sweet Spot 3×12
match). The live activity in the screenshot is a 35 km trail long run.

## Live app changes since 21 Oct 2026 (refreshed 29 Oct)

The live screenshots were retaken at 1380px (Teal) against `main` at 9158def with the local backend rebuilt from the same commit. **The mock itself has not been updated for these changes.**

- The header now has the gear link in the title row: "+ Assign shoes" on runs and "+ Assign bike" on rides (#461). When gear is assigned it shows its mileage; no local activity has gear, so only the unassigned state was captured.
- All four live screenshots retaken: `activity-analysis-{run,bike}-{workout,no-workout}-live-1380.png`. Same activities as before; the page heights changed slightly with the header row.
