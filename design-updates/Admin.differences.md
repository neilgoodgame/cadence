# Admin: original mock vs live app

- Original mock: `Admin.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Admin.dc.html`
- Live source: `frontend/src/screens/AdminScreen.tsx` (read), `admin/ShoeCatalogTab.tsx` (layout, import panels and CSV card read; table rows not read line by line)
- Screenshots: `screenshots/admin-live-1380.png`, `screenshots/admin-design-updated-1380.png` (full page)

Checked: Teal theme, an admin account, the page header, tab rail, catalog table
and both import cards, visually and against `AdminScreen.tsx` and the import
panels. Not checked: the CSV preview and result states in a browser,
Violet/Day.

## Page shell (same on every screen)

See "Page shell" in `Dashboard.differences.md`. Applied here: plain sidebar
(Admin active) with Races and no icons, inert solid-border bell, `6px 12px`
toggle segments, `coachAccount` editor prop (default off), profile chip FTP 260
· LTHR 154.

## Screen-specific differences

| Area | Original mock | Live app |
|---|---|---|
| Import from CSV card | Not designed | A second card under "Import shoe model": file input (`.csv`), help text ("Expects a header row with Manufacturer, Model and (optionally) Version columns - e.g. a Strava shoe-rotation export. ... re-importing the same file is safe."), then after a file is chosen a preview list ("{file}: N shoe entries found."), an "Import N entries" button, and a green result line ("Imported X new models and Y versions (Z already in the catalog, skipped)") |
| Right column | Single card | Column of two cards, 16px apart |
| Tab rail | Shoe catalog plus Users, Coach grants and Audit log marked PROPOSED | Only "Shoe catalog" (a single-member tab list in code) |
| "BY" column | Short names such as "neil", "mara" | The added-by user's name truncated (for example "Neil Good…"), or "system" |

The updated mock adds the Import from CSV card in its idle state. The preview
and result states are described above but not drawn. The PROPOSED tabs are
deliberate design placeholders and were left.

## Sample data

The mock lists six sample models. The live catalog is much longer (for
example Adidas Adizero Adios Pro, Boston, Evo SL, ASICS Gel-Kayano, Gel-Nimbus
...) and its "Ver." column uses the same "version(count)" format as the mock
("3(0)", "12(0)"). Mock rows were left as is.
