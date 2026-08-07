import { useState, useCallback } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateAthlete, recomputeBestEffortsStream, recomputeStatsStream, trimBestEfforts } from "../../api/athletes";
import { useAuth } from "../../auth/AuthContext";
import type { BestEffortKind } from "../../api/types";

const KINDS: { kind: BestEffortKind; label: string }[] = [
  { kind: "running_hr", label: "Running HR" },
  { kind: "running_power", label: "Running Power" },
  { kind: "running_pace", label: "Running Pace" },
  { kind: "cycling_hr", label: "Cycling HR" },
  { kind: "cycling_power", label: "Cycling Power" },
];

const inputStyle: React.CSSProperties = {
  width: 80,
  padding: "8px 10px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 14,
  color: "var(--ink)",
};

const btnStyle: React.CSSProperties = {
  padding: "8px 14px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 13,
  fontWeight: 600,
  color: "var(--ink2)",
  cursor: "pointer",
};

interface RecomputeState {
  running: boolean;
  current: number;
  total: number;
  activeKind: BestEffortKind | "all" | null;
  result: string | null;
  error: boolean;
}

const IDLE: RecomputeState = { running: false, current: 0, total: 0, activeKind: null, result: null, error: false };

function ProgressBar({ current, total }: { current: number; total: number }) {
  const pct = total > 0 ? Math.round((current / total) * 100) : 0;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      <div style={{ height: 6, borderRadius: 3, background: "var(--line)", overflow: "hidden" }}>
        <div style={{ height: "100%", width: `${pct}%`, background: "var(--accent,#5b7cf7)", borderRadius: 3, transition: "width 0.2s" }} />
      </div>
      <span style={{ fontSize: 12, color: "var(--ink3)" }}>
        {current} / {total} activities
      </span>
    </div>
  );
}

export function BestEffortsTab() {
  const { user, setUser } = useAuth();
  const qc = useQueryClient();
  const savedTopN = user?.best_effort_top_n ?? 10;
  const [allActivities, setAllActivities] = useState(savedTopN === 0);
  const [topN, setTopN] = useState(savedTopN === 0 ? 10 : savedTopN);
  const [recompute, setRecompute] = useState<RecomputeState>(IDLE);
  const [statsRecompute, setStatsRecompute] = useState<{
    running: boolean;
    current: number;
    total: number;
    updated: number | null;
  }>({ running: false, current: 0, total: 0, updated: null });
  const effectiveTopN = allActivities ? 0 : topN;

  const isDecreasing = savedTopN === 0
    ? effectiveTopN > 0
    : effectiveTopN > 0 && effectiveTopN < savedTopN;
  const isIncreasing = effectiveTopN === 0
    ? savedTopN > 0
    : effectiveTopN > savedTopN;

  const saveMutation = useMutation({
    mutationFn: async () => {
      const updated = await updateAthlete(user!.id, { best_effort_top_n: effectiveTopN });
      setUser(updated);
      if (isDecreasing) await trimBestEfforts(user!.id);
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["best-efforts"] }); },
  });

  const startRecompute = useCallback(async (kind?: BestEffortKind, saveFirst?: boolean) => {
    setRecompute({ running: true, current: 0, total: 0, activeKind: kind ?? "all", result: null, error: false });
    try {
      if (saveFirst) {
        const updated = await updateAthlete(user!.id, { best_effort_top_n: effectiveTopN });
        setUser(updated);
      }
      for await (const event of recomputeBestEffortsStream(user!.id, kind)) {
        if (event.type === "progress") {
          setRecompute(s => ({ ...s, current: event.current, total: event.total }));
        } else {
          const label = kind ? KINDS.find(k => k.kind === kind)?.label ?? kind : "all";
          setRecompute({ running: false, current: event.processed, total: event.processed, activeKind: null, result: `Recomputed ${label} — ${event.processed} activities`, error: false });
          qc.invalidateQueries({ queryKey: ["best-efforts"] });
        }
      }
    } catch {
      setRecompute(s => ({ ...s, running: false, error: true }));
    }
  }, [user, effectiveTopN, setUser, qc]);

  const startStatsRecompute = useCallback(async () => {
    setStatsRecompute({ running: true, current: 0, total: 0, updated: null });
    for await (const event of recomputeStatsStream(user!.id)) {
      if (event.type === "progress") {
        setStatsRecompute(s => ({ ...s, current: event.current, total: event.total }));
      } else {
        setStatsRecompute({ running: false, current: event.updated, total: event.updated, updated: event.updated });
        qc.invalidateQueries({ queryKey: ["activities"] });
      }
    }
  }, [user, qc]);

  if (!user) return null;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 32, maxWidth: 560 }}>
      <section style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>Entries per window</h2>
        <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0, lineHeight: 1.6 }}>
          How many top efforts to track per time window (e.g. top {allActivities ? "all" : topN} for 5-minute power).
          Applied on the next upload and after a recompute.
        </p>
        <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13, cursor: "pointer" }}>
          <input type="checkbox" checked={allActivities} onChange={e => setAllActivities(e.target.checked)} />
          Store best efforts for every activity (no limit)
        </label>
        <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
          <input
            type="number" min={1} max={50} value={topN} disabled={allActivities}
            onChange={e => setTopN(Math.max(1, Math.min(50, Number(e.target.value))))}
            style={{ ...inputStyle, opacity: allActivities ? 0.4 : 1 }}
          />
          <button
            onClick={() => saveMutation.mutate()}
            disabled={saveMutation.isPending || recompute.running || effectiveTopN === savedTopN}
            style={{
              ...btnStyle,
              background: effectiveTopN !== savedTopN ? "var(--accent,#5b7cf7)" : "var(--elev)",
              color: effectiveTopN !== savedTopN ? "#fff" : "var(--ink2)",
              borderColor: effectiveTopN !== savedTopN ? "transparent" : "var(--line)",
              opacity: saveMutation.isPending ? 0.6 : 1,
            }}
          >
            {saveMutation.isPending ? "Saving…" : "Save"}
          </button>
          <button
            onClick={() => startRecompute(undefined, true)}
            disabled={saveMutation.isPending || recompute.running}
            style={{ ...btnStyle, opacity: recompute.running && recompute.activeKind === "all" ? 0.6 : 1 }}
          >
            {recompute.running && recompute.activeKind === "all" ? "Recomputing…" : "Save & recompute all"}
          </button>
          {saveMutation.isSuccess && !recompute.running && (
            <span style={{ fontSize: 13, color: "var(--ink3)" }}>
              {isDecreasing ? "Saved & trimmed" : "Saved"}
            </span>
          )}
        </div>
        {effectiveTopN !== savedTopN && isIncreasing && (
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: 0 }}>
            Increasing the limit requires a recompute to fill the new slots — use Save &amp; recompute all.
          </p>
        )}
      </section>

      <section style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>Recompute best efforts</h2>
        <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0, lineHeight: 1.6 }}>
          Recalculates best efforts from your stored activity records. Use this after changing the
          entries-per-window setting, or to fix records that were computed from bad data.
        </p>

        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {KINDS.map(({ kind, label }) => (
            <div key={kind} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "10px 14px", borderRadius: 8, background: "var(--elev)", border: "1px solid var(--line)" }}>
              <span style={{ fontSize: 13, fontWeight: 600 }}>{label}</span>
              <button
                onClick={() => startRecompute(kind)}
                disabled={recompute.running}
                style={{ ...btnStyle, padding: "6px 12px", fontSize: 12, opacity: recompute.running && recompute.activeKind === kind ? 0.6 : 1 }}
              >
                {recompute.running && recompute.activeKind === kind ? "Working…" : "Recompute"}
              </button>
            </div>
          ))}
        </div>

        <button
          onClick={() => startRecompute(undefined)}
          disabled={recompute.running}
          style={{ ...btnStyle, alignSelf: "flex-start", opacity: recompute.running && recompute.activeKind === "all" ? 0.6 : 1 }}
        >
          {recompute.running && recompute.activeKind === "all" ? "Working…" : "Recompute all"}
        </button>

        {recompute.running && recompute.total > 0 && (
          <ProgressBar current={recompute.current} total={recompute.total} />
        )}
        {recompute.running && recompute.total === 0 && (
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: 0 }}>Starting…</p>
        )}
        {recompute.result && !recompute.running && (
          <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0 }}>{recompute.result}</p>
        )}
        {recompute.error && (
          <p style={{ fontSize: 13, color: "var(--danger,#e04040)", margin: 0 }}>Recompute failed. Try again.</p>
        )}
      </section>

      <section style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>Recompute derived stats</h2>
        <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0, lineHeight: 1.6 }}>
          Backfills max power, cadence, elevation, calories, and TRIMP for every activity from its stored
          records. Use this after an upstream calculation fix, or for activities that never had these
          stats computed in the first place (e.g. imported activities).
        </p>

        <button
          onClick={() => startStatsRecompute()}
          disabled={statsRecompute.running}
          style={{ ...btnStyle, alignSelf: "flex-start", opacity: statsRecompute.running ? 0.6 : 1 }}
        >
          {statsRecompute.running ? "Working…" : "Recompute all"}
        </button>

        {statsRecompute.running && statsRecompute.total > 0 && (
          <ProgressBar current={statsRecompute.current} total={statsRecompute.total} />
        )}
        {statsRecompute.running && statsRecompute.total === 0 && (
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: 0 }}>Starting…</p>
        )}
        {statsRecompute.updated != null && !statsRecompute.running && (
          <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0 }}>Updated {statsRecompute.updated} activities</p>
        )}
      </section>
    </div>
  );
}
