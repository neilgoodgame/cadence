import { useState, type CSSProperties } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { recomputeActivityTss, updateActivity } from "../../api/activities";
import type { Activity } from "../../api/types";
import { formatDuration } from "../../lib/format";

const teInputStyle: CSSProperties = {
  fontSize: 13,
  padding: "2px 6px",
  borderRadius: 6,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  color: "var(--ink)",
  width: 56,
};

function Stat({ label, value, unit }: { label: string; value: string | number | null; unit?: string }) {
  return (
    <div style={{ flex: 1 }}>
      <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 4 }}>
        {label.toUpperCase()}
      </div>
      <div className="mono" style={{ fontSize: 22, fontWeight: 600 }}>
        {value ?? "—"}
        {unit && value != null && <span style={{ fontSize: 13, fontWeight: 500, color: "var(--ink2)", marginLeft: 4 }}>{unit}</span>}
      </div>
    </div>
  );
}

export function StatRow({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
  const recomputeMutation = useMutation({
    mutationFn: () => recomputeActivityTss(activity.id),
    onSuccess: (updated) => {
      queryClient.setQueryData(["activity", activity.id], updated);
      queryClient.invalidateQueries({ queryKey: ["activities", "training-history"] });
    },
  });

  // Aerobic/Anaerobic TE are Garmin Firstbeat-computed - absent for anything not recorded on a
  // real Garmin device. A Zwift-originated activity routed through Garmin Connect is the common
  // case this exists for: Connect's own web UI shows a real cloud-computed value, but that value
  // never gets written back into the FIT file you can download/re-upload, so there's no way for
  // Cadence to pick it up automatically - manual entry from what Connect's UI already shows is
  // the only path. No "don't overwrite real data" guard here (unlike EnvironmentCard's air temp/
  // humidity): a FIT-embedded value for a non-Garmin-device source is already untrustworthy
  // (often a meaningless 0.0 placeholder, as this exact scenario turned out to be), so there's no
  // real device reading to protect against being overwritten.
  const [editingTe, setEditingTe] = useState(false);
  const [aerobicInput, setAerobicInput] = useState("");
  const [anaerobicInput, setAnaerobicInput] = useState("");
  const [teError, setTeError] = useState<string | null>(null);

  const teSaveMutation = useMutation({
    mutationFn: (patch: { aerobic_training_effect: number | null; anaerobic_training_effect: number | null }) =>
      updateActivity(activity.id, patch),
    onSuccess: (updated) => {
      queryClient.setQueryData(["activity", activity.id], updated);
      setEditingTe(false);
    },
  });

  function startEditingTe() {
    setAerobicInput(activity.aerobic_training_effect?.toString() ?? "");
    setAnaerobicInput(activity.anaerobic_training_effect?.toString() ?? "");
    setTeError(null);
    setEditingTe(true);
  }

  function validTe(input: string): number | null | "invalid" {
    if (input.trim() === "") return null;
    const n = Number(input);
    if (Number.isNaN(n) || n < 0 || n > 5) return "invalid";
    return n;
  }

  function commitTe() {
    const aerobic = validTe(aerobicInput);
    const anaerobic = validTe(anaerobicInput);
    if (aerobic === "invalid" || anaerobic === "invalid") {
      setTeError("Training effect should be between 0.0 and 5.0.");
      return;
    }
    setTeError(null);
    teSaveMutation.mutate({ aerobic_training_effect: aerobic, anaerobic_training_effect: anaerobic });
  }

  return (
    <div style={{ display: "flex", padding: "18px 0", borderTop: "1px solid var(--line)", borderBottom: "1px solid var(--line)" }}>
      <Stat label="Distance" value={activity.distance_km.toFixed(2)} unit="km" />
      <Stat label="Moving time" value={formatDuration(activity.moving_time)} />
      <Stat label="Avg power" value={activity.avg_power} unit="w" />
      <Stat label="Norm. power" value={activity.norm_power} unit="w" />
      <Stat label="Intensity" value={activity.intensity ? activity.intensity.toFixed(2) : null} />
      <div style={{ flex: 1 }}>
        <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 4, display: "flex", alignItems: "center", gap: 6 }}>
          TSS
          <button
            type="button"
            onClick={() => recomputeMutation.mutate()}
            disabled={recomputeMutation.isPending}
            title="Recompute TSS from stored records"
            style={{
              fontSize: 9,
              padding: "1px 5px",
              borderRadius: 4,
              border: "1px solid var(--line)",
              background: "none",
              color: recomputeMutation.isSuccess ? "var(--ember)" : "var(--ink3)",
              cursor: "pointer",
              opacity: recomputeMutation.isPending ? 0.5 : 1,
              lineHeight: 1.4,
            }}
          >
            {recomputeMutation.isPending ? "…" : "↺"}
          </button>
        </div>
        <div className="mono" style={{ fontSize: 22, fontWeight: 600 }}>
          {activity.tss ?? "—"}
        </div>
      </div>
      <Stat label="Avg HR" value={activity.avg_hr} unit="bpm" />
      <Stat label="Ascent" value={activity.ascent} unit="m" />
      <div style={{ flex: 1 }}>
        <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 4, display: "flex", alignItems: "center", gap: 6 }}>
          AEROBIC TE
          <button
            type="button"
            onClick={editingTe ? () => setEditingTe(false) : startEditingTe}
            title="Enter manually - e.g. from Garmin Connect, for a Zwift-originated file whose downloadable FIT doesn't carry Connect's own computed value"
            style={{
              fontSize: 9,
              padding: "1px 5px",
              borderRadius: 4,
              border: "1px solid var(--line)",
              background: "none",
              color: "var(--ink3)",
              cursor: "pointer",
              lineHeight: 1.4,
            }}
          >
            ✎
          </button>
        </div>
        {editingTe ? (
          <input
            type="number"
            min={0}
            max={5}
            step={0.1}
            placeholder="0.0–5.0"
            value={aerobicInput}
            onChange={(e) => setAerobicInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && commitTe()}
            autoFocus
            style={teInputStyle}
          />
        ) : (
          <div className="mono" style={{ fontSize: 22, fontWeight: 600 }}>
            {activity.aerobic_training_effect != null ? activity.aerobic_training_effect.toFixed(1) : "—"}
            {activity.training_effect_label && activity.aerobic_training_effect != null && (
              <span style={{ fontSize: 13, fontWeight: 500, color: "var(--ink2)", marginLeft: 4 }}>{activity.training_effect_label}</span>
            )}
          </div>
        )}
      </div>
      <div style={{ flex: 1 }}>
        <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 4 }}>
          ANAEROBIC TE
        </div>
        {editingTe ? (
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <input
              type="number"
              min={0}
              max={5}
              step={0.1}
              placeholder="0.0–5.0"
              value={anaerobicInput}
              onChange={(e) => setAnaerobicInput(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && commitTe()}
              style={teInputStyle}
            />
            {teError && <div style={{ fontSize: 10, color: "var(--danger, #c0392b)" }}>{teError}</div>}
            <div style={{ display: "flex", gap: 8 }}>
              <button
                type="button"
                onClick={commitTe}
                disabled={teSaveMutation.isPending}
                style={{
                  fontSize: 11,
                  fontWeight: 700,
                  padding: "3px 10px",
                  borderRadius: 7,
                  border: "none",
                  background: "var(--ember)",
                  color: "#fff",
                  cursor: "pointer",
                  opacity: teSaveMutation.isPending ? 0.5 : 1,
                }}
              >
                {teSaveMutation.isPending ? "…" : "Save"}
              </button>
              <button
                type="button"
                onClick={() => setEditingTe(false)}
                style={{ fontSize: 11, border: "none", background: "none", color: "var(--ink3)", cursor: "pointer" }}
              >
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <div className="mono" style={{ fontSize: 22, fontWeight: 600 }}>
            {activity.anaerobic_training_effect != null ? activity.anaerobic_training_effect.toFixed(1) : "—"}
          </div>
        )}
      </div>
    </div>
  );
}
