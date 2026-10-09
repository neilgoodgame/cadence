import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateShoe } from "../../api/gear";
import { Card } from "../../components/Card";
import type { Shoe } from "../../api/types";
import { WEAR_STATUS_COLOR, wearStatus } from "../../lib/gear";
import { tableStyle, tdStyle, thStyle } from "./tableStyle";

function ShoeRow({ shoe }: { shoe: Shoe }) {
  const queryClient = useQueryClient();
  const retireMutation = useMutation({
    mutationFn: () => updateShoe(shoe.id, { retired: true }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["shoes"] }),
  });

  const status = wearStatus(shoe.km, shoe.limit_km);
  const pct = shoe.limit_km > 0 ? Math.round(Math.min(shoe.km / shoe.limit_km, 1) * 100) : 0;

  return (
    <tr>
      <td style={{ ...tdStyle, color: "var(--ink)", fontWeight: 700 }}>{shoe.name}</td>
      <td style={tdStyle}>{shoe.role ?? ""}</td>
      <td className="mono" style={tdStyle}>
        {shoe.km} km
      </td>
      <td className="mono" style={tdStyle}>
        {shoe.limit_km} km
      </td>
      <td className="mono" style={tdStyle}>
        {pct}%
      </td>
      <td style={tdStyle}>
        <span
          style={{
            fontSize: 11,
            fontWeight: 600,
            padding: "2px 8px",
            borderRadius: 20,
            color: WEAR_STATUS_COLOR[status],
            background: `${WEAR_STATUS_COLOR[status]}22`,
          }}
        >
          {status === "good" ? "In rotation" : status === "soon" ? "Near limit" : "Retire now"}
        </span>
      </td>
      <td style={tdStyle}>
        <button
          onClick={() => retireMutation.mutate()}
          disabled={retireMutation.isPending}
          style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12, fontWeight: 600, padding: 0, cursor: "pointer" }}
        >
          Retire
        </button>
      </td>
    </tr>
  );
}

export function ShoesTable({ shoes }: { shoes: Shoe[] }) {
  return (
    <Card style={{ padding: 0, overflowX: "auto" }}>
      <table style={tableStyle}>
        <thead>
          <tr>
            <th style={thStyle}>Name</th>
            <th style={thStyle}>Role</th>
            <th style={thStyle}>Distance</th>
            <th style={thStyle}>Limit</th>
            <th style={thStyle}>Worn</th>
            <th style={thStyle}>Status</th>
            <th style={thStyle} />
          </tr>
        </thead>
        <tbody>
          {shoes.map((shoe) => (
            <ShoeRow key={shoe.id} shoe={shoe} />
          ))}
        </tbody>
      </table>
    </Card>
  );
}
