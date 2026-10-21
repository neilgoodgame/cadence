# Preferences: original mock vs live app

- Original mock: `Preferences.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Preferences.dc.html`
- Live source: `frontend/src/screens/PreferencesScreen.tsx`, `preferences/ProfileTab.tsx` (lines 225-441), `preferences/CoursesTab.tsx` (read)
- Screenshots: `screenshots/preferences-profile-live-1380.png`, `screenshots/preferences-profile-design-updated-1380.png`, `screenshots/preferences-courses-live-1380.png`, `screenshots/preferences-courses-design-updated-1380.png`

This is the largest screen: 13 tabs and about 3,000 lines of tab components.
Profile and Courses were compared in depth; the other eleven were compared by
visible text and full-page screenshots (see "Other tabs" below). The tab
components themselves were not read.

Checked: the tab list, Profile and Courses (text and values against the live
page and source), and every other tab by text and screenshot. **Not checked:**
the tab components' source (`WorkoutsTab`, `ZoneEditorTab`, `HrZoneGenerator`,
`BestEffortsTab`, `TagsTab`, `RacesTab`, `SharingTab`, `TokensTab`, `ExportTab`),
hover and error states, the Violet/Day themes.

## Page shell (same on every screen)

See "Page shell" in `Dashboard.differences.md`. Applied here: plain sidebar
with Races and Admin and no icons, inert solid-border bell, `6px 12px` toggle
segments, `coachAccount` editor prop (default off), profile chip FTP 260 ·
LTHR 154.

## Tab list

| Original mock | Live app |
|---|---|
| Profile, Workouts, Heart rate, Bike power, Run power, Pace, Best efforts, Tags, Races, Sharing, API tokens, Export data (12) | The same plus **Courses**, between Races and Sharing (13) |

## Profile tab

| Area | Original mock | Live app |
|---|---|---|
| Thresholds: expiry warning | Not present | "Expiry warning (days before a threshold's best effort ages out)" select: 14 days, 21 days (recommended), 28 days, Off, with a help paragraph about Suggestions on Thresholds & zones and the Dashboard |
| Aerobic decoupling group | Not present | VI limit - bike (1.06), VI limit - run (1.04), IF limit (0.85), Minimum steady time (60 min), Warm-up trim (5 min), a checkbox to trim a matched workout's own warmup step instead, and a help paragraph |
| Heat-confound flags group | Not present | Warm and Hot air / skin temperature thresholds (25, 33, 30, 34 °C) and a help paragraph |
| Gear group | Not present | "Default shoe wear limit (km)" (800) and a help paragraph; applies to new shoes and CSV imports |
| Field values | Age 34, FTP 265, critical run power 285, threshold pace 4:00, LTHR 155, max HR 186, resting HR 46, weight 72.0 | Age 52, FTP 260, 280 W, 4:35, LTHR 154, max HR 168, resting HR 44, weight 72 (the live account) |

The updated mock adds the four additions and uses the live values. The
checkbox and select are drawn statically.

## Courses tab (new)

Not in the original mock. The updated mock adds it as a static panel: an
intro line; Method (Douglas–Peucker selected, Bottom-up merge, with hover
help); five number fields (Flat band 1 ±%, Steep threshold 4 ±%, Fit tolerance
2 m, Minimum section length 250 m, "…unless they rise or drop at least" 5 m)
with a collapsed "▸ Advanced"; "My courses" with rename and delete rows; a
Preview course picker; Save changes and Reset to defaults; and "Rebuild course
elevation" with a "Rebuild all courses" button. Not drawn: the Advanced fields,
the Preview section chart (`CourseSectionsProfile`), the validation error line,
saved/rebuild result messages and the delete confirmation.

## Sample data

The Profile tab uses live values (above). Other tabs keep their original
sample data. Courses lists the live account's ten courses.

## Other tabs (compared by text and screenshot)

Screenshots are in `screenshots/` as `preferences-<tab>-live-1380.png` and
`preferences-<tab>-design-updated-1380.png`, full page. The live API tokens
screenshot is deliberately not kept: it shows the first characters of real
tokens. Its text was compared instead.

| Tab | Result | Differences (original mock vs live) |
|---|---|---|
| Workouts | Matches | None found: same text, same controls |
| Heart rate | **Differs** | Live has a method switch (**% of max HR / Karvonen / Coggan**) with a one-line explanation of the selected method ("Zones anchored to lactate threshold heart rate (68/83/94/105%)."), RESTING HR / LTHR / MAX HR inputs, a generated zone **preview table** (Zone, Range, % Max HR, Description for each of five zones) with an **Apply zones** button, and the heading "Recompute zones" with "Generate all five zones from your physiology. Applying updates your profile's LTHR and max HR too." Below that the editable zone table ("Reference threshold: 154 bpm", Low %, High %, Range) and Save zones. The mock has only "Generate a fresh 5-zone set from your heart-rate markers", three inputs, Generate, and the editable table |
| Bike power | Close | Same structure (Reference threshold, Low % / High % / Range, Save zones). Live: 260 w, ranges 0–143, 146–195, 198–234, 237–273, 276–390 (the last zone has an upper bound). Mock: 265 w, last zone open ("281+ w") |
| Run power | Close | Same structure. Live 280 w, last zone 297–420 w; mock 285 w, last zone "331+ w" |
| Pace | Close | Same structure. Live reference "275 /km" and ranges printed as raw seconds ("367–491 /km", "500+ /km"); mock prints minutes and seconds ("4:00 /km", "4:36–5:12 /km", "> 5:16 /km"). The live seconds format looks unintentional |
| Best efforts | **Differs** | Live adds: a "Store best efforts for every activity (no limit)" option, **Save & recompute all**, per-kind Recompute buttons (Running HR, Running Power, Running Pace, Cycling HR, Cycling Power) and Recompute all, a longer explanation under each section, a Threshold history block with **Window (days)** and **Sanity limit (%)** and a note that changing them is not retroactive, **Regenerate all from oldest** and per-field **Rebuild from oldest** (FTP, Critical running power, Threshold pace). The mock has Entries per window, Recompute best efforts, Recompute derived stats, Recompute duration curves and a single Threshold history window |
| Tags | Close | Same structure (explanation, Create, rows with count, Rename, Delete). Live lists 30 real tags; mock lists 5 sample tags |
| Races | **Differs** | Live is a pointer plus two settings: "Go to Races →" (races are added, edited and deleted from the Races page), **Training block length** by distance (5K, 10K, 10 Mile, Half Marathon, Marathon, Ultra, No distance set; options 12 / 16 / 20 / 24 weeks) and **Key sessions shown per race** (6 / 8 / 10 / 12 / 15 / 20). The mock is a race list (Upcoming and Past with Edit, Delete, Results links) and says "Add races from the Calendar view" |
| Sharing | Close | Same sections (My coaches, Invite someone, Add an AI coach, People with access; Viewer / Coach / Revoke). Live shows one coach (Claude.ai, AI, since 2026-08-25); mock shows three people including a Pending invite |
| API tokens | Close | Same sections (Generate token with scope checkboxes, Active tokens with Rotate / Revoke). Live has four tokens with their scopes; mock has two sample tokens |
| Export data | Close | Live adds an **Include profile & zone settings** option ("age, weight, FTP, LTHR, max/resting HR, and your zone percentages. Importing this file elsewhere will overwrite that account's own values with these."). Otherwise the same Export and Import sections |

None of these tab differences were applied to the mock yet.
