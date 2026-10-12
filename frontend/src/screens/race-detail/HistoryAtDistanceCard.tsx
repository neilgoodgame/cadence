import { Link } from "react-router-dom";
import type { Race } from "../../api/types";
import { formatDuration } from "../../lib/format";
import { resultSeconds } from "./raceHistory";

/** README §4.2.3 "History at this distance" card - other past races at (roughly) the same
 * distance, with a PB badge or the difference vs PB. */
export function HistoryAtDistanceCard({ races, pbS }: { races: Race[]; pbS: number | null }) {
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 18, minWidth: 290 }}>
      <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", marginBottom: 10 }}>
        History at this distance
      </div>
      {races.length === 0 ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>No other races at this distance yet.</div>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {races.map((r) => {
            const s = resultSeconds(r);
            const isPb = s != null && pbS != null && s === pbS;
            const vsPb = s != null && pbS != null ? s - pbS : null;
            return (
              <Link key={r.id} to={`/races/${r.id}`} style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", textDecoration: "none", color: "inherit", fontSize: 13 }}>
                <span>{r.name} <span style={{ color: "var(--ink3)", fontSize: 11.5 }}>{r.date}</span></span>
                <span className="mono" style={{ display: "flex", alignItems: "center", gap: 6 }}>
                  {s != null ? formatDuration(s) : "–"}
                  {isPb ? (
                    <span style={{ fontSize: 9.5, fontWeight: 700, color: "#2fa66a", border: "1px solid #2fa66a", borderRadius: 4, padding: "0 4px" }}>PB</span>
                  ) : vsPb != null ? (
                    <span style={{ fontSize: 11, color: "var(--ink3)" }}>+{formatDuration(vsPb)}</span>
                  ) : null}
                </span>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
