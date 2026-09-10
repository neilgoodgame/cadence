import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { getLaps, inferWorkout, regenerateActivityLaps } from "../../api/activities";
import { ApiError, type Athlete, type Lap, type Sport } from "../../api/types";
import { formatDuration } from "../../lib/format";
import { kindLabel, zoneColor } from "../workouts/workoutTree";
import {
  compliancePct,
  complianceColor,
  formatStepTarget,
  groupLaps,
  stepZonePct,
  summarizeSteps,
  sum,
  weightedAvg,
  type RepeatGroup,
} from "./lapPresentation";

function StepSummaryCards({ laps, athlete }: { laps: Lap[]; athlete: Athlete }) {
  const rows = useMemo(() => summarizeSteps(laps, athlete), [laps, athlete]);
  // Nothing worth summarizing for an activity with no step-derived laps at all (original-source
  // laps, or an unmatched activity) - every lap would land in one "Other" bucket.
  if (!rows.some((r) => r.key !== "other")) return null;

  return (
    <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
      {rows.map((row) => (
        <div
          key={row.key}
          style={{
            display: "flex",
            alignItems: "stretch",
            gap: 10,
            border: "1px solid var(--line)",
            borderRadius: 10,
            padding: "8px 12px",
            background: "var(--card)",
            minWidth: 140,
          }}
        >
          <div style={{ width: 4, borderRadius: 2, background: row.color, flexShrink: 0 }} />
          <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
            <div style={{ fontSize: 12.5, fontWeight: 700 }}>
              {row.label}
              {row.target && <span style={{ fontWeight: 500, color: "var(--ink3)" }}> · {row.target}</span>}
            </div>
            <div className="mono" style={{ fontSize: 11.5, color: "var(--ink2)" }}>
              {row.count}× avg {formatDuration(Math.round(row.avgDuration))}
              {row.avgPower != null && ` · ${Math.round(row.avgPower)}w`}
              {row.avgHr != null && ` · ${Math.round(row.avgHr)}bpm`}
            </div>
            {row.avgCompliancePct != null && (
              <div className="mono" style={{ fontSize: 11, fontWeight: 700, color: complianceColor(row.avgCompliancePct) }}>
                {row.avgCompliancePct}% of target
              </div>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

function ComplianceBadge({ lap, athlete }: { lap: Lap; athlete: Athlete }) {
  const pct = compliancePct(lap, athlete);
  if (pct == null) return null;
  return (
    <span className="mono" style={{ fontSize: 10.5, fontWeight: 700, color: complianceColor(pct) }}>
      {pct}% of target
    </span>
  );
}

function StepBadge({ lap, athlete }: { lap: Lap; athlete: Athlete }) {
  if (!lap.step_kind) return <span style={{ fontSize: 12.5, color: "var(--ink3)" }}>—</span>;
  const target = formatStepTarget(lap);
  const zonePct = stepZonePct(lap, athlete);
  const color = zonePct != null ? zoneColor(zonePct) : "var(--ink3)";
  return (
    <span style={{ display: "flex", alignItems: "center", gap: 6 }}>
      <span style={{ width: 4, alignSelf: "stretch", minHeight: 22, borderRadius: 2, background: color, flexShrink: 0 }} />
      <span style={{ display: "flex", flexDirection: "column", gap: 1 }}>
        <span style={{ fontSize: 12.5 }}>
          {kindLabel(lap.step_kind)}
          {lap.repeat_index != null && <span style={{ color: "var(--ink3)" }}> · rep {lap.repeat_index}</span>}
        </span>
        {target && <span className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>{target}</span>}
      </span>
    </span>
  );
}

function RepeatGroupRow({
  group,
  expanded,
  onToggle,
  maxPower,
  athlete,
}: {
  group: RepeatGroup;
  expanded: boolean;
  onToggle: () => void;
  maxPower: number;
  athlete: Athlete;
}) {
  const allLaps = group.reps.flat();
  const totalDuration = sum(allLaps.map((l) => l.duration));
  const totalDistance = sum(allLaps.map((l) => l.distance_km));
  const avgPower = weightedAvg(allLaps, (l) => l.avg_power);
  const avgHr = weightedAvg(allLaps, (l) => l.avg_hr);

  return (
    <>
      <tr
        onClick={onToggle}
        style={{ borderTop: "1px solid var(--line)", cursor: "pointer", background: expanded ? "var(--elev)" : undefined }}
      >
        <td style={{ padding: "8px 0" }}>
          <span style={{ display: "flex", alignItems: "center", gap: 6 }}>
            <span style={{ fontSize: 10, color: "var(--ink3)", width: 12, textAlign: "center" }}>{expanded ? "▾" : "▸"}</span>
            <span
              className="mono"
              style={{ fontSize: 11, fontWeight: 700, color: "var(--ember)", background: "rgba(236,74,38,0.1)", borderRadius: 6, padding: "2px 6px" }}
            >
              {group.reps.length}×
            </span>
          </span>
        </td>
        <td>
          <span style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
            {group.reps[0].map((templateLap) => (
              <StepBadge key={templateLap.workout_step_id ?? templateLap.index} lap={templateLap} athlete={athlete} />
            ))}
          </span>
        </td>
        <td className="mono">{totalDistance.toFixed(2)} km</td>
        <td className="mono">{formatDuration(totalDuration)}</td>
        <td className="mono">{((totalDistance / (totalDuration / 3600)) || 0).toFixed(1)} km/h</td>
        <td>
          {avgPower != null ? (
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <div style={{ width: 60, height: 6, background: "var(--elev)", borderRadius: 3 }}>
                <div style={{ width: `${(avgPower / maxPower) * 100}%`, height: "100%", background: "var(--ember)", borderRadius: 3 }} />
              </div>
              <span className="mono">{avgPower} w</span>
            </div>
          ) : (
            <span className="mono" style={{ color: "var(--ink3)" }}>—</span>
          )}
        </td>
        <td className="mono">{avgHr != null ? `${avgHr} bpm` : "—"}</td>
      </tr>
      {expanded &&
        group.reps.flat().map((lap) => (
          <tr key={lap.index} style={{ borderTop: "1px solid var(--line)", background: "var(--elev)" }}>
            <td style={{ padding: "6px 0 6px 22px" }}>
              <span style={{ display: "inline-block", width: 22, height: 22, borderRadius: "50%", background: "var(--card)", textAlign: "center", lineHeight: "22px", fontSize: 10.5 }}>
                {lap.index}
              </span>
            </td>
            <td>
              <span style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                <StepBadge lap={lap} athlete={athlete} />
                <ComplianceBadge lap={lap} athlete={athlete} />
              </span>
            </td>
            <td className="mono">{lap.distance_km.toFixed(2)} km</td>
            <td className="mono">{formatDuration(lap.duration)}</td>
            <td className="mono">{((lap.distance_km / (lap.duration / 3600)) || 0).toFixed(1)} km/h</td>
            <td>
              {lap.avg_power != null ? (
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <div style={{ width: 60, height: 6, background: "var(--card)", borderRadius: 3 }}>
                    <div style={{ width: `${(lap.avg_power / maxPower) * 100}%`, height: "100%", background: "var(--ember)", borderRadius: 3 }} />
                  </div>
                  <span className="mono">{lap.avg_power} w</span>
                </div>
              ) : (
                <span className="mono" style={{ color: "var(--ink3)" }}>—</span>
              )}
            </td>
            <td className="mono">{lap.avg_hr != null ? `${lap.avg_hr} bpm` : "—"}</td>
          </tr>
        ))}
    </>
  );
}

function SingleLapRow({ lap, maxPower, athlete }: { lap: Lap; maxPower: number; athlete: Athlete }) {
  return (
    <tr style={{ borderTop: "1px solid var(--line)" }}>
      <td style={{ padding: "8px 0" }}>
        <span style={{ display: "inline-block", width: 24, height: 24, borderRadius: "50%", background: "var(--elev)", textAlign: "center", lineHeight: "24px", fontSize: 11 }}>
          {lap.index}
        </span>
      </td>
      <td>
        <span style={{ display: "flex", flexDirection: "column", gap: 2 }}>
          <StepBadge lap={lap} athlete={athlete} />
          <ComplianceBadge lap={lap} athlete={athlete} />
        </span>
      </td>
      <td className="mono">{lap.distance_km.toFixed(2)} km</td>
      <td className="mono">{formatDuration(lap.duration)}</td>
      <td className="mono">{((lap.distance_km / (lap.duration / 3600)) || 0).toFixed(1)} km/h</td>
      <td>
        {lap.avg_power != null ? (
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <div style={{ width: 60, height: 6, background: "var(--elev)", borderRadius: 3 }}>
              <div style={{ width: `${(lap.avg_power / maxPower) * 100}%`, height: "100%", background: "var(--ember)", borderRadius: 3 }} />
            </div>
            <span className="mono">{lap.avg_power} w</span>
          </div>
        ) : (
          <span className="mono" style={{ color: "var(--ink3)" }}>—</span>
        )}
      </td>
      <td className="mono">{lap.avg_hr != null ? `${lap.avg_hr} bpm` : "—"}</td>
    </tr>
  );
}

function RegenerateLapsButton({ activityId }: { activityId: string }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () => regenerateActivityLaps(activityId),
    onSuccess: (updated) => queryClient.setQueryData(["activity-laps", activityId], updated),
  });
  const error = mutation.isError
    ? mutation.error instanceof ApiError
      ? mutation.error.message
      : "Couldn't regenerate laps. Try again."
    : null;
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <button
        type="button"
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
        title="Replace this activity's laps with ones derived from its matched workout's steps."
        style={{
          fontSize: 12,
          fontWeight: 600,
          padding: "5px 10px",
          borderRadius: 8,
          border: "1px solid var(--line)",
          background: "none",
          color: mutation.isSuccess ? "var(--ember)" : "var(--ink3)",
          cursor: "pointer",
          opacity: mutation.isPending ? 0.6 : 1,
        }}
      >
        {mutation.isPending ? "Regenerating…" : "↺ Regenerate from workout"}
      </button>
      {error && <span style={{ fontSize: 12.5, color: "#e0442e" }}>{error}</span>}
    </div>
  );
}

export function LapsTab({
  activityId,
  sport,
  workoutId,
  athlete,
}: {
  activityId: string;
  sport: Sport;
  workoutId: string | null;
  athlete: Athlete;
}) {
  const navigate = useNavigate();
  const { data } = useQuery({ queryKey: ["activity-laps", activityId], queryFn: () => getLaps(activityId) });
  const laps = useMemo(() => data?.data ?? [], [data]);
  const maxPower = Math.max(1, ...laps.map((l) => l.avg_power ?? 0));
  const [autoDetectRepeats, setAutoDetectRepeats] = useState(true);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const groups = useMemo(() => groupLaps(laps), [laps]);

  const inferMutation = useMutation({
    mutationFn: () => inferWorkout(activityId, autoDetectRepeats),
    onSuccess: (draft) => navigate("/workouts", { state: { inferredDraft: draft } }),
  });
  const inferError = inferMutation.isError
    ? inferMutation.error instanceof ApiError
      ? inferMutation.error.message
      : "Couldn't infer a workout from these laps. Try again."
    : null;

  if (laps.length === 0) {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {workoutId && <RegenerateLapsButton activityId={activityId} />}
        <div style={{ color: "var(--ink3)", fontSize: 13 }}>No laps recorded.</div>
      </div>
    );
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
        {(sport === "bike" || sport === "run") && (
          <>
            <button
              onClick={() => inferMutation.mutate()}
              disabled={inferMutation.isPending}
              style={{
                border: "1px solid var(--line)",
                borderRadius: 8,
                background: "var(--card)",
                color: "var(--ink2)",
                fontSize: 12.5,
                fontWeight: 700,
                padding: "7px 14px",
                cursor: "pointer",
                opacity: inferMutation.isPending ? 0.6 : 1,
              }}
            >
              {inferMutation.isPending ? "Building…" : "Create workout from laps"}
            </button>
            <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12.5, color: "var(--ink3)" }}>
              <input type="checkbox" checked={autoDetectRepeats} onChange={(e) => setAutoDetectRepeats(e.target.checked)} />
              Auto-detect repeat groups
            </label>
            {inferError && <span style={{ fontSize: 12.5, color: "#e0442e" }}>{inferError}</span>}
          </>
        )}
        {workoutId && <RegenerateLapsButton activityId={activityId} />}
      </div>
      <StepSummaryCards laps={laps} athlete={athlete} />
      <table style={{ width: "100%", fontSize: 13, borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ textAlign: "left", color: "var(--ink3)", fontSize: 11 }}>
            <th style={{ paddingBottom: 8 }}>Lap</th>
            <th style={{ paddingBottom: 8 }}>Step</th>
            <th style={{ paddingBottom: 8 }}>Distance</th>
            <th style={{ paddingBottom: 8 }}>Time</th>
            <th style={{ paddingBottom: 8 }}>Speed</th>
            <th style={{ paddingBottom: 8 }}>Avg Power</th>
            <th style={{ paddingBottom: 8 }}>Avg HR</th>
          </tr>
        </thead>
        <tbody>
          {groups.map((group) =>
            group.type === "single" ? (
              <SingleLapRow key={group.lap.index} lap={group.lap} maxPower={maxPower} athlete={athlete} />
            ) : (
              <RepeatGroupRow
                key={group.key}
                group={group}
                expanded={expanded.has(group.key)}
                onToggle={() =>
                  setExpanded((prev) => {
                    const next = new Set(prev);
                    if (next.has(group.key)) {
                      next.delete(group.key);
                    } else {
                      next.add(group.key);
                    }
                    return next;
                  })
                }
                maxPower={maxPower}
                athlete={athlete}
              />
            ),
          )}
        </tbody>
      </table>
    </div>
  );
}
