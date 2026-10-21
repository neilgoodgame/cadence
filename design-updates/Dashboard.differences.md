# Dashboard: original mock vs live app

- Original mock: `Dashboard.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Dashboard.dc.html`
- Live source: `frontend/src/screens/DashboardScreen.tsx`, `screens/dashboard/*`, `layout/AppShell.tsx`, `layout/ThemeToggle.tsx`
- Screenshots (`design-updates/screenshots/`): `dashboard-live-1380.png`, `dashboard-design-updated-1380.png`

Both screenshots are full-page at a 1380px-wide viewport, which is the mock's
declared preview width. Live data is the local dev account on 21 Oct 2026.

The updated mock was changed to match the live app, except where a section
below says "not copied".

Checked: Teal theme, a non-coach admin account, visually in Safari, and
against the React source for `DashboardScreen`, `AppShell`, `ThemeToggle`,
`WeekCalendar` (incl. `WeekHrDistribution`), `UpcomingWorkoutsCard`,
`StatCardsRow`, `NextRaceCard`, `TrainingHistory`.
Not checked in code: `TopEffortsCard`, `ThresholdSummaryCard`, `CoachingSection`,
`ProfileChip`, `TrainingContextSwitcher`, `EmailVerificationBanner` (matched by
eye only, or not visible with this account). Violet and Day themes not compared.

## Page shell

| Area | Original mock | Live app |
|---|---|---|
| Sidebar background | `var(--card)` fill, sticky, `height: 100vh` | No fill, not sticky; stretches to page height, so theme toggle and Log out sit at the bottom of the whole page |
| Sidebar padding | `22px` brand, `12px` nav | `24px` all round |
| Brand mark | 26px square with 11px white inner square, 11px gap | 24px plain square, 10px gap, 32px below |
| Nav items | Icon + label; active = `--emberSoft` fill and ember text; `border-radius: 9px`; weight 500 inactive | No icons (except Races); active = `--elev` fill and `--ink` text; `border-radius: 8px`; weight 600 for all |
| Nav list | Dashboard, Activities, Best efforts, Import, Calendar, Workouts, Gear, Preferences | Adds **Races** (flag icon) after Calendar, and **Admin** last (admin accounts only) |
| Theme toggle | Segments `flex: 1`, `padding: 6px 0`, active = card fill with shadow | Segments `padding: 6px 12px`, active = `--elev` fill, no shadow |
| Notifications bell | Dashed border, links to `Notifications.dc.html`, "Proposed" tooltip | Solid border, `--ink3` icon, inert `aria-hidden` div |
| Greeting | "Welcome back, {name}." h1 above the cards | None |
| Coach switcher and "Your athletes" | Always shown (`isCoach: true`) | Coach accounts only. Now an editor prop `coachAccount`, default off |

## Cards, in page order

| # | Original mock | Live app |
|---|---|---|
| 1 | This week | Same, but the heart-rate zones are **not** inside this card |
| 2 | Top efforts this week | Same |
| 3 | (heart-rate zones were inside card 1) | **Heart rate zones is its own card** here, between Top efforts and Next race. It is hidden when the week has no HR activities or no HR zone set, and shows "Loading…" while streams load |
| 4 | Next race | Same. Hidden entirely when there is no upcoming race. On race day shows a flag emoji and no weeks label |
| 5 | Upcoming workouts | Same, plus an empty state: "No workouts scheduled — plan one in the Workout Designer." |
| 6 | Stat row | Same |
| 7 | Thresholds | Same (by eye) |
| 8 | 16-week block | Same |
| 9 | Your athletes (coach) | Same, coach accounts only |

## Detail differences

- **This week stats:** the bike group (Bike distance, Rides, Avg power) only appears when the week contains a ride. The original mock always showed it.
- **Stat-row deltas:** the app prints plain ASCII hyphen-minus ("-2.8 CTL"); the mock used a true minus sign (U+2212).
- **Heart-rate zone colours:** the app uses `--zone-1`..`--zone-5` CSS variables; the mock hard-codes hex values. The mock's hex values look the same on screen.
- **Upcoming workout rows:** unchanged in style. The mock's sample rows are now behind an editor prop `upcomingScheduled` (default off), so the default render matches the live empty state.

## Sample data

The mock states that its data mirrors the live app. It was last aligned to
Wed 29 Jul 2026. Re-anchored to Wed 21 Oct 2026:

- `today`, this week's stats, the seven day columns and their activities
- Top efforts (6 efforts across 2 activities)
- Heart-rate zone times and ranges
- Next race: Sevilla Marathon, 2027-02-21, 42.195 km, goal 3:20:00
- Stat row: 117 / 66 / 52 / 451 TSS
- FTP 260 W (+35W, 96 days left), LTHR 154; profile chip "FTP 260 · LTHR 154"
- 16-week block: real weekly run distances and the per-week averages

Still sample values, not read from the live screen: critical running power,
threshold pace, the Run time / Activity time / TSS bar series, and the coach
athletes list.

## Live behaviour that looks unintentional (not copied into the mock)

Decide whether the app or the mock should change:

1. **Uneven week columns.** The app uses `repeat(7, 1fr)`, so a long activity name widens its column and squeezes the others (Thu and Sat are wide, Fri and Mon-Wed narrow). The mock uses `repeat(7, minmax(0, 1fr))` with truncated names, giving equal columns.
2. **Effort label wrapping.** In a day block the label beside the rank badge (for example "30 km pace +3") wraps onto a second line in the app. The mock keeps it on one line with an ellipsis.
3. **Week blocks are `div`s with `onClick`**, not links, so there is no middle-click or open-in-new-tab. The mock uses `<a>`.

## Not reflected in the mock

- Loading, error and empty states for most cards (the app shows nothing until data arrives).
- Email-verification banner copy and states were not compared.
