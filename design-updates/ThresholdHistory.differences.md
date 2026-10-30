# Thresholds & zones (ThresholdHistory): original mock vs live app

- Original mock: `ThresholdHistory.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/ThresholdHistory.dc.html`
- Live source: `frontend/src/screens/ThresholdHistoryScreen.tsx` (header lines and the ledger, lines 420-519, read), `thresholdHistory.ts` (read); `ThresholdHistoryChart.tsx`, `thresholdSuggestions.ts` not read
- Screenshots: `screenshots/threshold-history-live-1380.png` (live `/thresholds/ftp`), `screenshots/threshold-history-design-updated-1380.png`

Checked: Teal theme, FTP tab, page header, current-value card, zone table,
chart legend and the ledger, against the live page and the ledger source. Not
checked: the other three field tabs against the live page, the chart's drawing
and axis scale against `ThresholdHistoryChart.tsx`, the **Suggestions**
section (the live screen can show expiring-threshold suggestions with
accept / dismiss / undo and schedule / add-race modals; the live account had
none, and the mock was not compared for it), Violet/Day.

## Page shell

Sidebar and theme toggle changed as on every screen (see "Page shell" in
`Dashboard.differences.md`). No nav item is active here, because the page is
reached from the Dashboard's Thresholds card and has no sidebar entry.

This page also differed in its own shell:

| Area | Original mock | Live app |
|---|---|---|
| Top bar | None | The standard top bar (+ Import activity, bell, profile chip) |
| Page padding | `22px 32px 64px` | The app's `32px` |
| Heart rate tab label | "Heart rate (LTHR)" | "Heart rate" |

## Ledger

| Area | Original mock | Live app |
|---|---|---|
| Ledger | A list of cards: value, "Effective from {weekday, month d, yyyy}" and a "View activity" link (max width 560px) | A full-width table card titled "{FIELD} · LEDGER" with an entry count ("30 entries") and seven columns: **#**, **Value**, **Activity** (link to the source activity, or "—"), **Δ vs previous** (+35W, -30W; for pace "▼ 0:03"), **Effective from** (5 Oct 2026), **Days in effect** (a 56px bar relative to the longest span plus "16 days"), **Effort date** (a REVEALED badge and the earlier effort's date when a later ingest surfaced a dormant effort, else "—") |

The updated mock replaces the card list with this table and computes delta and
days in effect the same way as `thresholdHistory.ts` (days run to the next newer
entry, or to today for the first).

## Sample data

The FTP tab now uses the live account's 30 ledger entries (260 W from 5 Oct
2026 back to 0 W on 12 Mar 2022), with their activity names and the seven
REVEALED rows, so the current value, zone table "Before 5 Oct" column, chart
step line and ledger match the live page. Not matched:

- **Zone set:** the live FTP zones are five (Z1 Recovery, Z2 Endurance, Z3 Tempo, Z4 Threshold, Z5 VO2max, with "≥ 106%" as the last) because they come from the athlete's own zone set; the mock keeps its seven (Z1 Active Recovery … Z7 Neuromuscular).
- **Chart axis:** the mock's own scale prints -57 W … 371 W; the live chart prints 0 W … 300 W.
- Critical running power, Threshold pace and Heart rate tabs keep the mock's original sample entries.
