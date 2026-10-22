import { useQuery } from "@tanstack/react-query";
import { listShoes } from "../../api/gear";
import type { SessionDetailsDraft } from "./sessionDetails";

const inputStyle: React.CSSProperties = {
  width: "100%",
  padding: "10px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 14,
  color: "var(--ink)",
};

const labelStyle: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 };

export function SessionDetailsFields({
  value,
  onChange,
}: {
  value: SessionDetailsDraft;
  onChange: (next: SessionDetailsDraft) => void;
}) {
  const { data: shoes } = useQuery({ queryKey: ["shoes"], queryFn: listShoes });
  const set = (patch: Partial<SessionDetailsDraft>) => onChange({ ...value, ...patch });

  return (
    <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
      <label>
        <div style={labelStyle}>Weight before (kg)</div>
        <input
          type="number"
          step="0.1"
          className="mono"
          style={inputStyle}
          value={value.weightBefore}
          onChange={(e) => set({ weightBefore: e.target.value })}
          placeholder="72.4"
        />
      </label>
      <label>
        <div style={labelStyle}>Weight after (kg)</div>
        <input
          type="number"
          step="0.1"
          className="mono"
          style={inputStyle}
          value={value.weightAfter}
          onChange={(e) => set({ weightAfter: e.target.value })}
          placeholder="70.9"
        />
      </label>
      <label>
        <div style={labelStyle}>Fluids consumed (ml)</div>
        <input
          type="number"
          step="50"
          className="mono"
          style={inputStyle}
          value={value.fluids}
          onChange={(e) => set({ fluids: e.target.value })}
          placeholder="750"
        />
      </label>
      <label>
        <div style={labelStyle}>Shoes</div>
        <select style={inputStyle} value={value.shoeId} onChange={(e) => set({ shoeId: e.target.value })}>
          <option value="">None</option>
          {shoes?.data.map((shoe) => (
            <option key={shoe.id} value={shoe.id}>
              {shoe.name}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}
