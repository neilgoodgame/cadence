import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { getFitness } from "../api/athletes";
import { listActivities } from "../api/activities";
import { getContexts } from "../api/auth";
import { Card } from "../components/Card";
import { BestEffortsRow } from "./dashboard/BestEffortsRow";
import { CoachingSection } from "./dashboard/CoachingSection";
import { RecentActivitiesTable } from "./dashboard/RecentActivitiesTable";
import { SportDistribution } from "./dashboard/SportDistribution";
import { StatCardsRow } from "./dashboard/StatCardsRow";
import { WeekCalendar } from "./dashboard/WeekCalendar";
import { WeeklyVolumeChart } from "./dashboard/WeeklyVolumeChart";

function isoDaysAgo(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

export function DashboardScreen() {
  const { user } = useAuth();

  const fitnessQuery = useQuery({
    queryKey: ["fitness", user?.id],
    queryFn: () => getFitness(user!.id, isoDaysAgo(84), isoDaysAgo(0)),
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
        <WeekCalendar activities={activities} athleteId={user.id} />
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
