# Import: original mock vs live app

- Original mock: `Import.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Import.dc.html`
- Live source: `frontend/src/screens/ImportScreen.tsx` (lines 90-291 read); `import/BatchStatus.tsx`, `import/UploadStatus.tsx` not read
- Screenshots: `screenshots/import-live-1380.png`, `screenshots/import-design-updated-1380.png` (idle state only)

Checked: Teal theme, the idle page and the component's template against the
mock's template. Not checked: the in-progress, batch-result and error states
against `UploadStatus` / `BatchStatus`, drag-over styling in a browser,
Violet/Day.

## Page shell (same on every screen)

See "Page shell" in `Dashboard.differences.md`. Applied here: plain sidebar
with Races and Admin and no icons, inert solid-border bell, `6px 12px` toggle
segments, `coachAccount` editor prop (default off), profile chip FTP 260 ·
LTHR 154.

## Screen-specific differences

None found in the page content. The mock already matched the live layout:
"Import an activity" / "Import activities" title (zip), the dashed drop zone
with the "Also accepts .tcx, .gpx, and .zip (bulk import)" hint, the optional
session-details grid (weight before and after, fluids, shoes), the Save & import
/ Upload archive button, and the Import history block with Clear import history,
its confirm step and cleared count.

| Area | Original mock | Live app |
|---|---|---|
| Drop zone | Clicking cycles through sample states (none, .fit, .zip) | Opens the file picker; accepts `.fit`, `.gpx`, `.tcx`, `.zip` and drag and drop |
| Drag-over | Not drawn | Border and fill switch to ember / `--ember-soft` |
| Error line | Not drawn | A red `#e0442e` message under the button when the upload fails |

These are behaviour differences, not layout ones, so the mock is unchanged.

## Sample data

Sample file names (for example "2026-09-26-parkrun.fit") are illustrative and
were left as is. The live page has no data on first load.
