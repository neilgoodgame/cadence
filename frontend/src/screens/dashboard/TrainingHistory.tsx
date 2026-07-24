import { useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { recomputeTss } from "../../api/athletes";
import type { Activity } from "../../api/types";
import { formatDuration, formatPace } from "../../lib/format";

type Metric = "run_distance" | "run_time" | "activity_time" | "tss";

const METRIC_LABELS: Record<Metric, string> = {
  run_distance: "Run distance",
  run_time: "Run time",
  activity_time: "Activity time",
  tss: "TSS",
};

function localIso(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function getMonday(date: Date): Date {
  const d = new Date(date);
  d.setHours(0, 0, 0, 0);
  const day = d.getDay();
  d.setDate(d.getDate() - (day === 0 ? 6 : day - 1));
  return d;
}

interface WeekBlock {
  start: string;
  end: string;
  monthLabel: string | null;
}

function buildWeekBlocks(today: Date): WeekBlock[] {
  const thisMonday = getMonday(today);
  const blocks: WeekBlock[] = [];

  for (let i = 16; i >= 1; i--) {
    const weekStart = new Date(thisMonday);
    weekStart.setDate(thisMonday.getDate() - i * 7);
    const weekEnd = new Date(weekStart);
    weekEnd.setDate(weekStart.getDate() + 6);
    blocks.push({ start: localIso(weekStart), end: localIso(weekEnd), monthLabel: null });
  }

  return blocks.map((block, idx) => {
    const ws = new Date(block.start);
    const prev = idx > 0 ? new Date(blocks[idx - 1].start) : null;
    return {
      ...block,
      monthLabel: !prev || ws.getMonth() !== prev.getMonth()
        ? ws.toLocaleString("default", { month: "short" })
        : null,
    };
  });
}

function getMetricValue(activities: Activity[], metric: Metric): number {
  const nonWalks = activities.filter((a) => a.sport !== "walk");
  const runs = activities.filter((a) => a.sport === "run");
  switch (metric) {
    case "run_distance": return runs.reduce((s, a) => s + a.distance_km, 0);
    case "run_time":     return runs.reduce((s, a) => s + a.moving_time, 0);
    case "activity_time": return nonWalks.reduce((s, a) => s + a.moving_time, 0);
    case "tss":          return nonWalks.reduce((s, a) => s + a.tss, 0);
  }
}

function formatMetricValue(value: number, metric: Metric): string {
  if (metric === "run_distance") return `${value.toFixed(1)} km`;
  if (metric === "tss") return Math.round(value).toString();
  return formatDuration(Math.round(value));
}

export function TrainingHistory({ activities, athleteId }: { activities: Activity[]; athleteId: string }) {
  const queryClient = useQueryClient();
  const [metric, setMetric] = useState<Metric>("run_distance");

  const recomputeMutation = useMutation({
    mutationFn: () => recomputeTss(athleteId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["activities", "dashboard"] }),
  });

  const today = useMemo(() => new Date(), []);
  const weeks = useMemo(() => buildWeekBlocks(today), [today]);

  const weeklyActivities = useMemo(
    () =>
      weeks.map((week) =>
        activities.filter((a) => {
          const d = a.start_date.slice(0, 10);
          return d >= week.start && d <= week.end;
        })
      ),
    [activities, weeks]
  );

  const weeklyValues = useMemo(
    () => weeklyActivities.map((acts) => getMetricValue(acts, metric)),
    [weeklyActivities, metric]
  );

  const maxValue = Math.max(1, ...weeklyValues);

  const blockActivities = useMemo(() => weeklyActivities.flat(), [weeklyActivities]);
  const nonWalks = blockActivities.filter((a) => a.sport !== "walk");
  const runs = blockActivities.filter((a) => a.sport === "run");
  const rides = blockActivities.filter((a) => a.sport === "bike");

  const totalTimeS = nonWalks.reduce((s, a) => s + a.moving_time, 0);
  const totalTss = nonWalks.reduce((s, a) => s + a.tss, 0);
  const runDistanceKm = runs.reduce((s, a) => s + a.distance_km, 0);
  const runTimeS = runs.reduce((s, a) => s + a.moving_time, 0);
  const avgPaceSecPerKm = runDistanceKm > 0 ? runTimeS / runDistanceKm : null;
  const bikeDistanceKm = rides.reduce((s, a) => s + a.distance_km, 0);
  const poweredRides = rides.filter((a) => a.avg_power != null);
  const avgBikePower =
    poweredRides.length > 0
      ? Math.round(poweredRides.reduce((s, a) => s + a.avg_power!, 0) / poweredRides.length)
      : null;

  const nWeeks = weeks.length;

  const stats: ({ label: string; value: string } | null)[] = [
    { label: "Training time/wk", value: formatDuration(Math.round(totalTimeS / nWeeks)) },
    { label: "TSS/wk", value: Math.round(totalTss / nWeeks).toString() },
    null,
    { label: "Run dist/wk", value: `${(runDistanceKm / nWeeks).toFixed(1)} km` },
    { label: "Runs/wk", value: (runs.length / nWeeks).toFixed(1) },
    { label: "Avg run pace", value: avgPaceSecPerKm != null ? formatPace(avgPaceSecPerKm) : "—" },
    ...(rides.length > 0
      ? [
          null as null,
          { label: "Bike dist/wk", value: `${(bikeDistanceKm / nWeeks).toFixed(1)} km` },
          { label: "Rides/wk", value: (rides.length / nWeeks).toFixed(1) },
          { label: "Avg power", value: avgBikePower != null ? `${avgBikePower} W` : "—" },
        ]
      : []),
  ];

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 16 }}>
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>16-week block</h2>
        <div style={{ display: "flex", gap: 4, alignItems: "center" }}>
          {(Object.keys(METRIC_LABELS) as Metric[]).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMetric(m)}
              style={{
                fontSize: 11,
                fontWeight: 600,
                padding: "3px 10px",
                borderRadius: 20,
                border: "1px solid var(--line)",
                background: metric === m ? "var(--ember)" : "none",
                color: metric === m ? "#fff" : "var(--ink3)",
                cursor: "pointer",
              }}
            >
              {METRIC_LABELS[m]}
            </button>
          ))}
          {metric === "tss" && (
            <button
              type="button"
              onClick={() => recomputeMutation.mutate()}
              disabled={recomputeMutation.isPending}
              title="Recompute TSS for all historical activities using your current HR zones"
              style={{
                fontSize: 11,
                fontWeight: 600,
                padding: "3px 10px",
                borderRadius: 20,
                border: "1px solid var(--line)",
                background: "none",
                color: "var(--ink3)",
                cursor: "pointer",
                opacity: recomputeMutation.isPending ? 0.5 : 1,
                marginLeft: 8,
              }}
            >
              {recomputeMutation.isPending ? "Recomputing…" : recomputeMutation.isSuccess ? `Updated ${recomputeMutation.data?.updated}` : "Recompute TSS"}
            </button>
          )}
        </div>
      </div>

      {/* Histogram */}
      <div style={{ display: "flex", gap: 3, alignItems: "flex-end", height: 100 }}>
        {weeks.map((week, i) => {
          const value = weeklyValues[i];
          const barPct = (value / maxValue) * 100;
          return (
            <div
              key={week.start}
              style={{ flex: 1, height: "100%", display: "flex", flexDirection: "column", justifyContent: "flex-end" }}
              title={`${week.start}: ${formatMetricValue(value, metric)}`}
            >
              <div
                style={{
                  width: "100%",
                  height: `${barPct}%`,
                  minHeight: value > 0 ? 3 : 0,
                  background: "var(--ember)",
                  borderRadius: "3px 3px 0 0",
                  opacity: 0.85,
                  transition: "height 0.2s ease",
                }}
              />
            </div>
          );
        })}
      </div>

      {/* Month labels on x-axis */}
      <div style={{ display: "flex", gap: 3, marginBottom: 20, borderTop: "1px solid var(--line)", paddingTop: 4 }}>
        {weeks.map((week) => (
          <div key={week.start} style={{ flex: 1 }}>
            {week.monthLabel && (
              <span className="mono" style={{ fontSize: 9, fontWeight: 700, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.06em" }}>
                {week.monthLabel}
              </span>
            )}
          </div>
        ))}
      </div>

      {/* 16-week summary stats */}
      <div style={{ display: "flex", gap: 32, paddingTop: 14, borderTop: "1px solid var(--line)", flexWrap: "wrap" }}>
        {stats.map((stat, i) =>
          stat === null ? (
            <div key={i} style={{ width: 1, background: "var(--line)", alignSelf: "stretch" }} />
          ) : (
            <div key={stat.label}>
              <div style={{ fontSize: 11, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.06em", fontWeight: 700, marginBottom: 2 }}>
                {stat.label}
              </div>
              <div className="mono" style={{ fontSize: 18, fontWeight: 800, color: "var(--ink)" }}>
                {stat.value}
              </div>
            </div>
          )
        )}
      </div>
    </div>
  );
}
