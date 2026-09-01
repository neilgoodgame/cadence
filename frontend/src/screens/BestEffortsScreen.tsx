import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, useMemo } from "react";
import { Link } from "react-router-dom";
import { listBestEfforts, excludeActivityFromBestEfforts } from "../api/athletes";
import { listActivities, getActivity } from "../api/activities";
import { ActivityNameLink } from "../components/ActivityNameLink";
import { useAuth } from "../auth/AuthContext";
import { formatDuration, formatPace } from "../lib/format";
import type { Activity, BestEffort, BestEffortKind, BestEffortPeriod } from "../api/types";

// ─── Period config ────────────────────────────────────────────────────────────

type DisplayPeriod = "4w" | "16w" | "1y" | "all";

// Each display tab maps to a backend period of the exact same cutoff - the backend used to
// only bucket by "3m" / "1y" / "all", so "4w"/"16w" were faked by fetching a wider bucket (3m
// or 1y) and narrowing the result client-side to 28/112 days. That narrowing was applied AFTER
// the backend had already capped each window to its top-N *within the wider bucket* - so an
// effort that was genuinely top-N within the narrower 28/112-day window, but not within the
// top-N of the wider bucket it was fetched from, got silently dropped before the client-side
// filter ever saw it (seen live: a window with plenty of history over the year showed only 3
// of the last 16 weeks' true top-10). The backend now has native "4w"/"16w" periods matching
// BEST_EFFORT_TRIM_PERIOD_DAYS exactly, so each tab fetches precisely the window it displays -
// no client-side narrowing needed.
//
// The backend keeps each tracked period's own top-N leaderboard (not just an all-time one), so
// a recent effort can show up here even if it's not an all-time record - it only needs to be a
// top-N record within its own period. See BEST_EFFORT_TRIM_PERIOD_DAYS in
// backend/uploads/processing.py (and BestEffortWindows.TRIM_PERIOD_DAYS in the Java backend)
// for the exact cutoffs that are kept.
const PERIOD_CONFIG: Record<DisplayPeriod, { apiPeriod: BestEffortPeriod; label: string }> = {
  "4w": { apiPeriod: "4w", label: "4 weeks" },
  "16w": { apiPeriod: "16w", label: "16 weeks" },
  "1y": { apiPeriod: "1y", label: "1 year" },
  all: { apiPeriod: "all", label: "All time" },
};
const DISPLAY_PERIODS: DisplayPeriod[] = ["4w", "16w", "1y", "all"];

function windowToSeconds(w: string): number {
  const m = w.match(/^(\d+)\s*(s|sec|m|min|h|hr)/i);
  if (!m) return 0;
  const n = parseInt(m[1]);
  const unit = m[2].toLowerCase();
  if (unit.startsWith("h")) return n * 3600;
  if (unit.startsWith("m")) return n * 60;
  return n;
}

function windowToKm(w: string): number {
  if (/marathon/i.test(w)) return /half/i.test(w) ? 21.097 : 42.195;
  const mile = w.match(/^([\d.]+)\s*mile/i);
  if (mile) return parseFloat(mile[1]) * 1.609344;
  const m = w.match(/^([\d.]+)\s*km/i);
  return m ? parseFloat(m[1]) : 0;
}

/** True only for the #1 all-time effort for a given window. */
function isPR(effort: BestEffort, allTimeEfforts: BestEffort[], lowerIsBetter: boolean): boolean {
  const windowBests = allTimeEfforts.filter((e) => e.window === effort.window);
  if (!windowBests.length) return false;
  if (lowerIsBetter) {
    // API sorts value desc regardless of kind, so min is the true best for pace
    const best = Math.min(...windowBests.map((e) => e.value));
    return effort.value <= best;
  }
  // Higher is better: API sorts value desc, so first match is the best
  return effort.value >= windowBests[0].value;
}

function fmtShortDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

function fmtAvgHr(activity: Activity | null): string {
  return activity?.avg_hr != null ? `${Math.round(activity.avg_hr)} bpm` : "—";
}

function fmtAvgPower(activity: Activity | null): string {
  return activity?.avg_power != null ? `${Math.round(activity.avg_power)}w` : "—";
}

function fmtActivityPace(activity: Activity | null): string {
  if (!activity?.moving_time || !activity?.distance_km) return "—";
  return formatPace(activity.moving_time / activity.distance_km);
}

/** Unique sorted window labels from a list that may have multiple entries per window. */
function uniqueWindowsSorted(efforts: BestEffort[], sortFn: (a: string, b: string) => number): string[] {
  const seen = new Set<string>();
  const windows: string[] = [];
  for (const e of efforts) {
    if (!seen.has(e.window)) {
      seen.add(e.window);
      windows.push(e.window);
    }
  }
  return windows.sort(sortFn);
}

// ─── Shared UI ────────────────────────────────────────────────────────────────

function pillStyle(active: boolean): React.CSSProperties {
  return {
    padding: "5px 11px",
    fontSize: 11.5,
    fontWeight: 600,
    borderRadius: 7,
    cursor: "pointer",
    whiteSpace: "nowrap",
    color: active ? "var(--ink)" : "var(--ink3)",
    background: active ? "var(--card)" : "transparent",
    boxShadow: active ? "0 1px 2px rgba(0,0,0,0.14)" : "none",
  };
}

function segStyle(active: boolean): React.CSSProperties {
  return {
    flex: 1,
    textAlign: "center" as const,
    padding: "6px 12px",
    fontSize: 12.5,
    fontWeight: 600,
    borderRadius: 7,
    cursor: "pointer",
    whiteSpace: "nowrap",
    color: active ? "var(--ink)" : "var(--ink3)",
    background: active ? "var(--card)" : "transparent",
    boxShadow: active ? "0 1px 2px rgba(0,0,0,0.14)" : "none",
  };
}

function PrBadge({ color }: { color: string }) {
  return (
    <span
      style={{
        fontFamily: "'JetBrains Mono',monospace",
        fontSize: 9,
        fontWeight: 700,
        padding: "2px 7px",
        borderRadius: 20,
        background: color + "26",
        color,
        letterSpacing: "0.04em",
      }}
    >
      PR
    </span>
  );
}

function useExclude(athleteId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ activityId, kind }: { activityId: string; kind: BestEffortKind }) =>
      excludeActivityFromBestEfforts(athleteId, activityId, kind),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["best-efforts", athleteId] });
    },
  });
}

function useActivity(id: string | null): Activity | null {
  const { data } = useQuery({
    queryKey: ["activity", id],
    queryFn: () => getActivity(id!),
    enabled: !!id,
    staleTime: 5 * 60 * 1000,
  });
  return data ?? null;
}

function TabBar({
  tabs,
  active,
  onChange,
}: {
  tabs: string[];
  active: string;
  onChange: (t: string) => void;
}) {
  return (
    <div
      style={{
        display: "flex",
        gap: 2,
        background: "var(--canvas)",
        border: "1px solid var(--line)",
        borderRadius: 9,
        padding: 3,
      }}
    >
      {tabs.map((t) => (
        <div key={t} onClick={() => onChange(t)} style={pillStyle(t === active)}>
          {t}
        </div>
      ))}
    </div>
  );
}

function CardShell({
  title,
  tabs,
  children,
}: {
  title: React.ReactNode;
  tabs?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div
      style={{
        background: "var(--card)",
        border: "1px solid var(--line)",
        borderRadius: 14,
        overflow: "hidden",
      }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "14px 18px",
          borderBottom: "1px solid var(--line)",
          flexWrap: "wrap",
          gap: 10,
        }}
      >
        <span
          style={{
            fontFamily: "'JetBrains Mono',monospace",
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: "0.08em",
            color: "var(--ink3)",
          }}
        >
          {title}
        </span>
        {tabs}
      </div>
      {children}
    </div>
  );
}

function ColHeaders({ cols, gridCols }: { cols: string[]; gridCols: string }) {
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: gridCols,
        gap: 8,
        padding: "10px 18px",
        borderBottom: "1px solid var(--line)",
        fontFamily: "'JetBrains Mono',monospace",
        fontSize: 10,
        textTransform: "uppercase",
        letterSpacing: "0.06em",
        color: "var(--ink3)",
        whiteSpace: "nowrap",
      }}
    >
      {cols.map((c) => (
        <span key={c}>{c}</span>
      ))}
    </div>
  );
}

function EmptyRow({ msg }: { msg: string }) {
  return <div style={{ padding: "20px 18px", color: "var(--ink3)", fontSize: 13 }}>{msg}</div>;
}

function ExcludeButton({ onExclude, pending }: { onExclude: () => void; pending: boolean }) {
  const [hovered, setHovered] = useState(false);
  return (
    <button
      onClick={onExclude}
      disabled={pending}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      style={{
        background: hovered ? "var(--surface2)" : "none",
        border: "1px solid",
        borderColor: hovered ? "var(--ink3)" : "var(--ink4)",
        cursor: pending ? "default" : "pointer",
        padding: "2px 6px",
        borderRadius: 4,
        color: hovered ? "var(--ink2)" : "var(--ink3)",
        fontSize: 11,
        lineHeight: 1.4,
        opacity: pending ? 0.4 : 1,
        flexShrink: 0,
        whiteSpace: "nowrap",
      }}
    >
      {pending ? "…" : "Exclude"}
    </button>
  );
}

// ─── Running HR (HEART RATE · MAX SUSTAINED) ─────────────────────────────────

const RUN_HR_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.55fr 0.85fr 0.5fr 0.55fr 64px";

function RunHrRow({ effort, rank, allTimeEfforts, onExclude, excludePending }: { effort: BestEffort; rank: number; allTimeEfforts: BestEffort[]; onExclude: (id: string) => void; excludePending: boolean }) {
  const pr = isPR(effort, allTimeEfforts, false);
  const activity = useActivity(effort.activity_id);
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: RUN_HR_GRID,
        gap: 8,
        padding: "11px 18px",
        borderBottom: "1px solid var(--line)",
        alignItems: "center",
      }}
    >
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>{rank}</span>
      <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
        <ActivityNameLink id={effort.activity_id} />
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
        {fmtShortDate(effort.date)}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {effort.window}
      </span>
      <span style={{ display: "flex", alignItems: "baseline", gap: 5, whiteSpace: "nowrap" }}>
        <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 15, fontWeight: 700, color: "var(--run,#ec4a26)" }}>
          {Math.round(effort.value)} bpm
        </span>
        {pr && <PrBadge color="var(--run,#ec4a26)" />}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {fmtAvgPower(activity)}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {fmtActivityPace(activity)}
      </span>
      <ExcludeButton onExclude={() => onExclude(effort.activity_id)} pending={excludePending} />
    </div>
  );
}

function RunHrCard({
  efforts,
  allTimeEfforts,
  onExclude,
  excludePending,
}: {
  efforts: BestEffort[];
  allTimeEfforts: BestEffort[];
  onExclude: (id: string) => void;
  excludePending: boolean;
}) {
  const tabs = useMemo(
    () => uniqueWindowsSorted(allTimeEfforts, (a, b) => windowToSeconds(a) - windowToSeconds(b)),
    [allTimeEfforts],
  );
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const firstWithData = useMemo(() => tabs.find((t) => efforts.some((e) => e.window === t)), [tabs, efforts]);
  const effective = activeTab ?? firstWithData ?? tabs[0] ?? null;
  // Deliberately never falls back to allTimeEfforts when the period has nothing for the
  // selected window - that silently showed activities from outside the chosen period (e.g.
  // "4 weeks" rendering a PR set months ago) instead of the correct empty state.
  const windowEfforts = useMemo(() => efforts.filter((e) => e.window === effective), [efforts, effective]);

  return (
    <CardShell
      title="HEART RATE · MAX SUSTAINED"
      tabs={
        tabs.length > 0 ? (
          <TabBar tabs={tabs} active={effective ?? ""} onChange={(t) => setActiveTab(t)} />
        ) : undefined
      }
    >
      <ColHeaders cols={["#", "Activity", "Date", "Duration", "Avg HR", "Power", "Pace", ""]} gridCols={RUN_HR_GRID} />
      {windowEfforts.length === 0 ? (
        <EmptyRow msg="No heart rate data for this period." />
      ) : (
        windowEfforts.map((e, i) => (
          <RunHrRow key={e.activity_id} effort={e} rank={i + 1} allTimeEfforts={allTimeEfforts} onExclude={onExclude} excludePending={excludePending} />
        ))
      )}
    </CardShell>
  );
}

// ─── Cycling HR (HEART RATE · MAX SUSTAINED) ─────────────────────────────────

const BIKE_HR_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.55fr 0.85fr 0.55fr 64px";

function BikeHrRow({ effort, rank, allTimeEfforts, onExclude, excludePending }: { effort: BestEffort; rank: number; allTimeEfforts: BestEffort[]; onExclude: (id: string) => void; excludePending: boolean }) {
  const pr = isPR(effort, allTimeEfforts, false);
  const activity = useActivity(effort.activity_id);
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: BIKE_HR_GRID,
        gap: 8,
        padding: "11px 18px",
        borderBottom: "1px solid var(--line)",
        alignItems: "center",
      }}
    >
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>{rank}</span>
      <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
        <ActivityNameLink id={effort.activity_id} />
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
        {fmtShortDate(effort.date)}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {effort.window}
      </span>
      <span style={{ display: "flex", alignItems: "baseline", gap: 5, whiteSpace: "nowrap" }}>
        <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 15, fontWeight: 700, color: "var(--bike,#3d7fd6)" }}>
          {Math.round(effort.value)} bpm
        </span>
        {pr && <PrBadge color="var(--bike,#3d7fd6)" />}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {fmtAvgPower(activity)}
      </span>
      <ExcludeButton onExclude={() => onExclude(effort.activity_id)} pending={excludePending} />
    </div>
  );
}

function BikeHrCard({
  efforts,
  allTimeEfforts,
  onExclude,
  excludePending,
}: {
  efforts: BestEffort[];
  allTimeEfforts: BestEffort[];
  onExclude: (id: string) => void;
  excludePending: boolean;
}) {
  const tabs = useMemo(
    () => uniqueWindowsSorted(allTimeEfforts, (a, b) => windowToSeconds(a) - windowToSeconds(b)),
    [allTimeEfforts],
  );
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const firstWithData = useMemo(() => tabs.find((t) => efforts.some((e) => e.window === t)), [tabs, efforts]);
  const effective = activeTab ?? firstWithData ?? tabs[0] ?? null;
  // Deliberately never falls back to allTimeEfforts when the period has nothing for the
  // selected window - that silently showed activities from outside the chosen period (e.g.
  // "4 weeks" rendering a PR set months ago) instead of the correct empty state.
  const windowEfforts = useMemo(() => efforts.filter((e) => e.window === effective), [efforts, effective]);

  return (
    <CardShell
      title="HEART RATE · MAX SUSTAINED"
      tabs={
        tabs.length > 0 ? (
          <TabBar tabs={tabs} active={effective ?? ""} onChange={(t) => setActiveTab(t)} />
        ) : undefined
      }
    >
      <ColHeaders cols={["#", "Activity", "Date", "Duration", "Avg HR", "Power", ""]} gridCols={BIKE_HR_GRID} />
      {windowEfforts.length === 0 ? (
        <EmptyRow msg="No heart rate data for this period." />
      ) : (
        windowEfforts.map((e, i) => (
          <BikeHrRow key={e.activity_id} effort={e} rank={i + 1} allTimeEfforts={allTimeEfforts} onExclude={onExclude} excludePending={excludePending} />
        ))
      )}
    </CardShell>
  );
}

// ─── Running Power (POWER · STRYD) ───────────────────────────────────────────

const RUN_PWR_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.55fr 0.85fr 0.5fr 0.55fr 64px";

function RunPowerRow({ effort, rank, allTimeEfforts, onExclude, excludePending }: { effort: BestEffort; rank: number; allTimeEfforts: BestEffort[]; onExclude: (id: string) => void; excludePending: boolean }) {
  const pr = isPR(effort, allTimeEfforts, false);
  const activity = useActivity(effort.activity_id);
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: RUN_PWR_GRID,
        gap: 8,
        padding: "11px 18px",
        borderBottom: "1px solid var(--line)",
        alignItems: "center",
      }}
    >
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>{rank}</span>
      <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
        <ActivityNameLink id={effort.activity_id} />
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
        {fmtShortDate(effort.date)}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {effort.window}
      </span>
      <span style={{ display: "flex", alignItems: "baseline", gap: 5, whiteSpace: "nowrap" }}>
        <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 15, fontWeight: 700, color: "var(--run,#ec4a26)" }}>
          {Math.round(effort.value)}w
        </span>
        {pr && <PrBadge color="var(--run,#ec4a26)" />}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {fmtAvgHr(activity)}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {fmtActivityPace(activity)}
      </span>
      <ExcludeButton onExclude={() => onExclude(effort.activity_id)} pending={excludePending} />
    </div>
  );
}

function RunPowerCard({
  efforts,
  allTimeEfforts,
  rCP,
  onExclude,
  excludePending,
}: {
  efforts: BestEffort[];
  allTimeEfforts: BestEffort[];
  rCP: number | null;
  onExclude: (id: string) => void;
  excludePending: boolean;
}) {
  const tabs = useMemo(
    () => uniqueWindowsSorted(allTimeEfforts, (a, b) => windowToSeconds(a) - windowToSeconds(b)),
    [allTimeEfforts],
  );
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const firstWithData = useMemo(() => tabs.find((t) => efforts.some((e) => e.window === t)), [tabs, efforts]);
  const effective = activeTab ?? firstWithData ?? tabs[0] ?? null;
  // Deliberately never falls back to allTimeEfforts when the period has nothing for the
  // selected window - that silently showed activities from outside the chosen period (e.g.
  // "4 weeks" rendering a PR set months ago) instead of the correct empty state.
  const windowEfforts = useMemo(() => efforts.filter((e) => e.window === effective), [efforts, effective]);

  const title = (
    <span style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
      <span>POWER · STRYD</span>
      {rCP && (
        <span
          style={{
            fontFamily: "'JetBrains Mono',monospace",
            fontSize: 11,
            color: "var(--ink3)",
            fontWeight: 400,
            letterSpacing: "0.04em",
          }}
        >
          rCP {rCP}w
        </span>
      )}
    </span>
  );

  return (
    <CardShell
      title={title}
      tabs={
        tabs.length > 0 ? (
          <TabBar tabs={tabs} active={effective ?? ""} onChange={(t) => setActiveTab(t)} />
        ) : undefined
      }
    >
      <ColHeaders cols={["#", "Activity", "Date", "Duration", "Power", "Avg HR", "Pace", ""]} gridCols={RUN_PWR_GRID} />
      {windowEfforts.length === 0 ? (
        <EmptyRow msg="No running power data for this period." />
      ) : (
        windowEfforts.map((e, i) => (
          <RunPowerRow key={e.activity_id} effort={e} rank={i + 1} allTimeEfforts={allTimeEfforts} onExclude={onExclude} excludePending={excludePending} />
        ))
      )}
    </CardShell>
  );
}

// ─── Running Pace (BEST TIME · BY DISTANCE) ──────────────────────────────────

const RUN_TIME_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.85fr 0.55fr 0.5fr 0.55fr 64px";

function RunPaceRow({ effort, rank, allTimeEfforts, onExclude, excludePending }: { effort: BestEffort; rank: number; allTimeEfforts: BestEffort[]; onExclude: (id: string) => void; excludePending: boolean }) {
  const pr = isPR(effort, allTimeEfforts, true);
  const activity = useActivity(effort.activity_id);
  const distKm = windowToKm(effort.window);
  const totalSec = distKm ? effort.value * distKm : null;
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: RUN_TIME_GRID,
        gap: 8,
        padding: "11px 18px",
        borderBottom: "1px solid var(--line)",
        alignItems: "center",
      }}
    >
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>{rank}</span>
      <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
        <ActivityNameLink id={effort.activity_id} />
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
        {fmtShortDate(effort.date)}
      </span>
      <span style={{ display: "flex", alignItems: "baseline", gap: 5, whiteSpace: "nowrap" }}>
        <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 15, fontWeight: 700, color: "var(--run,#ec4a26)" }}>
          {totalSec !== null ? formatDuration(totalSec) : "—"}
        </span>
        {pr && <PrBadge color="var(--run,#ec4a26)" />}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {formatPace(effort.value)}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {fmtAvgHr(activity)}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {fmtAvgPower(activity)}
      </span>
      <ExcludeButton onExclude={() => onExclude(effort.activity_id)} pending={excludePending} />
    </div>
  );
}

function RunPaceCard({
  efforts,
  allTimeEfforts,
  onExclude,
  excludePending,
}: {
  efforts: BestEffort[];
  allTimeEfforts: BestEffort[];
  onExclude: (id: string) => void;
  excludePending: boolean;
}) {
  const tabs = useMemo(
    () => uniqueWindowsSorted(allTimeEfforts, (a, b) => windowToKm(a) - windowToKm(b)),
    [allTimeEfforts],
  );
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const firstWithData = useMemo(() => tabs.find((t) => efforts.some((e) => e.window === t)), [tabs, efforts]);
  const effective = activeTab ?? firstWithData ?? tabs[0] ?? null;
  // Deliberately never falls back to allTimeEfforts when the period has nothing for the
  // selected window - that silently showed activities from outside the chosen period (e.g.
  // "4 weeks" rendering a PR set months ago) instead of the correct empty state.
  const windowEfforts = useMemo(() => {
    // pace is lower-is-better; API returns value asc for pace? No — API always returns value desc.
    // Re-sort ascending (lower = faster = better rank).
    return efforts.filter((e) => e.window === effective).sort((a, b) => a.value - b.value);
  }, [efforts, effective]);

  return (
    <CardShell
      title="BEST TIME · BY DISTANCE"
      tabs={
        tabs.length > 0 ? (
          <TabBar tabs={tabs} active={effective ?? ""} onChange={(t) => setActiveTab(t)} />
        ) : undefined
      }
    >
      <ColHeaders cols={["#", "Activity", "Date", "Time", "Pace", "Avg HR", "Power", ""]} gridCols={RUN_TIME_GRID} />
      {windowEfforts.length === 0 ? (
        <EmptyRow msg="No running pace data for this period." />
      ) : (
        windowEfforts.map((e, i) => (
          <RunPaceRow key={e.activity_id} effort={e} rank={i + 1} allTimeEfforts={allTimeEfforts} onExclude={onExclude} excludePending={excludePending} />
        ))
      )}
    </CardShell>
  );
}

// ─── Cycling Peak Power (PEAK POWER) ─────────────────────────────────────────

const BIKE_PWR_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.55fr 0.85fr 0.45fr 0.55fr 64px";

function BikePowerRow({
  effort,
  rank,
  allTimeEfforts,
  weightKg,
  onExclude,
  excludePending,
}: {
  effort: BestEffort;
  rank: number;
  allTimeEfforts: BestEffort[];
  weightKg: number | null;
  onExclude: (id: string) => void;
  excludePending: boolean;
}) {
  const pr = isPR(effort, allTimeEfforts, false);
  const activity = useActivity(effort.activity_id);
  const wkg = weightKg ? (effort.value / weightKg).toFixed(1) : "—";
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: BIKE_PWR_GRID,
        gap: 8,
        padding: "11px 18px",
        borderBottom: "1px solid var(--line)",
        alignItems: "center",
      }}
    >
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>{rank}</span>
      <span style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
        <ActivityNameLink id={effort.activity_id} />
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
        {fmtShortDate(effort.date)}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {effort.window}
      </span>
      <span style={{ display: "flex", alignItems: "baseline", gap: 5, whiteSpace: "nowrap" }}>
        <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 15, fontWeight: 700, color: "var(--bike,#3d7fd6)" }}>
          {Math.round(effort.value)}w
        </span>
        {pr && <PrBadge color="var(--bike,#3d7fd6)" />}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {wkg}
      </span>
      <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
        {fmtAvgHr(activity)}
      </span>
      <ExcludeButton onExclude={() => onExclude(effort.activity_id)} pending={excludePending} />
    </div>
  );
}

function BikePowerCard({
  efforts,
  allTimeEfforts,
  weightKg,
  onExclude,
  excludePending,
}: {
  efforts: BestEffort[];
  allTimeEfforts: BestEffort[];
  weightKg: number | null;
  onExclude: (id: string) => void;
  excludePending: boolean;
}) {
  const tabs = useMemo(
    () => uniqueWindowsSorted(allTimeEfforts, (a, b) => windowToSeconds(a) - windowToSeconds(b)),
    [allTimeEfforts],
  );
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const firstWithData = useMemo(() => tabs.find((t) => efforts.some((e) => e.window === t)), [tabs, efforts]);
  const effective = activeTab ?? firstWithData ?? tabs[0] ?? null;
  // Deliberately never falls back to allTimeEfforts when the period has nothing for the
  // selected window - that silently showed activities from outside the chosen period (e.g.
  // "4 weeks" rendering a PR set months ago) instead of the correct empty state.
  const windowEfforts = useMemo(() => efforts.filter((e) => e.window === effective), [efforts, effective]);

  return (
    <CardShell
      title="PEAK POWER"
      tabs={
        tabs.length > 0 ? (
          <TabBar tabs={tabs} active={effective ?? ""} onChange={(t) => setActiveTab(t)} />
        ) : undefined
      }
    >
      <ColHeaders cols={["#", "Activity", "Date", "Duration", "Power", "W/kg", "Avg HR", ""]} gridCols={BIKE_PWR_GRID} />
      {windowEfforts.length === 0 ? (
        <EmptyRow msg="No cycling power data for this period." />
      ) : (
        windowEfforts.map((e, i) => (
          <BikePowerRow key={e.activity_id} effort={e} rank={i + 1} allTimeEfforts={allTimeEfforts} weightKg={weightKg} onExclude={onExclude} excludePending={excludePending} />
        ))
      )}
    </CardShell>
  );
}

// ─── Longest Rides ────────────────────────────────────────────────────────────

const RIDES_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.55fr 0.85fr 0.55fr";

function LongestRidesCard() {
  const { user } = useAuth();
  const topN = user?.best_effort_top_n || 10;
  const { data } = useQuery({
    queryKey: ["activities", "longest-rides", topN],
    queryFn: () => listActivities({ sport: "bike", sort: "-distance", limit: topN }),
  });
  const rides = data?.data ?? [];

  return (
    <CardShell title={`LONGEST RIDES · TOP ${topN}`}>
      <ColHeaders
        cols={["#", "Activity", "Date", "Duration", "Distance", "Elevation"]}
        gridCols={RIDES_GRID}
      />
      {rides.length === 0 ? (
        <EmptyRow msg="No cycling activities yet." />
      ) : (
        rides.map((a, i) => (
          <div
            key={a.id}
            style={{
              display: "grid",
              gridTemplateColumns: RIDES_GRID,
              gap: 8,
              padding: "11px 18px",
              borderBottom: "1px solid var(--line)",
              alignItems: "center",
            }}
          >
            <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
              {i + 1}
            </span>
            <Link
              to={`/activities/${a.id}`}
              style={{
                fontSize: 13,
                fontWeight: 600,
                color: "var(--ink)",
                whiteSpace: "nowrap",
                overflow: "hidden",
                textOverflow: "ellipsis",
                textDecoration: "none",
              }}
              onMouseEnter={(e) => ((e.currentTarget as HTMLAnchorElement).style.textDecoration = "underline")}
              onMouseLeave={(e) => ((e.currentTarget as HTMLAnchorElement).style.textDecoration = "none")}
            >
              {a.name}
            </Link>
            <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
              {fmtShortDate(a.start_date)}
            </span>
            <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
              {formatDuration(a.moving_time)}
            </span>
            <span
              style={{
                fontFamily: "'JetBrains Mono',monospace",
                fontSize: 15,
                fontWeight: 700,
                color: "var(--bike,#3d7fd6)",
              }}
            >
              {a.distance_km.toFixed(0)} km
            </span>
            <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
              {a.ascent != null ? `${Math.round(a.ascent)} m` : "—"}
            </span>
          </div>
        ))
      )}
    </CardShell>
  );
}

// ─── Biggest Climbs ───────────────────────────────────────────────────────────

const CLIMBS_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.55fr 0.85fr";

function BiggestClimbsCard() {
  const { user } = useAuth();
  const topN = user?.best_effort_top_n || 10;
  const { data } = useQuery({
    queryKey: ["activities", "biggest-climbs", topN],
    queryFn: () =>
      // No server-side sort-by-ascent field exists, so this pulls a generous pool (the API's
      // own max page size) and sorts client-side.
      listActivities({ sport: "bike", limit: 200 }).then((r) => ({
        ...r,
        data: [...r.data]
          .filter((a) => a.ascent != null)
          .sort((a, b) => (b.ascent ?? 0) - (a.ascent ?? 0))
          .slice(0, topN),
      })),
  });
  const rides = data?.data ?? [];

  return (
    <CardShell title={`BIGGEST CLIMBS · ELEVATION GAIN · TOP ${topN}`}>
      <ColHeaders cols={["#", "Activity", "Date", "Distance", "Elevation"]} gridCols={CLIMBS_GRID} />
      {rides.length === 0 ? (
        <EmptyRow msg="No climbing data yet." />
      ) : (
        rides.map((a, i) => (
          <div
            key={a.id}
            style={{
              display: "grid",
              gridTemplateColumns: CLIMBS_GRID,
              gap: 8,
              padding: "11px 18px",
              borderBottom: "1px solid var(--line)",
              alignItems: "center",
            }}
          >
            <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
              {i + 1}
            </span>
            <Link
              to={`/activities/${a.id}`}
              style={{
                fontSize: 13,
                fontWeight: 600,
                color: "var(--ink)",
                whiteSpace: "nowrap",
                overflow: "hidden",
                textOverflow: "ellipsis",
                textDecoration: "none",
              }}
              onMouseEnter={(e) => ((e.currentTarget as HTMLAnchorElement).style.textDecoration = "underline")}
              onMouseLeave={(e) => ((e.currentTarget as HTMLAnchorElement).style.textDecoration = "none")}
            >
              {a.name}
            </Link>
            <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
              {fmtShortDate(a.start_date)}
            </span>
            <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
              {a.distance_km.toFixed(0)} km
            </span>
            <span
              style={{
                fontFamily: "'JetBrains Mono',monospace",
                fontSize: 15,
                fontWeight: 700,
                color: "var(--bike,#3d7fd6)",
              }}
            >
              {a.ascent != null ? `${Math.round(a.ascent)} m` : "—"}
            </span>
          </div>
        ))
      )}
    </CardShell>
  );
}

// ─── Section header ───────────────────────────────────────────────────────────

function SectionHeader({
  label,
  color,
  summary,
}: {
  label: string;
  color: string;
  summary: string;
}) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <span
        style={{ width: 11, height: 11, borderRadius: 3, background: color, display: "inline-block", flexShrink: 0 }}
      />
      <h2
        style={{ fontSize: 17, fontWeight: 800, letterSpacing: "-0.01em", margin: 0, color: "var(--ink)" }}
      >
        {label}
      </h2>
      <span style={{ fontSize: 13, color: "var(--ink3)" }}>{summary}</span>
    </div>
  );
}

// ─── Main screen ──────────────────────────────────────────────────────────────

export function BestEffortsScreen() {
  const { user } = useAuth();
  const athleteId = user?.id ?? "";
  const weightKg = user?.weight_kg ?? null;
  const rCP = user?.critical_run_power ?? null;
  const [displayPeriod, setDisplayPeriod] = useState<DisplayPeriod>("16w");
  const { apiPeriod, label: periodLabel } = PERIOD_CONFIG[displayPeriod];
  const { mutate: excludeActivity, isPending: excludePending } = useExclude(athleteId);
  const makeExclude = (kind: BestEffortKind) => (activityId: string) => excludeActivity({ activityId, kind });

  const bqOpts = (kind: Parameters<typeof listBestEfforts>[1], per: BestEffortPeriod) => ({
    queryKey: ["best-efforts", athleteId, kind, per],
    queryFn: () => listBestEfforts(athleteId, kind, per),
    enabled: !!athleteId,
  });

  const { data: runHrData } = useQuery(bqOpts("running_hr", apiPeriod));
  const { data: runHrAll } = useQuery(bqOpts("running_hr", "all"));
  const { data: runPowerData } = useQuery(bqOpts("running_power", apiPeriod));
  const { data: runPowerAll } = useQuery(bqOpts("running_power", "all"));
  const { data: runPaceData } = useQuery(bqOpts("running_pace", apiPeriod));
  const { data: runPaceAll } = useQuery(bqOpts("running_pace", "all"));
  const { data: bikeHrData } = useQuery(bqOpts("cycling_hr", apiPeriod));
  const { data: bikeHrAll } = useQuery(bqOpts("cycling_hr", "all"));
  const { data: bikePowerData } = useQuery(bqOpts("cycling_power", apiPeriod));
  const { data: bikePowerAll } = useQuery(bqOpts("cycling_power", "all"));

  const runHrEfforts = runHrData?.data ?? [];
  const runHrAllEfforts = runHrAll?.data ?? [];
  const runPowerEfforts = runPowerData?.data ?? [];
  const runPowerAllEfforts = runPowerAll?.data ?? [];
  const runPaceEfforts = runPaceData?.data ?? [];
  const runPaceAllEfforts = runPaceAll?.data ?? [];
  const bikeHrEfforts = bikeHrData?.data ?? [];
  const bikeHrAllEfforts = bikeHrAll?.data ?? [];
  const bikePowerEfforts = bikePowerData?.data ?? [];
  const bikePowerAllEfforts = bikePowerAll?.data ?? [];

  const hasRunHr = runHrAllEfforts.length > 0;
  const hasRunPower = runPowerAllEfforts.length > 0;
  const hasBikeHr = bikeHrAllEfforts.length > 0;

  // Count distinct windows that have a PR this period
  const runPrCount = [
    ...runHrEfforts.filter((e) => isPR(e, runHrAllEfforts, false)),
    ...runPowerEfforts.filter((e) => isPR(e, runPowerAllEfforts, false)),
    ...runPaceEfforts.filter((e) => isPR(e, runPaceAllEfforts, true)),
  ].length;
  const bikePrCount = [
    ...bikeHrEfforts.filter((e) => isPR(e, bikeHrAllEfforts, false)),
    ...bikePowerEfforts.filter((e) => isPR(e, bikePowerAllEfforts, false)),
  ].length;

  return (
    <div style={{ display: "flex", flexDirection: "column" }}>
      {/* Sticky top bar — negative margins cancel AppShell's 32px padding so bar is edge-to-edge */}
      <div
        style={{
          margin: "-32px -32px 0 -32px",
          position: "sticky",
          top: 0,
          zIndex: 5,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "16px 32px",
          borderBottom: "1px solid var(--line)",
          background: "var(--card)",
          flexWrap: "wrap",
          gap: 12,
        }}
      >
        <div style={{ flexShrink: 0, whiteSpace: "nowrap" }}>
          <h1
            style={{
              fontSize: 21,
              fontWeight: 800,
              letterSpacing: "-0.02em",
              margin: 0,
              color: "var(--ink)",
            }}
          >
            Best efforts
          </h1>
          <div style={{ fontSize: 13, color: "var(--ink3)", marginTop: 2 }}>
            Personal bests across running and cycling · {periodLabel.toLowerCase()}
          </div>
        </div>

        <div
          style={{
            display: "flex",
            gap: 2,
            background: "var(--canvas)",
            border: "1px solid var(--line)",
            borderRadius: 9,
            padding: 3,
          }}
        >
          {DISPLAY_PERIODS.map((p) => (
            <div key={p} onClick={() => setDisplayPeriod(p)} style={segStyle(p === displayPeriod)}>
              {PERIOD_CONFIG[p].label}
            </div>
          ))}
        </div>
      </div>

      {/* Content */}
      <div style={{ paddingTop: 20, paddingBottom: 64, display: "flex", flexDirection: "column", gap: 28 }}>
        {/* Running section */}
        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <SectionHeader
            label="Running"
            color="var(--run,#ec4a26)"
            summary={`${runPrCount} record${runPrCount === 1 ? "" : "s"} standing this period`}
          />

          <RunPaceCard efforts={runPaceEfforts} allTimeEfforts={runPaceAllEfforts} onExclude={makeExclude("running_pace")} excludePending={excludePending} />

          {hasRunHr && (
            <RunHrCard efforts={runHrEfforts} allTimeEfforts={runHrAllEfforts} onExclude={makeExclude("running_hr")} excludePending={excludePending} />
          )}

          {hasRunPower && (
            <RunPowerCard efforts={runPowerEfforts} allTimeEfforts={runPowerAllEfforts} rCP={rCP} onExclude={makeExclude("running_power")} excludePending={excludePending} />
          )}
        </div>

        {/* Cycling section */}
        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <SectionHeader
            label="Cycling"
            color="var(--bike,#3d7fd6)"
            summary={`${bikePrCount} record${bikePrCount === 1 ? "" : "s"} standing this period`}
          />

          <BikePowerCard
            efforts={bikePowerEfforts}
            allTimeEfforts={bikePowerAllEfforts}
            weightKg={weightKg}
            onExclude={makeExclude("cycling_power")}
            excludePending={excludePending}
          />

          {hasBikeHr && (
            <BikeHrCard efforts={bikeHrEfforts} allTimeEfforts={bikeHrAllEfforts} onExclude={makeExclude("cycling_hr")} excludePending={excludePending} />
          )}

          <LongestRidesCard />

          <BiggestClimbsCard />
        </div>
      </div>
    </div>
  );
}
