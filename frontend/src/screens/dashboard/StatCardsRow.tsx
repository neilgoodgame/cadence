import { Card } from "../../components/Card";
import { StatCell } from "../../components/StatCell";
import type { FitnessPoint } from "../../api/types";

function formLabel(tsb: number): string {
  if (tsb > 5) return "Fresh & ready";
  if (tsb > -10) return "Building fitness";
  return "Carrying fatigue";
}

function formColor(tsb: number): string {
  if (tsb > 5) return "#2fa66a";
  if (tsb > -10) return "#f0a02e";
  return "#e0442e";
}

export function StatCardsRow({ points, weekTss }: { points: FitnessPoint[]; weekTss: number }) {
  const latest = points.at(-1);
  const previous = points.at(-2);
  if (!latest) {
    return null;
  }
  const ctlDelta = previous ? latest.ctl - previous.ctl : 0;
  const atlDelta = previous ? latest.atl - previous.atl : 0;

  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 13 }}>
      <Card>
        <StatCell
          label="Fitness"
          value={Math.round(latest.ctl)}
          color="#3d7fd6"
          subtitle={`${ctlDelta >= 0 ? "+" : ""}${ctlDelta.toFixed(1)} CTL · 42-day load`}
        />
      </Card>
      <Card>
        <StatCell
          label="Fatigue"
          value={Math.round(latest.atl)}
          color="#f0a02e"
          subtitle={`${atlDelta >= 0 ? "+" : ""}${atlDelta.toFixed(1)} ATL · 7-day load`}
        />
      </Card>
      <Card>
        <StatCell label="Form" value={Math.round(latest.tsb)} color={formColor(latest.tsb)} subtitle={formLabel(latest.tsb)} />
      </Card>
      <Card>
        <StatCell label="7-day Load" value={Math.round(weekTss)} unit="TSS" color="var(--ember)" subtitle="last 7 days" />
      </Card>
    </div>
  );
}
