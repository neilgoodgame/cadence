# Screens with no mock

Four screens in the app have no `*.dc.html` mock. They were captured live only
(full page, 1380px wide, Teal theme) so they can be designed from. Nothing here
was compared against a design.

Capture notes: each image height was checked against the page's scroll height.
The Race Detail page has one inner scroll box (the Splits table, 520px tall,
723px of content); the capture was taken with it expanded so every row shows.

## Races (`/races`)

- Screenshot: `screenshots/races-live-1380.png`
- Title "Races" with "1 upcoming · 31 past · A races get a 16-week training block; B and C races sit inside them", filters All / Run / Bike / Multisport, and **+ Add race**.
- **Current block · next A race** card: Sevilla Marathon, "2027-02-21 · 42.195 km · Goal 3:20:00", a days-to-go number, FITNESS (6, "-33 since wk 1") and DISTANCE ("0 km", "12 wk planned").
- **Upcoming**: rows with date, an A / B / C grade badge, name and "123 days · block week 1 of 12".
- **Past races** grouped by year (2026, 2025, ...): tables with DATE, RACE (with grade badge), DISTANCE, GOAL, RESULT, VS GOAL and PACE; a missing value shows "–".
- Observed inconsistency: the block card says "122 days to go" while the Upcoming row and the Dashboard say 123.

## Race Detail (`/races/:raceId`)

- Screenshots: `screenshots/race-detail-completed-live-1380.png` (Yorkshire Marathon 2026, completed A race), `screenshots/race-detail-upcoming-live-1380.png` (Sevilla Marathon, upcoming A race)
- Header: "← All races", the race name, a status ("Completed" or "In 123 days"), the A / B / C badge, and **Share**, **Download report**, **Edit**, **Delete**; below it "date · sport · distance", a **Website ↗** link and, when linked, the activity.
- **Completed race**: tune-up races in this block (B and C races listed with grade); **Result** card (3:20:53, "+0:53 vs goal", pace, heart rate, power, temperature, humidity, positive or negative split, goal with an Edit link, "Predicted 3:21:39, actual 3:20:53"); **Race-day form** (CTL 133, ATL 79, TSB 54, "Very fresh"); **History at this distance** (a list with result and gap to PB); the **course** card (map with "Use ⌘ + scroll to zoom the map", Distance, Gain, Loss, Highest, Lowest, Climb/km, an elevation-sections chart and list with "Show all 28 sections", "Raced on this course" by year with a "Course best", **Download GPX / Change / Unlink**); **Splits** with Laps / 1 km / 5 km / Elevation tabs and a table (SPLIT, DIST, TIME, PACE, AVG HR, AVG POWER, GRADE, Δ).
- **Upcoming race**: **Goal** card (3:20:00, "4:44 /km · +1:02 vs PB", Edit); **Projected race-day form** ("Not enough scheduled yet."); History at this distance; "No course linked. **Link course**"; a **Pacing and fuelling plan** (Even / Negative split, carbs per hour 45 / 60 / 75 / 90 g, halves, carbs and gels, fluid, sodium, and a per-5 km table with target, pace, clock and fuel and drink); a **Training block · 12 weeks** card with dates, a compare race, All / Run / Ride and Load / Distance / Time switches, a weekly chart with Base / Build / Peak / Taper phases and a week-by-week panel.

## Public Race (`/public/races/:id`)

- Screenshot: `screenshots/public-race-live-1380.png`
- **Only the error state was captured.** The race used is not shared, so the page shows a minimal header ("Cadence", "Open Cadence") and "This race doesn't exist or isn't shared publicly."
- The shared view itself is not captured: showing it needs Share switched on for a race in the local database, which was not done without asking.

## Verify Email (`/verify-email`)

- Screenshot: `screenshots/verify-email-no-token-live-1380.png`
- Captured without a token: "Link invalid or expired", "This verification link is missing its token. Sign in and request a fresh link from your account.", and a **Go to sign in** button.
- The verifying and success states need a real token and were not captured.
