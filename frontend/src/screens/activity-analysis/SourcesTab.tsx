import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getStreamComparison, listActivitySources, setActivityPrimarySource } from "../../api/activities";
import type { Activity, ActivitySourceSummary, StreamComparisonField } from "../../api/types";
import { BlandAltmanScatter } from "./BlandAltmanScatter";
import { StreamComparisonOverlayChart } from "./StreamComparisonOverlayChart";
import { StreamDifferencePlot } from "./StreamDifferencePlot";

const ZONE_COLORS = ["var(--zone-1)", "var(--zone-2)", "var(--zone-3)", "var(--zone-4)", "var(--zone-5)"];

const FIELDS: { key: StreamComparisonField; label: string; unit: string }[] = [
  { key: "power", label: "Power sources", unit: "W" },
  { key: "heartrate", label: "Heart rate sources", unit: "bpm" },
  { key: "cadence", label: "Cadence sources", unit: "rpm" },
];

function StreamComparisonPanel({
  activityId,
  field,
  sourceB,
  unit,
}: {
  activityId: string;
  field: StreamComparisonField;
  sourceB: string;
  unit: string;
}) {
  const comparisonQuery = useQuery({
    queryKey: ["stream-comparison", activityId, field, "primary", sourceB],
    queryFn: () => getStreamComparison(activityId, field, "primary", sourceB, field !== "cadence"),
  });

  if (comparisonQuery.isLoading) {
    return <div style={{ color: "var(--ink3)", fontSize: 12, marginTop: 14 }}>Comparing…</div>;
  }
  if (comparisonQuery.isError || !comparisonQuery.data) {
    return (
      <div style={{ color: "var(--ink3)", fontSize: 12, marginTop: 14 }}>
        Not enough overlapping data between these two streams to compare.
      </div>
    );
  }
  const result = comparisonQuery.data;
  const stats = result.comparison;

  return (
    <div style={{ marginTop: 16, paddingTop: 16, borderTop: "1px solid var(--line)" }}>
      <div style={{ display: "flex", gap: 16, flexWrap: "wrap", fontSize: 12, color: "var(--ink2)", marginBottom: 12 }}>
        <span>Bias <b style={{ color: "var(--ink)" }}>{stats.bias.toFixed(1)} {unit}</b></span>
        <span>MAE <b style={{ color: "var(--ink)" }}>{stats.mae.toFixed(1)} {unit}</b></span>
        <span>RMSE <b style={{ color: "var(--ink)" }}>{stats.rmse.toFixed(1)} {unit}</b></span>
        <span>r <b style={{ color: "var(--ink)" }}>{stats.pearson_r.toFixed(2)}</b></span>
        <span>Within tolerance <b style={{ color: "var(--ink)" }}>{stats.within_tolerance_pct.toFixed(0)}%</b></span>
        {result.lag && <span>Lag <b style={{ color: "var(--ink)" }}>{result.lag.lag_seconds}s</b></span>}
        {result.power_fit && (
          <span>
            Fit{" "}
            <b style={{ color: "var(--ink)" }}>
              b = {result.power_fit.scale.toFixed(2)}·a + {result.power_fit.offset.toFixed(0)}
            </b>
          </span>
        )}
      </div>

      <StreamComparisonOverlayChart points={result.points} unit={unit} labelA="primary" labelB="attached" />
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14, marginTop: 14 }}>
        <StreamDifferencePlot points={result.points} unit={unit} />
        <BlandAltmanScatter points={result.points} stats={stats} unit={unit} />
      </div>

      {result.zone_breakdown && result.zone_breakdown.length > 0 && (
        <div style={{ marginTop: 14 }}>
          <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", letterSpacing: "0.08em", marginBottom: 10 }}>
            AGREEMENT BY ZONE
          </div>
          {result.zone_breakdown.map((zone, i) => (
            <div key={zone.zone_name} style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 8, fontSize: 12 }}>
              <span style={{ width: 10, height: 10, borderRadius: 3, flexShrink: 0, background: ZONE_COLORS[i % ZONE_COLORS.length] }} />
              <span style={{ width: 110, color: "var(--ink)" }}>{zone.zone_name}</span>
              <span className="mono" style={{ color: "var(--ink3)" }}>
                {zone.sample_count === 0 ? "no data" : `bias ${zone.bias!.toFixed(1)} ${unit}, n=${zone.sample_count}`}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function FieldSourcesCard({
  activityId,
  field,
  label,
  unit,
  sources,
}: {
  activityId: string;
  field: StreamComparisonField;
  label: string;
  unit: string;
  sources: ActivitySourceSummary[];
}) {
  const queryClient = useQueryClient();
  const [compareWith, setCompareWith] = useState<string | null>(null);
  const setPrimaryMutation = useMutation({
    mutationFn: (sourceId: string) => setActivityPrimarySource(activityId, field, sourceId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity", activityId] });
      queryClient.invalidateQueries({ queryKey: ["activity-sources", activityId] });
    },
  });

  if (sources.length === 0) return null;

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "20px 22px" }}>
      <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", letterSpacing: "0.08em", marginBottom: 14 }}>
        {label.toUpperCase()}
      </div>
      {sources.map((source) => (
        <div key={source.id} style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 10 }}>
          <input
            type="radio"
            name={`primary-${field}`}
            checked={source.is_primary}
            disabled={source.is_primary || setPrimaryMutation.isPending}
            onChange={() => setPrimaryMutation.mutate(source.id)}
            aria-label={`Make ${source.label} the primary ${field} source`}
          />
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)" }}>
              {source.label}
              {source.device ? ` (${source.device})` : ""}
            </div>
            <div className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>
              {source.origin}
              {source.source_kind ? ` · ${source.source_kind}` : ""}
              {source.is_primary ? " · primary" : ""}
            </div>
          </div>
          <button
            onClick={() => setCompareWith(compareWith === source.id ? null : source.id)}
            style={{
              border: "1px solid var(--line)",
              borderRadius: 6,
              padding: "3px 8px",
              fontSize: 11,
              fontWeight: 600,
              background: compareWith === source.id ? "var(--elev)" : "transparent",
              color: compareWith === source.id ? "var(--ink)" : "var(--ink3)",
            }}
          >
            {compareWith === source.id ? "Hide comparison" : "Compare to primary"}
          </button>
        </div>
      ))}
      {compareWith && <StreamComparisonPanel activityId={activityId} field={field} sourceB={compareWith} unit={unit} />}
    </div>
  );
}

/** Issue #503 (phase 4): per-field source selector (phases 1-3 built list/set-primary, but never
 * a frontend consumer for them) plus, once a non-primary source is picked, its agreement
 * comparison against the activity's current primary. */
export function SourcesTab({ activity }: { activity: Activity }) {
  const sourcesQuery = useQuery({
    queryKey: ["activity-sources", activity.id],
    queryFn: () => listActivitySources(activity.id),
  });

  if (sourcesQuery.isLoading) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading…</div>;
  }
  const sources = sourcesQuery.data ?? [];
  if (sources.length === 0) {
    return (
      <div style={{ color: "var(--ink3)", fontSize: 13 }}>
        No alternate sensor streams stored for this activity - nothing has been attached onto it from another
        activity, and no second power candidate was found at parse time.
      </div>
    );
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      {FIELDS.map((f) => (
        <FieldSourcesCard
          key={f.key}
          activityId={activity.id}
          field={f.key}
          label={f.label}
          unit={f.unit}
          sources={sources.filter((s) => s.sensor_kind === f.key)}
        />
      ))}
    </div>
  );
}
