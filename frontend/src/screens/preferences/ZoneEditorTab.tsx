import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listZones, replaceZoneSet } from "../../api/athletes";
import type { Zone, ZoneSet, ZoneType } from "../../api/types";
import { HrZoneGenerator } from "./HrZoneGenerator";

const UNIT_BY_TYPE: Record<ZoneType, string> = {
  heart_rate: "bpm",
  bike_power: "w",
  run_power: "w",
  pace: "/km",
};

function ZoneEditorForm({ athleteId, type, zoneSet }: { athleteId: string; type: ZoneType; zoneSet: ZoneSet }) {
  const queryClient = useQueryClient();
  const [zones, setZones] = useState<Zone[]>(zoneSet.zones);
  const unit = UNIT_BY_TYPE[type];

  const mutation = useMutation({
    mutationFn: () => replaceZoneSet(athleteId, type, zones),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["zones", athleteId] }),
  });

  function updateZone(index: number, patch: Partial<Zone>) {
    setZones((prev) => prev.map((z, i) => (i === index ? { ...z, ...patch } : z)));
  }

  return (
    <div>
      <div style={{ fontSize: 13, color: "var(--ink3)", marginBottom: 16 }}>
        Reference threshold: <span className="mono">{zoneSet.reference ?? "not set"}</span>{" "}
        {zoneSet.reference != null && unit}
      </div>

      <table style={{ width: "100%", fontSize: 13, borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ textAlign: "left", color: "var(--ink3)", fontSize: 11 }}>
            <th style={{ paddingBottom: 8 }}>Zone</th>
            <th style={{ paddingBottom: 8 }}>Low %</th>
            <th style={{ paddingBottom: 8 }}>High %</th>
            <th style={{ paddingBottom: 8 }}>Range</th>
          </tr>
        </thead>
        <tbody>
          {zones.map((zone, i) => (
            <tr key={i} style={{ borderTop: "1px solid var(--line)" }}>
              <td style={{ padding: "8px 0" }}>
                <input
                  value={zone.name}
                  onChange={(e) => updateZone(i, { name: e.target.value })}
                  style={{ width: "100%", border: "none", background: "none", color: "var(--ink)", fontSize: 13 }}
                />
              </td>
              <td style={{ padding: "8px 0" }}>
                <input
                  type="number"
                  className="mono"
                  value={zone.low_pct}
                  onChange={(e) => updateZone(i, { low_pct: Number(e.target.value) })}
                  style={{ width: 60, border: "1px solid var(--line)", borderRadius: 6, padding: 4, background: "var(--elev)", color: "var(--ink)" }}
                />
              </td>
              <td style={{ padding: "8px 0" }}>
                <input
                  type="number"
                  className="mono"
                  value={zone.high_pct}
                  onChange={(e) => updateZone(i, { high_pct: Number(e.target.value) })}
                  style={{ width: 60, border: "1px solid var(--line)", borderRadius: 6, padding: 4, background: "var(--elev)", color: "var(--ink)" }}
                />
              </td>
              <td className="mono" style={{ padding: "8px 0", color: "var(--ink2)" }}>
                {zoneSet.reference != null ? (
                  <>
                    {Math.round((zone.low_pct / 100) * zoneSet.reference)}–
                    {Math.round((zone.high_pct / 100) * zoneSet.reference)} {unit}
                  </>
                ) : (
                  "—"
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <button
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
        style={{
          marginTop: 16,
          padding: "8px 16px",
          borderRadius: 8,
          border: "none",
          background: "var(--ember)",
          color: "#fff",
          fontSize: 13,
          fontWeight: 700,
        }}
      >
        {mutation.isPending ? "Saving…" : "Save zones"}
      </button>
      {mutation.isSuccess && <span style={{ marginLeft: 10, fontSize: 13, color: "#2fa66a" }}>Saved.</span>}
    </div>
  );
}

/** Keyed by type so switching tabs (or the initial fetch resolving) mounts a fresh form
 * with its own local draft state, rather than syncing an effect's setState into it. */
export function ZoneEditorTab({ athleteId, type }: { athleteId: string; type: ZoneType }) {
  const { data, dataUpdatedAt } = useQuery({ queryKey: ["zones", athleteId], queryFn: () => listZones(athleteId) });
  const zoneSet = data?.data.find((z) => z.type === type);

  return (
    <div>
      {type === "heart_rate" && <HrZoneGenerator athleteId={athleteId} />}
      {zoneSet ? (
        // dataUpdatedAt in the key remounts the draft when a refetch lands (e.g. after the
        // generator applies new zones), so the form doesn't keep showing the stale set.
        <ZoneEditorForm key={`${type}-${dataUpdatedAt}`} athleteId={athleteId} type={type} zoneSet={zoneSet} />
      ) : (
        <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading…</div>
      )}
    </div>
  );
}
