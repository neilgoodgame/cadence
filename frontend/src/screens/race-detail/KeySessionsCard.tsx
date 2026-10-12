import { useState } from "react";
import { Link } from "react-router-dom";
import type { KeySessionItem, KeySessionReason, KeySessions, Sport } from "../../api/types";
import { sportColor, sportLabel } from "../../lib/sportColors";

const SPORT_ORDER: Sport[] = ["bike", "run", "swim", "walk", "row", "multisport", "transition"];

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

function pageButtonStyle(disabled: boolean): React.CSSProperties {
  return {
    border: "1px solid var(--line)",
    background: "none",
    borderRadius: 7,
    fontSize: 12,
    fontWeight: 600,
    padding: "4px 10px",
    cursor: disabled ? "default" : "pointer",
    color: disabled ? "var(--ink3)" : "var(--ink2)",
    opacity: disabled ? 0.5 : 1,
  };
}

const ALL_REASONS = Object.keys(REASON_LABEL) as KeySessionReason[];

function FilterChip({
  label,
  color,
  border,
  active,
  count,
  onToggle,
}: {
  label: string;
  color: string;
  border: string;
  active: boolean;
  count: number;
  onToggle: () => void;
}) {
  return (
    <button
      onClick={onToggle}
      disabled={count === 0}
      style={{
        fontSize: 10,
        fontWeight: 700,
        padding: "2px 9px",
        borderRadius: 20,
        cursor: count === 0 ? "default" : "pointer",
        background: active ? color : "none",
        color: active ? "var(--card)" : count === 0 ? "var(--ink3)" : color,
        border,
        opacity: count === 0 ? 0.4 : 1,
      }}
    >
      {label} ({count})
    </button>
  );
}

/** README §4.2.10 "Key sessions" card. Header: "KEY SESSIONS IN THIS BLOCK" for an A race's
 * full block, "AROUND THE RACE" for a B/C race's 5-week window. The backend returns every
 * candidate session found (unpaginated) - this card paginates client-side, using the athlete's
 * race_key_session_cap preference as the page size. Give this component a `key={race.id}` from
 * the caller so `page` resets when navigating between races. */
export function KeySessionsCard({
  keySessions,
  isARace,
  sport,
  pageSize,
}: {
  keySessions: KeySessions;
  isARace: boolean;
  sport: Sport | null;
  pageSize: number;
}) {
  const [page, setPage] = useState(0);
  const [activeReasons, setActiveReasons] = useState<Set<KeySessionReason>>(new Set(ALL_REASONS));
  const [activeSports, setActiveSports] = useState<Set<Sport> | null>(null);

  const total = keySessions.items.length;
  const countByReason = ALL_REASONS.reduce(
    (acc, reason) => {
      acc[reason] = keySessions.items.filter((item) => item.reason === reason).length;
      return acc;
    },
    {} as Record<KeySessionReason, number>,
  );
  const sportsPresent = SPORT_ORDER.filter((s) => keySessions.items.some((item) => item.sport === s));
  const countBySport = sportsPresent.reduce(
    (acc, s) => {
      acc[s] = keySessions.items.filter((item) => item.sport === s).length;
      return acc;
    },
    {} as Record<Sport, number>,
  );
  // null (not yet touched) behaves as "every sport present" without needing an effect to seed it
  // once sportsPresent is known.
  const effectiveActiveSports = activeSports ?? new Set(sportsPresent);

  const filteredItems = keySessions.items.filter(
    (item) => activeReasons.has(item.reason) && (item.sport == null || effectiveActiveSports.has(item.sport)),
  );
  const filteredTotal = filteredItems.length;
  const pageCount = Math.max(1, Math.ceil(filteredTotal / pageSize));
  const clampedPage = Math.min(page, pageCount - 1);
  const pageItems = filteredItems.slice(clampedPage * pageSize, (clampedPage + 1) * pageSize);

  function toggleReason(reason: KeySessionReason) {
    setPage(0);
    setActiveReasons((prev) => {
      const next = new Set(prev);
      if (next.has(reason)) next.delete(reason);
      else next.add(reason);
      return next;
    });
  }

  function toggleSport(sport: Sport) {
    setPage(0);
    setActiveSports((prev) => {
      const next = new Set(prev ?? sportsPresent);
      if (next.has(sport)) next.delete(sport);
      else next.add(sport);
      return next;
    });
  }

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 12, flexWrap: "wrap", gap: 8 }}>
        <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)" }}>
          {isARace ? "Key sessions in this block" : "Around the race"}
        </div>
        <span className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>
          {filteredTotal === total ? `${total} total` : `${filteredTotal} of ${total}`}
        </span>
      </div>
      {total > 0 && (
        <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginBottom: sportsPresent.length > 1 ? 8 : 14 }}>
          {ALL_REASONS.map((reason) => {
            const style = reasonStyle(reason, sport);
            return (
              <FilterChip
                key={reason}
                label={REASON_LABEL[reason]}
                color={style.color as string}
                border={style.border as string}
                active={activeReasons.has(reason)}
                count={countByReason[reason]}
                onToggle={() => toggleReason(reason)}
              />
            );
          })}
        </div>
      )}
      {sportsPresent.length > 1 && (
        <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginBottom: 14 }}>
          {sportsPresent.map((s) => (
            <FilterChip
              key={s}
              label={sportLabel(s)}
              color={sportColor(s)}
              border={`1px solid ${sportColor(s)}`}
              active={effectiveActiveSports.has(s)}
              count={countBySport[s]}
              onToggle={() => toggleSport(s)}
            />
          ))}
        </div>
      )}
      {total === 0 ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>No key sessions found yet.</div>
      ) : filteredTotal === 0 ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>No sessions match the selected filters.</div>
      ) : (
        <>
          <div>
            {pageItems.map((item, i) => (
              <Row key={clampedPage * pageSize + i} item={item} sport={sport} />
            ))}
          </div>
          {pageCount > 1 && (
            <div style={{ display: "flex", justifyContent: "center", alignItems: "center", gap: 12, marginTop: 14 }}>
              <button onClick={() => setPage(clampedPage - 1)} disabled={clampedPage === 0} style={pageButtonStyle(clampedPage === 0)}>
                ← Prev
              </button>
              <span className="mono" style={{ fontSize: 11.5, color: "var(--ink3)" }}>
                Page {clampedPage + 1} of {pageCount}
              </span>
              <button
                onClick={() => setPage(clampedPage + 1)}
                disabled={clampedPage === pageCount - 1}
                style={pageButtonStyle(clampedPage === pageCount - 1)}
              >
                Next →
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
