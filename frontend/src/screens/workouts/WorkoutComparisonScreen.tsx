import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { regenerateActivityLaps } from "../../api/activities";
import { getWorkout, getWorkoutMatchComparison } from "../../api/workouts";
import type { WorkoutMatchComparisonEntry } from "../../api/types";
import { Card } from "../../components/Card";
import { formatDate } from "../../lib/format";
import { WorkoutComparisonChart, type ComparisonPoint } from "./WorkoutComparisonChart";

type MetricKey =
  | "ef"
  | "avg_power"
  | "avg_hr"
  | "work_block_avg_power"
  | "work_block_avg_hr"
  | "avg_core_temp"
  | "avg_air_temp"
  | "avg_humidity";

const METRICS: { key: MetricKey; label: string; format: (v: number) => string }[] = [
  { key: "ef", label: "Aerobic Efficiency", format: (v) => v.toFixed(3) },
  { key: "avg_power", label: "Avg Power", format: (v) => `${Math.round(v)}W` },
  { key: "avg_hr", label: "Avg HR", format: (v) => `${Math.round(v)}bpm` },
  { key: "work_block_avg_power", label: "Work-block Power", format: (v) => `${Math.round(v)}W` },
  { key: "work_block_avg_hr", label: "Work-block HR", format: (v) => `${Math.round(v)}bpm` },
  { key: "avg_core_temp", label: "Core Temp", format: (v) => `${v.toFixed(1)}°C` },
  { key: "avg_air_temp", label: "Air Temp", format: (v) => `${v.toFixed(1)}°C` },
  { key: "avg_humidity", label: "Humidity", format: (v) => `${Math.round(v)}%` },
];

const GRID_COLS = "28px minmax(90px,0.9fr) minmax(140px,1.3fr) 0.55fr 0.5fr 0.6fr minmax(180px,1.8fr) 0.65fr 0.6fr 0.5fr";

function segBtn(active: boolean) {
  return {
    padding: "6px 12px",
    fontSize: 12,
    fontWeight: 600,
    borderRadius: 7,
    cursor: "pointer",
    whiteSpace: "nowrap" as const,
    color: active ? "var(--ink)" : "var(--ink3)",
    background: active ? "var(--card)" : "transparent",
    border: "none",
  };
}

function ColHeaders() {
  const style = { fontSize: 10, fontWeight: 700, letterSpacing: "0.06em", color: "var(--ink3)", textTransform: "uppercase" as const };
  return (
    <div style={{ display: "grid", gridTemplateColumns: GRID_COLS, gap: 8, padding: "0 4px 8px", minWidth: 830 }}>
      <span style={style}>#</span>
      <span style={style}>Date</span>
      <span style={style}>Activity</span>
      <span style={style}>Power</span>
      <span style={style}>HR</span>
      <span style={style}>EF</span>
      <span style={style}>Work-block</span>
      <span style={style}>Block HR</span>
      <span style={style}>Core temp</span>
      <span style={style}>TSS</span>
    </div>
  );
}

// A row with no work-block averages never had its laps derived against this workout (an
// unmatched-at-the-time import, or one that predates lap derivation) - regenerating fixes both
// work_block_avg_power and work_block_avg_hr together, since they come from the same lap data.
function RegenerateWorkBlockButton({ activityId, workoutId }: { activityId: string; workoutId: string }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () => regenerateActivityLaps(activityId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["workout-match-comparison", workoutId] }),
  });
  return (
    <button
      type="button"
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
        mutation.mutate();
      }}
      disabled={mutation.isPending}
      title={
        mutation.isError
          ? "Couldn't regenerate laps for this activity - it may have no matched workout data to derive from."
          : "Regenerate this activity's laps from the matched workout to fill in work-block power/HR."
      }
      style={{
        // Same style as LapsTab.tsx's "Create workout from laps" button.
        width: "100%",
        border: `1px solid ${mutation.isError ? "#e0442e" : "var(--line)"}`,
        borderRadius: 8,
        background: mutation.isError ? "rgba(224,68,46,0.08)" : "var(--card)",
        color: mutation.isError ? "#e0442e" : "var(--ink2)",
        fontSize: 12.5,
        fontWeight: 700,
        padding: "7px 14px",
        cursor: "pointer",
        whiteSpace: "nowrap" as const,
        opacity: mutation.isPending ? 0.6 : 1,
      }}
    >
      {mutation.isPending ? "Regenerating…" : mutation.isError ? "Failed - retry" : "Regenerate laps from workout"}
    </button>
  );
}

function Row({ rank, entry, workoutId }: { rank: number; entry: WorkoutMatchComparisonEntry; workoutId: string }) {
  const missingWorkBlockData = entry.work_block_avg_power == null && entry.work_block_avg_hr == null;
  return (
    <Link
      to={`/activities/${entry.activity_id}`}
      style={{
        display: "grid",
        gridTemplateColumns: GRID_COLS,
        gap: 8,
        alignItems: "center",
        padding: "8px 4px",
        borderTop: "1px solid var(--line)",
        minWidth: 830,
        textDecoration: "none",
        color: "inherit",
      }}
    >
      <span className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>{rank}</span>
      <span className="mono" style={{ fontSize: 12, color: "var(--ink)" }}>{formatDate(entry.date, true)}</span>
      <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
        {entry.name}
      </span>
      <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>{entry.avg_power != null ? `${entry.avg_power}W` : "—"}</span>
      <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>{entry.avg_hr != null ? entry.avg_hr : "—"}</span>
      <span className="mono" style={{ fontSize: 13, fontWeight: 700, color: "var(--ink)" }}>{entry.ef != null ? entry.ef.toFixed(3) : "—"}</span>
      <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>
        {missingWorkBlockData ? (
          <RegenerateWorkBlockButton activityId={entry.activity_id} workoutId={workoutId} />
        ) : entry.work_block_avg_power != null ? (
          `${entry.work_block_avg_power}W`
        ) : (
          "—"
        )}
      </span>
      <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>
        {missingWorkBlockData ? "" : entry.work_block_avg_hr != null ? entry.work_block_avg_hr : "—"}
      </span>
      <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>
        {entry.avg_core_temp != null ? `${entry.avg_core_temp.toFixed(1)}°C` : "—"}
      </span>
      <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>{entry.tss}</span>
    </Link>
  );
}

export function WorkoutComparisonScreen() {
  const { id } = useParams<{ id: string }>();
  const [metric, setMetric] = useState<MetricKey>("ef");

  const workoutQuery = useQuery({ queryKey: ["workout", id], queryFn: () => getWorkout(id!), enabled: !!id });
  const comparisonQuery = useQuery({ queryKey: ["workout-match-comparison", id], queryFn: () => getWorkoutMatchComparison(id!), enabled: !!id });

  const activeMetric = METRICS.find((m) => m.key === metric)!;
  const entries = useMemo(() => comparisonQuery.data?.data ?? [], [comparisonQuery.data]);

  const points: ComparisonPoint[] = useMemo(
    () =>
      entries
        .filter((e) => e[metric] != null)
        .map((e) => ({ activityId: e.activity_id, name: e.name, date: new Date(e.date), value: e[metric] as number })),
    [entries, metric],
  );

  const ranked = useMemo(
    () =>
      [...entries]
        .filter((e) => e[metric] != null)
        .sort((a, b) => (b[metric] as number) - (a[metric] as number)),
    [entries, metric],
  );

  if (!workoutQuery.data || !comparisonQuery.data) {
    return <div style={{ color: "var(--ink3)" }}>Loading…</div>;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20, maxWidth: 900 }}>
      <div>
        <Link to={`/workouts/${id}`} style={{ fontSize: 12, color: "var(--ink3)", fontWeight: 600 }}>
          ← {workoutQuery.data.name}
        </Link>
        <h1 style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.02em", margin: "8px 0 0" }}>Compare matched activities</h1>
      </div>

      <Card>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 4, marginBottom: 14 }}>
          {METRICS.map((m) => (
            <button key={m.key} onClick={() => setMetric(m.key)} style={segBtn(metric === m.key)}>
              {m.label}
            </button>
          ))}
        </div>
        <div style={{ fontSize: 13, fontWeight: 700, color: "var(--ink)", marginBottom: 10 }}>
          {activeMetric.label} on {workoutQuery.data.name}
        </div>
        <WorkoutComparisonChart points={points} formatValue={activeMetric.format} />
      </Card>

      <Card>
        <div className="mono" style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", color: "var(--ink3)", marginBottom: 12 }}>
          FULL RANKING ({activeMetric.label.toUpperCase()}, HIGHEST TO LOWEST)
        </div>
        {ranked.length === 0 ? (
          <div style={{ fontSize: 13, color: "var(--ink3)" }}>No matches have this metric yet.</div>
        ) : (
          <div style={{ overflowX: "auto" }}>
            <ColHeaders />
            {ranked.map((entry, i) => (
              <Row key={entry.activity_id} rank={i + 1} entry={entry} workoutId={id!} />
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
