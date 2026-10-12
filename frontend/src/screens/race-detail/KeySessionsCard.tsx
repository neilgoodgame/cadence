import { Link } from "react-router-dom";
import type { KeySessionItem, KeySessionReason, KeySessions, Sport } from "../../api/types";
import { sportColor } from "../../lib/sportColors";

const REASON_LABEL: Record<KeySessionReason, string> = {
  race: "Race",
  tagged: "Key",
  long: "Long",
  quality: "Quality",
  best_effort: "Best effort",
  planned: "Planned",
};

function reasonStyle(reason: KeySessionReason, sport: Sport | null): React.CSSProperties {
  switch (reason) {
    case "race":
      return { color: "var(--ember)", border: "1px solid var(--ember)" };
    case "tagged":
      return { color: "#c084fc", border: "1px solid #c084fc" };
    case "quality":
      return { color: sport ? sportColor(sport) : "var(--ink2)", border: `1px solid ${sport ? sportColor(sport) : "var(--ink2)"}` };
    case "long":
      return { color: "var(--ink2)", border: "1px solid var(--ink2)" };
    case "best_effort":
      return { color: "#f0a02e", border: "1px solid #f0a02e" };
    case "planned":
      return { color: "var(--ink3)", border: "1px dashed var(--ink3)" };
  }
}

function Row({ item, sport }: { item: KeySessionItem; sport: Sport | null }) {
  const to = item.scheduled_workout_id ? `/scheduled/${item.scheduled_workout_id}` : item.activity_id ? `/activities/${item.activity_id}` : null;
  const content = (
    <>
      <span className="mono" style={{ fontSize: 11.5, color: "var(--ink3)", width: 72, flexShrink: 0 }}>{item.date}</span>
      <span style={{ flex: 1, minWidth: 0, fontSize: 13, fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{item.name}</span>
      <span
        style={{ fontSize: 10, fontWeight: 700, padding: "1px 7px", borderRadius: 20, flexShrink: 0, ...reasonStyle(item.reason, sport) }}
      >
        {REASON_LABEL[item.reason]}
      </span>
      <span className="mono" style={{ fontSize: 11.5, color: "var(--ink3)", flexShrink: 0, minWidth: 60, textAlign: "right" }}>
        {[item.km != null && `${item.km.toFixed(1)} km`, item.tss != null && `${item.tss.toFixed(0)} TSS`].filter(Boolean).join(" · ") || item.label}
      </span>
    </>
  );

  const rowStyle: React.CSSProperties = { display: "flex", alignItems: "center", gap: 10, padding: "7px 0", borderBottom: "1px solid var(--line)", textDecoration: "none", color: "inherit" };

  return to ? <Link to={to} style={rowStyle}>{content}</Link> : <div style={rowStyle}>{content}</div>;
}

/** README §4.2.10 "Key sessions" card. Header: "KEY SESSIONS IN THIS BLOCK" for an A race's
 * full block, "AROUND THE RACE" for a B/C race's 5-week window. */
export function KeySessionsCard({
  keySessions,
  isARace,
  sport,
}: {
  keySessions: KeySessions;
  isARace: boolean;
  sport: Sport | null;
}) {
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 12 }}>
        <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)" }}>
          {isARace ? "Key sessions in this block" : "Around the race"}
        </div>
        <span className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>
          {keySessions.items.length} of {keySessions.total_candidates} · top {keySessions.items.length}
        </span>
      </div>
      {keySessions.items.length === 0 ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>No key sessions found yet.</div>
      ) : (
        <div>
          {keySessions.items.map((item, i) => (
            <Row key={i} item={item} sport={sport} />
          ))}
        </div>
      )}
    </div>
  );
}
