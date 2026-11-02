# Gear: original mock vs live app

- Original mock: `Gear.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Gear.dc.html`
- Live source: `frontend/src/screens/GearScreen.tsx`, `gear/ShoesSection.tsx`, `gear/ShoeCard.tsx` (read); `gear/BikesSection.tsx`, `BikeCard.tsx`, `BikesTable.tsx`, `ShoesTable.tsx`, `AddShoeForm.tsx`, `ImportShoesPanel.tsx`, `ShoePhotoTimeline.tsx` (not read)
- Screenshots: `screenshots/gear-live-1380.png`, `screenshots/gear-design-updated-1380.png`, `screenshots/gear-table-live-1380.png` (the live Table view)

Checked: Teal theme, the page header, the Run shoes section and shoe card,
visually and against the three files read. Not checked: the bike cards and
component rows against `BikeCard.tsx`, the add-shoe form steps, Violet/Day.

## Page shell (same on every screen)

See "Page shell" in `Dashboard.differences.md`. Applied here: plain sidebar
with Races and Admin and no icons, inert solid-border bell, `6px 12px` toggle
segments, `coachAccount` editor prop (default off), profile chip FTP 260 ·
LTHR 154.

## Screen-specific differences

| Area | Original mock | Live app |
|---|---|---|
| View switch | None | **Cards / Table** segmented control at the top right (choice is remembered in browser storage). Active segment is ember-filled |
| Table view | Not designed | `BikesTable` and `ShoesTable` render instead of cards. Not mocked |
| Run shoes header | Only "+ Add shoes" | "Set wear limit (N)" (only while some shoes have no wear limit; confirms, then applies the default limit from Preferences), "Import from CSV", "+ Add shoes". The label becomes "Cancel" while a panel is open |
| CSV import panel | Not designed | `ImportShoesPanel` opens above the grid. Not mocked |
| Shoe card actions | "Retire" only | "Photos" and "Retire". Photos expands a photo timeline inside the card (`ShoePhotoTimeline`). Not mocked |
| Shoe with no wear limit | Ring would divide by zero | Limit 0 shows a 0% empty ring, "N / 0 km" and "In rotation" |
| Empty bikes | Always shows sample bikes | "No bikes in the garage yet." when there are none |
| Empty shoes | Always shows sample shoes | "No shoes tracked yet." when there are none |

The updated mock adds the Cards / Table switch (Table inert), the two extra
shoe header buttons (inert except Add shoes), and the Photos link (inert). It
keeps the mock's sample bikes. The empty states are described here but not
drawn.

## Sample data

Shoes now start with the live account's first seven shoes (six with no wear
limit, one Nike Vaporfly 3 red at 0 / 600 km), so the header shows "Set wear
limit (6)". The three original sample shoes (Nike Vaporfly 3, ASICS Novablast 4,
Hoka Clifton 9) are kept after them as examples of the Near limit and Retire
now states, which the live account does not have. The live account has 24 shoes
and no bikes; the mock's two sample bikes and their components are unchanged.

## Table view (live capture only)

`screenshots/gear-table-live-1380.png` shows the live Table view of the 24 shoes.
Under "Run shoes" (same header buttons as the card view) one table has the
columns **Name, Role, Distance, Limit, Worn, Status** and a **Retire** button on
each row: for example "HOKA Mach 5 Red · 579 km · 0 km · 0% · In rotation ·
Retire". The Role column is empty for every shoe in this account.

There is no mock for the Table view, and no live capture of the bikes table: the
account has no bikes, so the live Bikes section shows "No bikes in the
garage yet." even in Table view. The mock still has cards only.

## Live app changes since 21 Oct 2026 (refreshed 29 Oct)

The live screenshots were retaken at 1380px (Teal) against `main` at 9158def with the local backend rebuilt from the same commit. **The mock itself has not been updated for these changes.**

- Cards and Table views now show mileage accrued from linked activities, and an Activities section per item (#460-#463). In the Table view each shoe row is followed by an "Activities" sub-row (empty for every local shoe, because the local activities are not linked to gear).
- Gear column headers in the table: Name, Role, Distance, Limit, Worn, Status.
- Local data has no bikes ("No bikes in the garage yet"), so bike cards and the bike table were not captured.
- Screenshots: `gear-live-1380.png`, `gear-table-live-1380.png`.
