import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { getFitness } from "../api/athletes";
import { listActivities } from "../api/activities";
import { getContexts } from "../api/auth";
import { Card } from "../components/Card";
import { BestEffortsRow } from "./dashboard/BestEffortsRow";
import { CoachingSection } from "./dashboard/CoachingSection";
import { PmcChart } from "./dashboard/PmcChart";
import { RecentActivitiesTable } from "./dashboard/RecentActivitiesTable";
import { SportDistribution } from "./dashboard/SportDistribution";
import { StatCardsRow } from "./dashboard/StatCardsRow";
import { WeeklyVolumeChart } from "./dashboard/WeeklyVolumeChart";

const RANGES = { "6w": 42, "12w": 84, Season: 180 } as const;
type RangeKey = keyof typeof RANGES;

function isoDaysAgo(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

export function DashboardScreen() {
  const { user } = useAuth();
  const [range, setRange] = useState<RangeKey>("12w");

  const fitnessQuery = useQuery({
    queryKey: ["fitness", user?.id, range],
    queryFn: () => getFitness(user!.id, isoDaysAgo(RANGES[range]), isoDaysAgo(0)),
    enabled: !!user,
  });

  const activitiesQuery = useQuery({
    // No `sort` param: the `sort` query param only accepts the same field aliases CQL does
    // (tss, distance, duration, power, hr, maxhr, sport, environment, name) - there's no
    // date alias, so sorting by date means relying on the list's documented default
    // ordering (-start_date, -id), which is exactly what this screen wants anyway.
    queryKey: ["activities", "dashboard"],
    queryFn: () => listActivities({ limit: 200 }),
    enabled: !!user,
  });

  const contextsQuery = useQuery({
    queryKey: ["contexts"],
    queryFn: getContexts,
    enabled: !!user?.is_coach,
  });

  const points = fitnessQuery.data?.data ?? [];

  const activities = useMemo(() => activitiesQuery.data?.data ?? [], [activitiesQuery.data]);

  const dailyTss = useMemo(() => {
    const map = new Map<string, number>();
    for (const activity of activities) {
      const date = activity.start_date.slice(0, 10);
      map.set(date, (map.get(date) ?? 0) + activity.tss);
    }
    return map;
  }, [activities]);

  const weekTss = useMemo(() => {
    const cutoff = isoDaysAgo(7);
    return activities
      .filter((a) => a.start_date.slice(0, 10) >= cutoff)
      .reduce((sum, a) => sum + a.tss, 0);
  }, [activities]);

  if (!user) {
    return null;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 24 }}>
      <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>Welcome back, {user.name}.</h1>

      <StatCardsRow points={points} weekTss={weekTss} />

      <Card>
        <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 14 }}>
          <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Performance management</h2>
          <div style={{ display: "flex", gap: 4 }}>
            {(Object.keys(RANGES) as RangeKey[]).map((key) => (
              <button
                key={key}
                onClick={() => setRange(key)}
                style={{
                  border: "1px solid var(--line)",
                  borderRadius: 8,
                  padding: "6px 12px",
                  fontSize: 13,
                  fontWeight: 600,
                  background: range === key ? "var(--elev)" : "transparent",
                  color: range === key ? "var(--ink)" : "var(--ink3)",
                }}
              >
                {key}
              </button>
            ))}
          </div>
        </div>
        <PmcChart points={points} dailyTss={dailyTss} />
      </Card>

      <BestEffortsRow athleteId={user.id} />

      {user.is_coach && <CoachingSection athletes={contextsQuery.data?.coaching ?? []} />}

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 20 }}>
        <Card>
          <WeeklyVolumeChart activities={activities} />
        </Card>
        <Card>
          <SportDistribution activities={activities} />
        </Card>
      </div>

      <Card>
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: "0 0 14px" }}>Recent activities</h2>
        <RecentActivitiesTable activities={activities} />
      </Card>
    </div>
  );
}
