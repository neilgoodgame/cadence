import { useMutation, useQueryClient } from "@tanstack/react-query";
import { recomputeActivityTss } from "../../api/activities";
import type { Activity } from "../../api/types";
import { formatDuration } from "../../lib/format";

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
      <Stat
        label="Aerobic TE"
        value={activity.aerobic_training_effect != null ? activity.aerobic_training_effect.toFixed(1) : null}
        unit={activity.training_effect_label || undefined}
      />
      <Stat
        label="Anaerobic TE"
        value={activity.anaerobic_training_effect != null ? activity.anaerobic_training_effect.toFixed(1) : null}
      />
    </div>
  );
}
