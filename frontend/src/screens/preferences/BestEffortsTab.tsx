import { useCallback, useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  updateAthlete,
  recomputeBestEffortsStream,
  recomputeCurvesStream,
  recomputeStatsStream,
  recomputeThresholdHistoryStream,
  trimBestEfforts,
} from "../../api/athletes";
import { useAuth } from "../../auth/AuthContext";
import type { BestEffortKind, ThresholdFieldName } from "../../api/types";

const THRESHOLD_FIELDS: { field: ThresholdFieldName; label: string }[] = [
  { field: "ftp", label: "FTP" },
  { field: "critical_run_power", label: "Critical running power" },
  { field: "threshold_pace", label: "Threshold pace" },
];

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
  activeKind: BestEffortKind | "all" | null;
  current: number;
  total: number;
  result: string | null;
  error: boolean;
}

const IDLE: RecomputeState = { activeKind: null, current: 0, total: 0, result: null, error: false };

function formatElapsed(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

// Rough - the per-activity rate can vary a lot (a marathon's worth of samples costs far
// more than a 20-minute spin), so this is a ballpark, not a promise. Needs a few seconds
// of real throughput before it means anything.
function etaText(current: number, total: number, elapsedSeconds: number): string | null {
  if (current === 0 || elapsedSeconds < 3) return null;
  const remaining = Math.round((elapsedSeconds / current) * (total - current));
  return remaining <= 0 ? null : `~${formatElapsed(remaining)} left`;
}

// A large account can take a long time to recompute (each activity is its own DB
// round-trip + sliding-window calculation) - "Starting…" with no feedback at all is
// indistinguishable from actually being stuck. Ticks every second while `active`, resets
// to 0 whenever a fresh recompute starts.
function useElapsedSeconds(active: boolean): number {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    if (!active) return;
    const start = Date.now();
    const id = setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), 1000);
    return () => {
      clearInterval(id);
      setElapsed(0);
    };
  }, [active]);
  return elapsed;
}

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
    error: boolean;
  }>({ running: false, current: 0, total: 0, updated: null, error: false });
  const [windowDays, setWindowDays] = useState(user?.threshold_window_days ?? 112);
  const [sanityPct, setSanityPct] = useState(user?.threshold_sanity_pct ?? 30);
  const IDLE_FIELD_RECOMPUTE = { running: false, current: 0, total: 0, result: null as number | null, error: false };
  const [fieldRecompute, setFieldRecompute] = useState<Record<ThresholdFieldName, typeof IDLE_FIELD_RECOMPUTE>>({
    ftp: IDLE_FIELD_RECOMPUTE,
    critical_run_power: IDLE_FIELD_RECOMPUTE,
    threshold_pace: IDLE_FIELD_RECOMPUTE,
  });
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
    setRecompute({ activeKind: kind ?? "all", current: 0, total: 0, result: null, error: false });
    try {
      if (saveFirst) {
        const updated = await updateAthlete(user!.id, { best_effort_top_n: effectiveTopN });
        setUser(updated);
      }
      for await (const event of recomputeBestEffortsStream(user!.id, kind)) {
        if (event.type === "progress") {
          setRecompute(s => ({ ...s, current: event.current, total: event.total }));
        }
        else {
          const label = kind ? KINDS.find(k => k.kind === kind)?.label ?? kind : "all";
          setRecompute({
            activeKind: null, current: 0, total: 0, error: false,
            result: `Recomputed ${label} — ${event.processed} activities`,
          });
          qc.invalidateQueries({ queryKey: ["best-efforts"] });
        }
      }
    }
    catch {
      setRecompute(s => ({ ...s, activeKind: null, error: true }));
    }
  }, [user, effectiveTopN, setUser, qc]);

  const recomputeRunning = recompute.activeKind !== null;
  const recomputeElapsed = useElapsedSeconds(recomputeRunning);

  const startStatsRecompute = useCallback(async () => {
    setStatsRecompute({ running: true, current: 0, total: 0, updated: null, error: false });
    try {
      for await (const event of recomputeStatsStream(user!.id)) {
        if (event.type === "progress") {
          setStatsRecompute(s => ({ ...s, current: event.current, total: event.total }));
        } else {
          setStatsRecompute({ running: false, current: event.updated, total: event.updated, updated: event.updated, error: false });
          qc.invalidateQueries({ queryKey: ["activities"] });
        }
      }
    }
    catch {
      setStatsRecompute(s => ({ ...s, running: false, error: true }));
    }
  }, [user, qc]);

  const [curvesRecompute, setCurvesRecompute] = useState<RecomputeState>(IDLE);
  const curvesRecomputeRunning = curvesRecompute.activeKind !== null;
  const curvesElapsed = useElapsedSeconds(curvesRecomputeRunning);

  const startCurvesRecompute = useCallback(async () => {
    setCurvesRecompute({ activeKind: "all", current: 0, total: 0, result: null, error: false });
    try {
      for await (const event of recomputeCurvesStream(user!.id)) {
        if (event.type === "progress") {
          setCurvesRecompute(s => ({ ...s, current: event.current, total: event.total }));
        }
        else {
          setCurvesRecompute({
            activeKind: null, current: 0, total: 0, error: false,
            result: `Recomputed duration curves — ${event.processed} activities`,
          });
          qc.invalidateQueries({ queryKey: ["activity-curve"] });
        }
      }
    }
    catch {
      setCurvesRecompute(s => ({ ...s, activeKind: null, error: true }));
    }
  }, [user, qc]);

  const thresholdSettingsMutation = useMutation({
    mutationFn: async () => {
      const updated = await updateAthlete(user!.id, {
        threshold_window_days: windowDays,
        threshold_sanity_pct: sanityPct,
      });
      setUser(updated);
    },
  });

  const startFieldRecompute = useCallback(async (field: ThresholdFieldName) => {
    setFieldRecompute(s => ({ ...s, [field]: { running: true, current: 0, total: 0, result: null, error: false } }));
    try {
      for await (const event of recomputeThresholdHistoryStream(user!.id, field)) {
        if (event.type === "progress") {
          setFieldRecompute(s => ({ ...s, [field]: { ...s[field], current: event.current, total: event.total } }));
        } else {
          setFieldRecompute(s => ({
            ...s, [field]: { running: false, current: event.total, total: event.total, result: event.total, error: false },
          }));
          qc.invalidateQueries({ queryKey: ["activities"] });
          qc.invalidateQueries({ queryKey: ["thresholds", user!.id] });
          qc.invalidateQueries({ queryKey: ["threshold-history", user!.id, field] });
        }
      }
    }
    catch {
      // Not rethrown - startAllFieldsRecompute's sequence should still try the remaining
      // fields rather than aborting entirely because one connection dropped.
      setFieldRecompute(s => ({ ...s, [field]: { ...s[field], running: false, error: true } }));
    }
  }, [user, qc]);

  const anyFieldRunning = Object.values(fieldRecompute).some(s => s.running);
  const [regenerateAllStep, setRegenerateAllStep] = useState<number | null>(null);

  // Runs the existing per-field rebuild sequentially for all three fields - each field's own
  // card shows its own live progress bar as its turn comes up, so no separate progress UI is
  // needed here beyond the step counter on the button itself.
  const startAllFieldsRecompute = useCallback(async () => {
    for (let i = 0; i < THRESHOLD_FIELDS.length; i++) {
      setRegenerateAllStep(i);
      await startFieldRecompute(THRESHOLD_FIELDS[i].field);
    }
    setRegenerateAllStep(null);
  }, [startFieldRecompute]);

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
            disabled={saveMutation.isPending || recomputeRunning || effectiveTopN === savedTopN}
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
            disabled={saveMutation.isPending || recomputeRunning}
            style={{ ...btnStyle, opacity: recomputeRunning && recompute.activeKind === "all" ? 0.6 : 1 }}
          >
            {recomputeRunning && recompute.activeKind === "all" ? "Recomputing…" : "Save & recompute all"}
          </button>
          {saveMutation.isSuccess && !recomputeRunning && (
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
        {/* Recompute can also be triggered by "Recompute all"/per-kind buttons further down
            (shared recompute state) - shown here too since a recompute started from THIS
            button would otherwise only show its progress in that other section, easy to miss
            since it's not next to the button that was actually clicked. */}
        {recomputeRunning && recompute.activeKind === "all" && recompute.total > 0 && (
          <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            <ProgressBar current={recompute.current} total={recompute.total} />
            <span style={{ fontSize: 11, color: "var(--ink3)" }}>
              {formatElapsed(recomputeElapsed)} elapsed
              {(() => {
                const eta = etaText(recompute.current, recompute.total, recomputeElapsed);
                return eta ? ` \u00b7 ${eta}` : "";
              })()}
            </span>
          </div>
        )}
        {recomputeRunning && recompute.activeKind === "all" && recompute.total === 0 && (
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: 0 }}>Starting… ({formatElapsed(recomputeElapsed)})</p>
        )}
      </section>

      <section style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>Recompute best efforts</h2>
        <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0, lineHeight: 1.6 }}>
          Recalculates best efforts from your stored activity records. Use this after changing the
          entries-per-window setting, to fix records that were computed from bad data, or for
          activities that never had best efforts computed in the first place (e.g. imported activities -
          restoring from an export doesn't recompute them).
        </p>

        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {KINDS.map(({ kind, label }) => (
            <div key={kind} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "10px 14px", borderRadius: 8, background: "var(--elev)", border: "1px solid var(--line)" }}>
              <span style={{ fontSize: 13, fontWeight: 600 }}>{label}</span>
              <button
                onClick={() => startRecompute(kind)}
                disabled={recomputeRunning}
                style={{ ...btnStyle, padding: "6px 12px", fontSize: 12, opacity: recomputeRunning && recompute.activeKind === kind ? 0.6 : 1 }}
              >
                {recomputeRunning && recompute.activeKind === kind ? "Working…" : "Recompute"}
              </button>
            </div>
          ))}
        </div>

        <button
          onClick={() => startRecompute(undefined)}
          disabled={recomputeRunning}
          style={{ ...btnStyle, alignSelf: "flex-start", opacity: recomputeRunning && recompute.activeKind === "all" ? 0.6 : 1 }}
        >
          {recomputeRunning && recompute.activeKind === "all" ? "Working…" : "Recompute all"}
        </button>

        {recomputeRunning && recompute.total > 0 && (
          <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            <ProgressBar current={recompute.current} total={recompute.total} />
            <span style={{ fontSize: 11, color: "var(--ink3)" }}>
              {formatElapsed(recomputeElapsed)} elapsed
              {(() => {
                const eta = etaText(recompute.current, recompute.total, recomputeElapsed);
                return eta ? ` \u00b7 ${eta}` : "";
              })()}
            </span>
          </div>
        )}
        {recomputeRunning && recompute.total === 0 && (
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: 0 }}>Starting… ({formatElapsed(recomputeElapsed)})</p>
        )}
        {recompute.result && !recomputeRunning && (
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
        {statsRecompute.error && (
          <p style={{ fontSize: 13, color: "var(--danger,#e04040)", margin: 0 }}>Recompute failed. Try again.</p>
        )}
      </section>

      <section style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>Recompute duration curves</h2>
        <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0, lineHeight: 1.6 }}>
          Backfills the power/heart-rate duration curves shown on each activity's Curves tab.
          Nothing computes these except the original upload - use this for activities that never
          had them in the first place (e.g. imported activities - restoring from an export
          doesn't recompute them).
        </p>

        <button
          onClick={() => startCurvesRecompute()}
          disabled={curvesRecomputeRunning}
          style={{ ...btnStyle, alignSelf: "flex-start", opacity: curvesRecomputeRunning ? 0.6 : 1 }}
        >
          {curvesRecomputeRunning ? "Working…" : "Recompute all"}
        </button>

        {curvesRecomputeRunning && curvesRecompute.total > 0 && (
          <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            <ProgressBar current={curvesRecompute.current} total={curvesRecompute.total} />
            <span style={{ fontSize: 11, color: "var(--ink3)" }}>
              {formatElapsed(curvesElapsed)} elapsed
              {(() => {
                const eta = etaText(curvesRecompute.current, curvesRecompute.total, curvesElapsed);
                return eta ? ` · ${eta}` : "";
              })()}
            </span>
          </div>
        )}
        {curvesRecomputeRunning && curvesRecompute.total === 0 && (
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: 0 }}>Starting… ({formatElapsed(curvesElapsed)})</p>
        )}
        {curvesRecompute.result && !curvesRecomputeRunning && (
          <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0 }}>{curvesRecompute.result}</p>
        )}
        {curvesRecompute.error && (
          <p style={{ fontSize: 13, color: "var(--danger,#e04040)", margin: 0 }}>Recompute failed. Try again.</p>
        )}
      </section>

      <section style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>Threshold history</h2>
        <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0, lineHeight: 1.6 }}>
          FTP, critical running power, and threshold pace are each the best qualifying effort
          within a trailing window, so they can go down as an old best effort ages out, not just
          up. A candidate effort that deviates too far from your current value (e.g. corrupt
          power-meter data) is excluded automatically.
        </p>
        <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
          <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 13, color: "var(--ink2)" }}>
            Window (days)
            <input
              type="number" min={1} max={365} value={windowDays}
              onChange={e => setWindowDays(Math.max(1, Number(e.target.value)))}
              style={inputStyle}
            />
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 13, color: "var(--ink2)" }}>
            Sanity limit (%)
            <input
              type="number" min={1} max={100} value={sanityPct}
              onChange={e => setSanityPct(Math.max(1, Number(e.target.value)))}
              style={inputStyle}
            />
          </label>
          <button
            onClick={() => thresholdSettingsMutation.mutate()}
            disabled={
              thresholdSettingsMutation.isPending
              || (windowDays === user.threshold_window_days && sanityPct === user.threshold_sanity_pct)
            }
            style={btnStyle}
          >
            {thresholdSettingsMutation.isPending ? "Saving…" : "Save"}
          </button>
          {thresholdSettingsMutation.isSuccess && <span style={{ fontSize: 13, color: "var(--ink3)" }}>Saved</span>}
        </div>
        <p style={{ fontSize: 12, color: "var(--ink3)", margin: 0 }}>
          Changing these doesn't retroactively update your history - rebuild a field below to
          replay it under the new settings.
        </p>

        <div>
          <button
            onClick={() => startAllFieldsRecompute()}
            disabled={anyFieldRunning}
            style={{ ...btnStyle, alignSelf: "flex-start", opacity: regenerateAllStep !== null ? 0.6 : 1 }}
          >
            {regenerateAllStep !== null
              ? `Regenerating all… (${regenerateAllStep + 1}/${THRESHOLD_FIELDS.length})`
              : "Regenerate all from oldest"}
          </button>
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: "6px 0 0" }}>
            Rebuilds all three fields from scratch, one after another. Useful after a bulk import,
            since the rolling-window algorithm replays activities oldest-first and a bulk import
            doesn't guarantee that order.
          </p>
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {THRESHOLD_FIELDS.map(({ field, label }) => {
            const state = fieldRecompute[field];
            return (
              <div key={field} style={{ display: "flex", flexDirection: "column", gap: 6, padding: "10px 14px", borderRadius: 8, background: "var(--elev)", border: "1px solid var(--line)" }}>
                <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                  <span style={{ fontSize: 13, fontWeight: 600 }}>{label}</span>
                  <button
                    onClick={() => startFieldRecompute(field)}
                    disabled={state.running || regenerateAllStep !== null}
                    style={{ ...btnStyle, padding: "6px 12px", fontSize: 12, opacity: state.running ? 0.6 : 1 }}
                  >
                    {state.running ? "Rebuilding…" : "Rebuild from oldest"}
                  </button>
                </div>
                {state.running && state.total > 0 && <ProgressBar current={state.current} total={state.total} />}
                {state.running && state.total === 0 && (
                  <p style={{ fontSize: 12, color: "var(--ink3)", margin: 0 }}>Starting…</p>
                )}
                {state.result != null && !state.running && (
                  <p style={{ fontSize: 12, color: "var(--ink3)", margin: 0 }}>
                    {state.result === 0 ? "No qualifying efforts found." : `Ledger rebuilt — ${state.result} change${state.result === 1 ? "" : "s"} recorded.`}
                  </p>
                )}
                {state.error && (
                  <p style={{ fontSize: 12, color: "var(--danger,#e04040)", margin: 0 }}>Recompute failed. Try again.</p>
                )}
              </div>
            );
          })}
        </div>
      </section>
    </div>
  );
}
