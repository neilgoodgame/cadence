import { useQuery } from "@tanstack/react-query";
import { useState, useMemo } from "react";
import { listBestEfforts } from "../api/athletes";
import { listActivities, getActivity } from "../api/activities";
import { useAuth } from "../auth/AuthContext";
import { formatDuration, formatPace } from "../lib/format";
import type { BestEffort, BestEffortPeriod } from "../api/types";

// ─── Period config ────────────────────────────────────────────────────────────

type DisplayPeriod = "4w" | "16w" | "1y" | "all";

const PERIOD_CONFIG: Record<DisplayPeriod, { apiPeriod: BestEffortPeriod; label: string }> = {
  "4w": { apiPeriod: "3m", label: "4 weeks" },
  "16w": { apiPeriod: "3m", label: "16 weeks" },
  "1y": { apiPeriod: "1y", label: "1 year" },
  all: { apiPeriod: "all", label: "All time" },
};
const DISPLAY_PERIODS: DisplayPeriod[] = ["4w", "16w", "1y", "all"];

// ─── Helpers ──────────────────────────────────────────────────────────────────

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
  const m = w.match(/^([\d.]+)\s*km/i);
  return m ? parseFloat(m[1]) : 0;
}

function isPR(effort: BestEffort, allTimeEfforts: BestEffort[], lowerIsBetter: boolean): boolean {
  const best = allTimeEfforts.find((e) => e.window === effort.window);
  if (!best) return false;
  return lowerIsBetter ? effort.value <= best.value : effort.value >= best.value;
}

function fmtShortDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
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

function ActivityName({ id }: { id: string }) {
  const { data } = useQuery({
    queryKey: ["activity", id],
    queryFn: () => getActivity(id),
    staleTime: 5 * 60 * 1000,
  });
  return <>{data?.name ?? "—"}</>;
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

// ─── Running Power (POWER · STRYD) ───────────────────────────────────────────

const RUN_PWR_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.55fr 0.85fr";

function RunPowerCard({
  efforts,
  allTimeEfforts,
  rCP,
}: {
  efforts: BestEffort[];
  allTimeEfforts: BestEffort[];
  rCP: number | null;
}) {
  const tabs = useMemo(
    () =>
      [...allTimeEfforts]
        .sort((a, b) => windowToSeconds(a.window) - windowToSeconds(b.window))
        .map((e) => e.window),
    [allTimeEfforts],
  );
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const firstWithData = useMemo(() => tabs.find((t) => efforts.some((e) => e.window === t)), [tabs, efforts]);
  const effective = activeTab ?? firstWithData ?? tabs[0] ?? null;
  const effort = efforts.find((e) => e.window === effective) ?? null;
  const pr = effort ? isPR(effort, allTimeEfforts, false) : false;

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
      <ColHeaders cols={["#", "Activity", "Date", "Duration", "Power"]} gridCols={RUN_PWR_GRID} />
      {!effort ? (
        <EmptyRow msg="No running power data for this period." />
      ) : (
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
          <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
            1
          </span>
          <span
            style={{
              fontSize: 13,
              fontWeight: 600,
              color: "var(--ink)",
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
            }}
          >
            <ActivityName id={effort.activity_id} />
          </span>
          <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
            {fmtShortDate(effort.date)}
          </span>
          <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
            {effort.window}
          </span>
          <span style={{ display: "flex", alignItems: "baseline", gap: 5, whiteSpace: "nowrap" }}>
            <span
              style={{
                fontFamily: "'JetBrains Mono',monospace",
                fontSize: 15,
                fontWeight: 700,
                color: "var(--run,#ec4a26)",
              }}
            >
              {Math.round(effort.value)}w
            </span>
            {pr && <PrBadge color="var(--run,#ec4a26)" />}
          </span>
        </div>
      )}
    </CardShell>
  );
}

// ─── Running Pace (BEST TIME · BY DISTANCE) ──────────────────────────────────

const RUN_TIME_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.85fr 0.55fr";

function RunPaceCard({
  efforts,
  allTimeEfforts,
}: {
  efforts: BestEffort[];
  allTimeEfforts: BestEffort[];
}) {
  const tabs = useMemo(
    () =>
      [...allTimeEfforts]
        .sort((a, b) => windowToKm(a.window) - windowToKm(b.window))
        .map((e) => e.window),
    [allTimeEfforts],
  );
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const firstWithData = useMemo(() => tabs.find((t) => efforts.some((e) => e.window === t)), [tabs, efforts]);
  const effective = activeTab ?? firstWithData ?? tabs[0] ?? null;
  const effort = efforts.find((e) => e.window === effective) ?? null;
  const pr = effort ? isPR(effort, allTimeEfforts, true) : false;
  const distKm = effective ? windowToKm(effective) : 0;
  const totalSec = effort && distKm ? effort.value * distKm : null;

  return (
    <CardShell
      title="BEST TIME · BY DISTANCE"
      tabs={
        tabs.length > 0 ? (
          <TabBar tabs={tabs} active={effective ?? ""} onChange={(t) => setActiveTab(t)} />
        ) : undefined
      }
    >
      <ColHeaders cols={["#", "Activity", "Date", "Time", "Pace"]} gridCols={RUN_TIME_GRID} />
      {!effort ? (
        <EmptyRow msg="No running pace data for this period." />
      ) : (
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
          <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
            1
          </span>
          <span
            style={{
              fontSize: 13,
              fontWeight: 600,
              color: "var(--ink)",
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
            }}
          >
            <ActivityName id={effort.activity_id} />
          </span>
          <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
            {fmtShortDate(effort.date)}
          </span>
          <span style={{ display: "flex", alignItems: "baseline", gap: 5, whiteSpace: "nowrap" }}>
            <span
              style={{
                fontFamily: "'JetBrains Mono',monospace",
                fontSize: 15,
                fontWeight: 700,
                color: "var(--run,#ec4a26)",
              }}
            >
              {totalSec !== null ? formatDuration(totalSec) : "—"}
            </span>
            {pr && <PrBadge color="var(--run,#ec4a26)" />}
          </span>
          <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
            {formatPace(effort.value)}
          </span>
        </div>
      )}
    </CardShell>
  );
}

// ─── Cycling power curve ──────────────────────────────────────────────────────

const PDC_TICKS: [number, string][] = [
  [5, "5s"],
  [30, "30s"],
  [60, "1m"],
  [300, "5m"],
  [1200, "20m"],
  [3600, "60m"],
];

function PowerCurveCard({
  efforts,
  allTimeEfforts,
  periodLabel,
}: {
  efforts: BestEffort[];
  allTimeEfforts: BestEffort[];
  periodLabel: string;
}) {
  const curveEfforts = efforts.length > 0 ? efforts : allTimeEfforts;
  const curveLabel = efforts.length > 0 ? periodLabel : "all time";
  if (!curveEfforts.length) {
    return (
      <div
        style={{
          background: "var(--card)",
          border: "1px solid var(--line)",
          borderRadius: 14,
          padding: "20px 22px 16px",
        }}
      >
        <div style={{ fontSize: 15, fontWeight: 700, letterSpacing: "-0.01em", color: "var(--ink)" }}>
          Mean-maximal power
        </div>
        <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 4 }}>No cycling power data.</div>
      </div>
    );
  }

  const pts = curveEfforts
    .map((e) => ({ sec: windowToSeconds(e.window), w: e.value }))
    .filter((p) => p.sec > 0)
    .sort((a, b) => a.sec - b.sec);

  const LN_MIN = Math.log(5);
  const LN_MAX = Math.log(3600);
  const W = 1000;
  const H = 200;
  const xOf = (sec: number) => ((Math.log(Math.max(sec, 5)) - LN_MIN) / (LN_MAX - LN_MIN)) * W;
  const yMin = Math.min(...pts.map((p) => p.w)) * 0.88;
  const yMax = Math.max(...pts.map((p) => p.w)) * 1.04;
  const yOf = (w: number) => (1 - (w - yMin) / (yMax - yMin)) * H;

  const cpStr = pts.map((p) => `${xOf(p.sec).toFixed(1)},${yOf(p.w).toFixed(1)}`).join(" ");
  const areaStr = `0,${H} ${cpStr} ${W},${H}`;

  return (
    <div
      style={{
        background: "var(--card)",
        border: "1px solid var(--line)",
        borderRadius: 14,
        padding: "20px 22px 16px",
      }}
    >
      <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", marginBottom: 14 }}>
        <div>
          <div style={{ fontSize: 15, fontWeight: 700, letterSpacing: "-0.01em", color: "var(--ink)" }}>
            Mean-maximal power
          </div>
          <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 1 }}>
            Best average power held for each duration · {curveLabel.toLowerCase()}
          </div>
        </div>
      </div>
      <div style={{ position: "relative", height: 200 }}>
        <svg
          viewBox={`0 0 ${W} ${H}`}
          preserveAspectRatio="none"
          style={{ width: "100%", height: "100%", display: "block" }}
        >
          <polygon points={areaStr} fill="var(--emberSoft,rgba(61,127,214,0.10))" />
          <polyline
            points={cpStr}
            fill="none"
            stroke="var(--bike,#3d7fd6)"
            strokeWidth={2.5}
            strokeLinejoin="round"
            vectorEffect="non-scaling-stroke"
          />
        </svg>
      </div>
      <div style={{ position: "relative", height: 16, marginTop: 6 }}>
        {PDC_TICKS.map(([sec, label]) => (
          <span
            key={sec}
            style={{
              position: "absolute",
              left: `${(xOf(sec) / W) * 100}%`,
              transform: "translateX(-50%)",
              fontFamily: "'JetBrains Mono',monospace",
              fontSize: 10,
              color: "var(--ink3)",
            }}
          >
            {label}
          </span>
        ))}
      </div>
    </div>
  );
}

// ─── Cycling Peak Power (PEAK POWER) ─────────────────────────────────────────

const BIKE_PWR_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.55fr 0.85fr 0.45fr";
const BIKE_PWR_GRID_NO_WKG = "28px minmax(120px,1.3fr) 0.5fr 0.55fr 0.85fr";

function BikePowerCard({
  efforts,
  allTimeEfforts,
  weightKg,
}: {
  efforts: BestEffort[];
  allTimeEfforts: BestEffort[];
  weightKg: number | null;
}) {
  const tabs = useMemo(
    () =>
      [...allTimeEfforts]
        .sort((a, b) => windowToSeconds(a.window) - windowToSeconds(b.window))
        .map((e) => e.window),
    [allTimeEfforts],
  );
  const [activeTab, setActiveTab] = useState<string | null>(null);
  const firstWithData = useMemo(() => tabs.find((t) => efforts.some((e) => e.window === t)), [tabs, efforts]);
  const effective = activeTab ?? firstWithData ?? tabs[0] ?? null;
  const effort = efforts.find((e) => e.window === effective) ?? null;
  const pr = effort ? isPR(effort, allTimeEfforts, false) : false;
  const wkg = effort && weightKg ? (effort.value / weightKg).toFixed(1) : null;
  const gridCols = wkg ? BIKE_PWR_GRID : BIKE_PWR_GRID_NO_WKG;
  const headers = wkg ? ["#", "Activity", "Date", "Duration", "Power", "W/kg"] : ["#", "Activity", "Date", "Duration", "Power"];

  return (
    <CardShell
      title="PEAK POWER"
      tabs={
        tabs.length > 0 ? (
          <TabBar tabs={tabs} active={effective ?? ""} onChange={(t) => setActiveTab(t)} />
        ) : undefined
      }
    >
      <ColHeaders cols={headers} gridCols={gridCols} />
      {!effort ? (
        <EmptyRow msg="No cycling power data for this period." />
      ) : (
        <div
          style={{
            display: "grid",
            gridTemplateColumns: gridCols,
            gap: 8,
            padding: "11px 18px",
            borderBottom: "1px solid var(--line)",
            alignItems: "center",
          }}
        >
          <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
            1
          </span>
          <span
            style={{
              fontSize: 13,
              fontWeight: 600,
              color: "var(--ink)",
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
            }}
          >
            <ActivityName id={effort.activity_id} />
          </span>
          <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink3)" }}>
            {fmtShortDate(effort.date)}
          </span>
          <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
            {effort.window}
          </span>
          <span style={{ display: "flex", alignItems: "baseline", gap: 5, whiteSpace: "nowrap" }}>
            <span
              style={{
                fontFamily: "'JetBrains Mono',monospace",
                fontSize: 15,
                fontWeight: 700,
                color: "var(--bike,#3d7fd6)",
              }}
            >
              {Math.round(effort.value)}w
            </span>
            {pr && <PrBadge color="var(--bike,#3d7fd6)" />}
          </span>
          {wkg && (
            <span style={{ fontFamily: "'JetBrains Mono',monospace", fontSize: 12, color: "var(--ink2)" }}>
              {wkg}
            </span>
          )}
        </div>
      )}
    </CardShell>
  );
}

// ─── Longest Rides ────────────────────────────────────────────────────────────

const RIDES_GRID = "28px minmax(120px,1.3fr) 0.5fr 0.55fr 0.85fr 0.55fr";

function LongestRidesCard() {
  const { data } = useQuery({
    queryKey: ["activities", "longest-rides"],
    queryFn: () => listActivities({ sport: "bike", sort: "-distance", limit: 5 }),
  });
  const rides = data?.data ?? [];

  return (
    <CardShell title="LONGEST RIDES · TOP 5">
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
            <span
              style={{
                fontSize: 13,
                fontWeight: 600,
                color: "var(--ink)",
                whiteSpace: "nowrap",
                overflow: "hidden",
                textOverflow: "ellipsis",
              }}
            >
              {a.name}
            </span>
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
  const { data } = useQuery({
    queryKey: ["activities", "biggest-climbs"],
    queryFn: () =>
      listActivities({ sport: "bike", limit: 50 }).then((r) => ({
        ...r,
        data: [...r.data]
          .filter((a) => a.ascent != null)
          .sort((a, b) => (b.ascent ?? 0) - (a.ascent ?? 0))
          .slice(0, 5),
      })),
  });
  const rides = data?.data ?? [];

  return (
    <CardShell title="BIGGEST CLIMBS · ELEVATION GAIN · TOP 5">
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
            <span
              style={{
                fontSize: 13,
                fontWeight: 600,
                color: "var(--ink)",
                whiteSpace: "nowrap",
                overflow: "hidden",
                textOverflow: "ellipsis",
              }}
            >
              {a.name}
            </span>
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

  const bqOpts = (kind: Parameters<typeof listBestEfforts>[1], per: BestEffortPeriod) => ({
    queryKey: ["best-efforts", athleteId, kind, per],
    queryFn: () => listBestEfforts(athleteId, kind, per),
    enabled: !!athleteId,
  });

  const { data: runPowerData } = useQuery(bqOpts("running_power", apiPeriod));
  const { data: runPowerAll } = useQuery(bqOpts("running_power", "all"));
  const { data: runPaceData } = useQuery(bqOpts("running_pace", apiPeriod));
  const { data: runPaceAll } = useQuery(bqOpts("running_pace", "all"));
  const { data: bikePowerData } = useQuery(bqOpts("cycling_power", apiPeriod));
  const { data: bikePowerAll } = useQuery(bqOpts("cycling_power", "all"));

  const runPowerEfforts = runPowerData?.data ?? [];
  const runPowerAllEfforts = runPowerAll?.data ?? [];
  const runPaceEfforts = runPaceData?.data ?? [];
  const runPaceAllEfforts = runPaceAll?.data ?? [];
  const bikePowerEfforts = bikePowerData?.data ?? [];
  const bikePowerAllEfforts = bikePowerAll?.data ?? [];

  const hasRunPower = runPowerAllEfforts.length > 0;

  const runPrCount = [
    ...runPowerEfforts.filter((e) => isPR(e, runPowerAllEfforts, false)),
    ...runPaceEfforts.filter((e) => isPR(e, runPaceAllEfforts, true)),
  ].length;
  const bikePrCount = bikePowerEfforts.filter((e) => isPR(e, bikePowerAllEfforts, false)).length;

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

          {hasRunPower && (
            <RunPowerCard efforts={runPowerEfforts} allTimeEfforts={runPowerAllEfforts} rCP={rCP} />
          )}

          <RunPaceCard efforts={runPaceEfforts} allTimeEfforts={runPaceAllEfforts} />
        </div>

        {/* Cycling section */}
        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <SectionHeader
            label="Cycling"
            color="var(--bike,#3d7fd6)"
            summary={`${bikePrCount} record${bikePrCount === 1 ? "" : "s"} standing this period`}
          />

          <PowerCurveCard
            efforts={bikePowerEfforts}
            allTimeEfforts={bikePowerAllEfforts}
            periodLabel={periodLabel}
          />

          <BikePowerCard
            efforts={bikePowerEfforts}
            allTimeEfforts={bikePowerAllEfforts}
            weightKg={weightKg}
          />

          <LongestRidesCard />

          <BiggestClimbsCard />
        </div>
      </div>
    </div>
  );
}
