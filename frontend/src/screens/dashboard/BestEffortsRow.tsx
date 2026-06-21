import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listBestEfforts } from "../../api/athletes";
import { Card } from "../../components/Card";
import type { BestEffortKind, BestEffortPeriod } from "../../api/types";

const METRICS: { kind: BestEffortKind; label: string }[] = [
  { kind: "cycling_power", label: "Bike power" },
  { kind: "running_power", label: "Run power" },
  { kind: "running_pace", label: "Pace" },
];

const PERIODS: { value: BestEffortPeriod; label: string }[] = [
  { value: "3m", label: "3 mo" },
  { value: "1y", label: "1 yr" },
  { value: "all", label: "All time" },
];

function chipButton(active: boolean): React.CSSProperties {
  return {
    border: "1px solid var(--line)",
    borderRadius: 8,
    padding: "6px 12px",
    fontSize: 13,
    fontWeight: 600,
    background: active ? "var(--elev)" : "transparent",
    color: active ? "var(--ink)" : "var(--ink3)",
  };
}

export function BestEffortsRow({ athleteId }: { athleteId: string }) {
  const [kind, setKind] = useState<BestEffortKind>("cycling_power");
  const [period, setPeriod] = useState<BestEffortPeriod>("3m");

  const { data } = useQuery({
    queryKey: ["best-efforts", athleteId, kind, period],
    queryFn: () => listBestEfforts(athleteId, kind, period),
  });

  return (
    <Card>
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 14, flexWrap: "wrap", gap: 10 }}>
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Best efforts</h2>
        <div style={{ display: "flex", gap: 16 }}>
          <div style={{ display: "flex", gap: 4 }}>
            {METRICS.map((m) => (
              <button key={m.kind} style={chipButton(kind === m.kind)} onClick={() => setKind(m.kind)}>
                {m.label}
              </button>
            ))}
          </div>
          <div style={{ display: "flex", gap: 4 }}>
            {PERIODS.map((p) => (
              <button key={p.value} style={chipButton(period === p.value)} onClick={() => setPeriod(p.value)}>
                {p.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div style={{ display: "flex", gap: 12, overflowX: "auto" }}>
        {data?.data.length ? (
          data.data.map((effort) => (
            <div
              key={effort.window}
              style={{
                minWidth: 110,
                padding: 12,
                borderRadius: 10,
                border: "1px solid var(--line)",
                background: "var(--elev)",
              }}
            >
              <div style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 6 }}>{effort.window}</div>
              <div className="mono" style={{ fontSize: 20, fontWeight: 600 }}>
                {effort.value}
              </div>
              <div style={{ fontSize: 11, color: "var(--ink2)" }}>{effort.unit}</div>
            </div>
          ))
        ) : (
          <div style={{ color: "var(--ink3)", fontSize: 13 }}>No best efforts yet for this period.</div>
        )}
      </div>
    </Card>
  );
}
