import { useMemo } from "react";
import type { Activity, Sport } from "../../api/types";
import { sportColor, sportLabel } from "../../lib/sportColors";

const SPORTS: Sport[] = ["bike", "run", "swim", "walk"];

export function SportDistribution({ activities }: { activities: Activity[] }) {
  const rows = useMemo(() => {
    const bySport = new Map<Sport, { count: number; hours: number }>();
    for (const sport of SPORTS) {
      bySport.set(sport, { count: 0, hours: 0 });
    }
    for (const activity of activities) {
      const entry = bySport.get(activity.sport)!;
      entry.count += 1;
      entry.hours += activity.moving_time / 3600;
    }
    const totalHours = Array.from(bySport.values()).reduce((sum, v) => sum + v.hours, 0);
    return SPORTS.map((sport) => ({ sport, ...bySport.get(sport)!, totalHours }))
      .filter((r) => r.count > 0)
      .sort((a, b) => b.hours - a.hours);
  }, [activities]);

  const totalHours = rows.reduce((sum, r) => sum + r.hours, 0);

  return (
    <div>
      <div style={{ fontSize: 12, color: "var(--ink3)", marginBottom: 14 }}>
        This training block · {totalHours.toFixed(1)}h total
      </div>

      <div style={{ display: "flex", height: 10, borderRadius: 5, overflow: "hidden", marginBottom: 16 }}>
        {rows.map((r) => (
          <div key={r.sport} style={{ width: `${(r.hours / totalHours) * 100}%`, background: sportColor(r.sport) }} />
        ))}
      </div>

      <table style={{ width: "100%", fontSize: 13 }}>
        <tbody>
          {rows.map((r) => (
            <tr key={r.sport}>
              <td style={{ padding: "6px 0", display: "flex", alignItems: "center", gap: 8 }}>
                <span style={{ width: 8, height: 8, borderRadius: 2, background: sportColor(r.sport) }} />
                {sportLabel(r.sport)}
              </td>
              <td style={{ padding: "6px 0", color: "var(--ink2)" }}>{r.count}×</td>
              <td className="mono" style={{ padding: "6px 0", color: "var(--ink2)" }}>
                {r.hours.toFixed(1)}h
              </td>
              <td className="mono" style={{ padding: "6px 0", textAlign: "right", color: "var(--ink2)" }}>
                {Math.round((r.hours / totalHours) * 100)}%
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
