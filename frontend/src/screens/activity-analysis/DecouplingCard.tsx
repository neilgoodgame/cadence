import { Fragment } from "react";
import { Link } from "react-router-dom";
import type { Activity } from "../../api/types";
import {
  BAND_COLOR,
  bandLabel,
  bandMarkerPct,
  decouplingBand,
  decouplingChecks,
  decouplingSummaryText,
  type DecouplingPrefs,
  durabilityTiles,
  fmtHalfTimeRange,
  isNeverComputed,
  notScoredReasonText,
} from "./decoupling";

// pass: null means the check was never evaluated at all (an activity that predates this
// feature and hasn't been recomputed) - shown neutral, not a false green checkmark.
const chipStyle = (pass: boolean | null): React.CSSProperties => ({
  display: "inline-flex",
  alignItems: "center",
  gap: 5,
  fontFamily: "'JetBrains Mono',monospace",
  fontSize: 11,
  fontWeight: 600,
  padding: "3px 9px",
  borderRadius: 6,
  color: pass === false ? "var(--ink)" : "var(--ink2)",
  background: pass === false ? "rgba(224,68,46,0.14)" : "transparent",
  border: `1px solid ${pass === false ? "#e0442e" : "var(--line)"}`,
  opacity: pass === null ? 0.6 : 1,
  whiteSpace: "nowrap",
});

function chipIcon(pass: boolean | null): string {
  if (pass === null) return "–"; // en dash - "not evaluated"
  return pass ? "✓" : "✗";
}

/** Activity Analysis → Stats tab "Aerobic decoupling · Pw:HR" card - only rendered for bike/run
 * activities with a power stream (callers are responsible for that gate, matching the design
 * spec: "Only render it for ride/run activities with power. Otherwise hide it entirely."). */
export function DecouplingCard({ activity, athlete }: { activity: Activity; athlete: DecouplingPrefs }) {
  const checks = decouplingChecks(activity, athlete);

  return (
    <div
      style={{
        gridColumn: "1 / -1",
        background: "var(--card)",
        border: "1px solid var(--line)",
        borderRadius: 14,
        padding: "20px 22px",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, flexWrap: "wrap", marginBottom: 16 }}>
        <div>
          <span className="mono" style={{ fontSize: 11, letterSpacing: "0.08em", color: "var(--ink3)", textTransform: "uppercase" }}>
            AEROBIC DECOUPLING
          </span>
          <span style={{ fontSize: 11, color: "var(--ink2)", marginLeft: 4 }}>&middot; Pw:HR</span>
        </div>
        {activity.decoupling_hot && (
          <span
            title="Air ≥ 25 °C or avg core ≥ 38.0 °C"
            style={{
              display: "inline-flex", alignItems: "center", gap: 7, border: "1.5px solid #f0a02e", borderRadius: 20,
              padding: "2px 10px", fontSize: 11.5, fontWeight: 600, color: "var(--ink)",
            }}
          >
            <span style={{ width: 8, height: 8, borderRadius: "50%", border: "2px solid #f0a02e", boxSizing: "border-box" }} />
            Hot session
          </span>
        )}
      </div>

      {activity.decoupling_qualified ? (
        <div style={{ display: "grid", gridTemplateColumns: "minmax(220px,.9fr) minmax(0,1.6fr)", gap: 28 }}>
          <div>
            <div className="mono" style={{ fontSize: 34, fontWeight: 600, color: "var(--ink)" }}>
              {activity.decoupling_pct!.toFixed(1)}%
            </div>
            <div
              style={{
                display: "inline-block", marginTop: 6, fontSize: 12, fontWeight: 700, color: "var(--ink)",
                background: BAND_COLOR[decouplingBand(activity.decoupling_pct!)], borderRadius: 20, padding: "3px 10px",
              }}
            >
              {bandLabel(decouplingBand(activity.decoupling_pct!))}
            </div>
            <div style={{ fontSize: 12.5, color: "var(--ink2)", marginTop: 10, lineHeight: 1.45 }}>
              {decouplingSummaryText(activity)}
            </div>
            <div style={{ position: "relative", height: 8, borderRadius: 4, overflow: "visible", marginTop: 14, display: "flex" }}>
              <div style={{ flex: 1, background: BAND_COLOR.good, borderRadius: "4px 0 0 4px" }} />
              <div style={{ flex: 1, background: BAND_COLOR.moderate }} />
              <div style={{ flex: 1, background: BAND_COLOR.high, borderRadius: "0 4px 4px 0" }} />
              <div
                style={{
                  position: "absolute", top: -2, left: `${bandMarkerPct(activity.decoupling_pct!)}%`,
                  width: 4, height: 16, background: "var(--ink)", border: "2px solid var(--card)",
                  borderRadius: 2, transform: "translateX(-50%)",
                }}
              />
            </div>
            <div className="mono" style={{ display: "flex", justifyContent: "space-between", fontSize: 10.5, color: "var(--ink3)", marginTop: 6 }}>
              <span>0%</span>
              <span>Good &lt; 5</span>
              <span>Moderate 5&ndash;10</span>
              <span>High &gt; 10</span>
            </div>
          </div>

          <div>
            <div style={{ display: "grid", gridTemplateColumns: "110px repeat(3,minmax(0,1fr))", columnGap: 14, fontSize: 13 }}>
              <span />
              <span className="mono" style={{ fontSize: 10, fontWeight: 700, textTransform: "uppercase", color: "var(--ink3)" }}>Power</span>
              <span className="mono" style={{ fontSize: 10, fontWeight: 700, textTransform: "uppercase", color: "var(--ink3)" }}>Heart rate</span>
              <span className="mono" style={{ fontSize: 10, fontWeight: 700, textTransform: "uppercase", color: "var(--ink3)" }}>EF (W/bpm)</span>
              {activity.decoupling_halves.map((half, i) => (
                <Fragment key={i}>
                  <div style={{ padding: "9px 0", borderTop: "1px solid var(--line)" }}>
                    <div style={{ fontWeight: 600, color: "var(--ink)" }}>{i === 0 ? "1st half" : "2nd half"}</div>
                    <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", marginTop: 2 }}>
                      {fmtHalfTimeRange(half.start_s, half.end_s)}
                    </div>
                  </div>
                  <span className="mono" style={{ padding: "9px 0", borderTop: "1px solid var(--line)" }}>
                    {half.power != null ? `${half.power} W` : "—"}
                  </span>
                  <span className="mono" style={{ padding: "9px 0", borderTop: "1px solid var(--line)" }}>
                    {half.hr != null ? `${half.hr} bpm` : "—"}
                  </span>
                  <span className="mono" style={{ padding: "9px 0", borderTop: "1px solid var(--line)" }}>
                    {half.ef != null ? half.ef.toFixed(2) : "—"}
                  </span>
                </Fragment>
              ))}
            </div>
            {activity.decoupling_hot && (
              <div style={{ fontSize: 12, color: "var(--ink2)", marginTop: 12, lineHeight: 1.45 }}>
                Hot session &mdash; {activity.decoupling_avg_temp != null ? `${activity.decoupling_avg_temp} °C air` : ""}
                {activity.decoupling_avg_temp != null && activity.decoupling_avg_core != null ? ", " : ""}
                {activity.decoupling_avg_core != null ? `${activity.decoupling_avg_core} °C average core` : ""}. Heat raises
                drift on its own, so this point is marked on your trend rather than hidden.
              </div>
            )}
          </div>
        </div>
      ) : (
        <div>
          <div style={{ fontSize: 14, fontWeight: 700, color: "var(--ink)" }}>
            Not scored &mdash; {notScoredReasonText(activity, athlete)}
          </div>
          <div style={{ fontSize: 12.5, color: "var(--ink2)", marginTop: 6, lineHeight: 1.45, maxWidth: 640 }}>
            {isNeverComputed(activity)
              ? "This activity hasn't been scored yet - use ↺ Recompute stats above to calculate it."
              : "Decoupling only means something on a steady, aerobic session — intervals and surges raise heart rate for reasons other than fatigue."}
          </div>
        </div>
      )}

      <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 16 }}>
        {checks.map((c) => (
          <span key={c.label} title={c.tooltip} style={chipStyle(c.pass)}>
            {chipIcon(c.pass)} {c.label}
          </span>
        ))}
      </div>

      <div style={{ borderTop: "1px solid var(--line)", marginTop: 18, paddingTop: 16 }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, flexWrap: "wrap", marginBottom: 10 }}>
          <span className="mono" style={{ fontSize: 11, letterSpacing: "0.08em", color: "var(--ink3)", textTransform: "uppercase" }}>
            DURABILITY &middot; best power once tired
          </span>
          <Link to="/best-efforts?view=durability" style={{ fontSize: 12, fontWeight: 600, color: "var(--ember)", textDecoration: "none" }}>
            Durability trends &rarr;
          </Link>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(200px,1fr))", gap: 10 }}>
          {durabilityTiles(activity).map((tile) => (
            <div key={tile.label} style={{ background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 10, padding: "10px 12px" }}>
              <div className="mono" style={{ fontSize: 10, fontWeight: 700, textTransform: "uppercase", color: "var(--ink3)" }}>
                {tile.label}
              </div>
              <div className="mono" style={{ fontSize: 15, fontWeight: 600, color: tile.reached ? "var(--ink)" : "var(--ink3)", marginTop: 4 }}>
                {tile.value}
              </div>
              <div style={{ fontSize: 11.5, color: "var(--ink3)", marginTop: 3 }}>{tile.sub}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
