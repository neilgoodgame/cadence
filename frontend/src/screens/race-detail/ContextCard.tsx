import { Link } from "react-router-dom";
import type { Race, RaceBlock, RacePriority } from "../../api/types";
import { formatDuration } from "../../lib/format";

const PRIORITY_ADVICE: Record<RacePriority, string> = {
  A: "",
  B: "A tune-up - race it hard, but prioritize the block around it.",
  C: "Low-key - run this as training, not an all-out effort.",
};

/** README §4.2.2 - context card varies by whether this is an A race with tune-ups, a B/C
 * race nested in a parent's block, or a standalone B/C race. */
export function ContextCard({
  race,
  tuneUps,
  parent,
  parentPrediction,
}: {
  race: Race;
  tuneUps: Race[];
  parent: Race | null;
  parentPrediction: RaceBlock["prediction"];
}) {
  if (race.priority === "A") {
    if (tuneUps.length === 0) return null;
    return (
      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "14px 20px" }}>
        <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", marginBottom: 8 }}>
          Tune-up races in this block
        </div>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          {tuneUps.map((t) => (
            <Link
              key={t.id}
              to={`/races/${t.id}`}
              style={{ fontSize: 12.5, padding: "4px 10px", borderRadius: 20, border: "1px solid var(--line)", color: "var(--ink2)", textDecoration: "none" }}
            >
              {t.priority} · {t.name}
            </Link>
          ))}
        </div>
      </div>
    );
  }

  if (parent) {
    const week = race.block_start && race.block_weeks
      ? Math.floor((new Date(race.date + "T00:00:00").getTime() - new Date(race.block_start + "T00:00:00").getTime()) / (7 * 86_400_000)) + 1
      : null;

    return (
      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "14px 20px" }}>
        <div style={{ fontSize: 13.5, fontWeight: 600 }}>
          Part of the{" "}
          <Link to={`/races/${parent.id}`} style={{ color: "var(--ember)" }}>
            {parent.name}
          </Link>{" "}
          block{week != null && race.block_weeks ? ` · week ${week} of ${race.block_weeks}` : ""}
        </div>
        {PRIORITY_ADVICE[race.priority] && (
          <div style={{ fontSize: 12.5, color: "var(--ink3)", marginTop: 4 }}>{PRIORITY_ADVICE[race.priority]}</div>
        )}
        {race.result_time && parentPrediction && parentPrediction.from_race_id === race.id && (
          <div style={{ fontSize: 12.5, color: "var(--ink2)", marginTop: 6 }}>
            Predicts <span className="mono" style={{ fontWeight: 700 }}>{formatDuration(parentPrediction.time_s)}</span> for{" "}
            {parent.name}.
          </div>
        )}
      </div>
    );
  }

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "14px 20px" }}>
      <div style={{ fontSize: 13, color: "var(--ink3)" }}>
        This {race.priority} race isn't part of a training block.
      </div>
    </div>
  );
}
