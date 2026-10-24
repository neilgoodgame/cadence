import { useState, type CSSProperties } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { updateActivity } from "../../api/activities";
import { listBikes, listShoes } from "../../api/gear";
import type { Activity } from "../../api/types";

const selectStyle: CSSProperties = {
  fontSize: 13,
  padding: "3px 8px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  color: "var(--ink)",
};

const addButtonStyle: CSSProperties = {
  fontSize: 13,
  fontWeight: 600,
  padding: "6px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "none",
  color: "var(--ink2)",
  cursor: "pointer",
};

/** Lets the athlete link/unlink the bike (bike activities) or pair of shoes (run activities)
 * used for this activity - the gear library itself (/gear) is fully built elsewhere; this is
 * just the activity-side link, which until now could only ever be set at Garmin-sync time. */
export function GearCard({ activity }: { activity: Activity }) {
  const kind: "bike" | "shoe" | null = activity.sport === "bike" ? "bike" : activity.sport === "run" ? "shoe" : null;
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [selected, setSelected] = useState("");

  const { data: bikes } = useQuery({ queryKey: ["bikes"], queryFn: listBikes, enabled: kind === "bike" });
  const { data: shoes } = useQuery({ queryKey: ["shoes"], queryFn: listShoes, enabled: kind === "shoe" });

  const saveMutation = useMutation({
    mutationFn: (patch: { bike_id?: string | null; shoe_id?: string | null }) => updateActivity(activity.id, patch),
    onSuccess: (updated) => {
      queryClient.setQueryData(["activity", activity.id], updated);
      setEditing(false);
    },
  });

  if (!kind) {
    return null;
  }

  const options = kind === "bike" ? bikes?.data : shoes?.data;
  const currentId = kind === "bike" ? activity.bike_id : activity.shoe_id;
  const current = options?.find((item) => item.id === currentId);
  const label = kind === "bike" ? "Bike" : "Shoes";

  function startEditing() {
    setSelected(currentId ?? "");
    setEditing(true);
  }

  function commit() {
    const patch = kind === "bike" ? { bike_id: selected || null } : { shoe_id: selected || null };
    saveMutation.mutate(patch);
  }

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "16px 22px" }}>
      <div className="mono" style={{ fontSize: 11, letterSpacing: "0.08em", color: "#3d7fd6", marginBottom: 12 }}>
        {label.toUpperCase()}
      </div>
      {!editing && current && (
        <div style={{ display: "flex", alignItems: "center", gap: 9 }}>
          <span style={{ fontSize: 14, fontWeight: 700, color: "var(--ink)" }}>{current.name}</span>
          <button type="button" onClick={startEditing} style={{ ...addButtonStyle, marginLeft: "auto", padding: "3px 9px", fontSize: 11.5 }}>
            Change
          </button>
        </div>
      )}
      {!editing && !current && (
        <button type="button" onClick={startEditing} style={addButtonStyle}>
          + Assign {label.toLowerCase()}
        </button>
      )}
      {editing && (
        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <select value={selected} onChange={(e) => setSelected(e.target.value)} style={selectStyle} autoFocus>
            <option value="">None</option>
            {options?.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={commit}
            disabled={saveMutation.isPending}
            style={{
              fontSize: 12,
              fontWeight: 700,
              padding: "4px 12px",
              borderRadius: 8,
              border: "none",
              background: "var(--ember)",
              color: "#fff",
              cursor: "pointer",
              opacity: saveMutation.isPending ? 0.5 : 1,
            }}
          >
            {saveMutation.isPending ? "…" : "Save"}
          </button>
          <button
            type="button"
            onClick={() => setEditing(false)}
            style={{ fontSize: 12, border: "none", background: "none", color: "var(--ink3)", cursor: "pointer" }}
          >
            Cancel
          </button>
        </div>
      )}
      {saveMutation.isError && (
        <div style={{ fontSize: 12, color: "#e0442e", marginTop: 8 }}>{(saveMutation.error as Error).message}</div>
      )}
    </div>
  );
}
