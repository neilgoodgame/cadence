import { Card } from "../../components/Card";
import type { Bike } from "../../api/types";
import { GearActivitiesSection } from "./GearActivitiesSection";
import { tableStyle, tdStyle, thStyle } from "./tableStyle";

const COLUMN_COUNT = 7;

function BikeRow({ bike }: { bike: Bike }) {
  return (
    <>
      <tr>
        <td style={{ ...tdStyle, color: "var(--ink)", fontWeight: 700 }}>{bike.name}</td>
        <td style={tdStyle}>{bike.kind.toUpperCase()}</td>
        <td style={tdStyle}>{bike.groupset ?? ""}</td>
        <td className="mono" style={tdStyle}>
          {bike.distance_km} km
        </td>
        <td className="mono" style={tdStyle}>
          {bike.hours}h
        </td>
        <td className="mono" style={tdStyle}>
          {bike.rides}
        </td>
        <td className="mono" style={tdStyle}>
          {bike.components}
        </td>
      </tr>
      <tr>
        <td colSpan={COLUMN_COUNT} style={{ padding: "0 12px 10px", borderBottom: "1px solid var(--line)" }}>
          <GearActivitiesSection filter={{ bike_id: bike.id }} />
        </td>
      </tr>
    </>
  );
}

export function BikesTable({ bikes }: { bikes: Bike[] }) {
  return (
    <Card style={{ padding: 0, overflowX: "auto" }}>
      <table style={tableStyle}>
        <thead>
          <tr>
            <th style={thStyle}>Name</th>
            <th style={thStyle}>Type</th>
            <th style={thStyle}>Groupset</th>
            <th style={thStyle}>Distance</th>
            <th style={thStyle}>Hours</th>
            <th style={thStyle}>Rides</th>
            <th style={thStyle}>Components</th>
          </tr>
        </thead>
        <tbody>
          {bikes.map((bike) => (
            <BikeRow key={bike.id} bike={bike} />
          ))}
        </tbody>
      </table>
    </Card>
  );
}
