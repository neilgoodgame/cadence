import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { getFitness } from "../api/athletes";
import { listActivities, listAllActivities } from "../api/activities";
import { getContexts } from "../api/auth";
import { Card } from "../components/Card";
import { CoachingSection } from "./dashboard/CoachingSection";
import { StatCardsRow } from "./dashboard/StatCardsRow";
import { ThresholdSummaryCard } from "./dashboard/ThresholdSummaryCard";
import { NextRaceCard } from "./dashboard/NextRaceCard";
import { UpcomingWorkoutsCard } from "./dashboard/UpcomingWorkoutsCard";
import { WeekCalendar } from "./dashboard/WeekCalendar";
import { TrainingHistory } from "./dashboard/TrainingHistory";

// new Date(...).toISOString().slice(0,10) reads back the *UTC* calendar date - for a UTC+ user,
// local midnight is still the previous day in UTC, silently shifting these date-range boundaries
// back by a day (same class of bug already fixed once in WeekCalendar.tsx's own localIso()).
function localIso(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function isoDaysAgo(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return localIso(d);
}

function historyWindow(): { after: string; before: string } {
  const today = new Date();
  const day = today.getDay();
  const thisMonday = new Date(today);
  thisMonday.setDate(today.getDate() - (day === 0 ? 6 : day - 1));
  thisMonday.setHours(0, 0, 0, 0);
  const after = new Date(thisMonday);
  after.setDate(thisMonday.getDate() - 112); // 16 weeks back
  const before = new Date(thisMonday);
  before.setDate(thisMonday.getDate() - 1);  // last Sunday (inclusive)
  return { after: localIso(after), before: localIso(before) };
}

export function DashboardScreen() {
  const { user, isCoachAccount } = useAuth();
  const { after: historyAfter, before: historyBefore } = useMemo(() => historyWindow(), []);

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

  const historyActivitiesQuery = useQuery({
    queryKey: ["activities", "training-history", historyAfter],
    // Not listActivities({ limit: 200 }) - the backend hard-caps a single page at 200
    // regardless of what's requested, so a busy account can have more activities than that
    // within the 16-week window alone (found live: 259 for the real test account), silently
    // dropping the oldest weeks. listAllActivities pages through everything in range.
    queryFn: () => listAllActivities({ after: historyAfter, before: historyBefore }),
    enabled: !!user,
  });

  const contextsQuery = useQuery({
    queryKey: ["contexts"],
    queryFn: getContexts,
    // isCoachAccount, not user.is_coach: this must stay based on the signed-in principal,
    // not whichever profile is currently active - otherwise switching to view as a
    // non-coaching athlete would hide the coach's own "Your athletes" section entirely.
    enabled: isCoachAccount,
  });

  const points = fitnessQuery.data?.data ?? [];

  const activities = useMemo(() => activitiesQuery.data?.data ?? [], [activitiesQuery.data]);
  const historyActivities = useMemo(() => historyActivitiesQuery.data ?? [], [historyActivitiesQuery.data]);

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

      <Card>
        <WeekCalendar activities={activities} athleteId={user.id} />
      </Card>

      <NextRaceCard />

      <UpcomingWorkoutsCard />

      <StatCardsRow points={points} weekTss={weekTss} />

      <ThresholdSummaryCard />

      <Card>
        <TrainingHistory activities={historyActivities} athleteId={user.id} />
      </Card>

{isCoachAccount && <CoachingSection athletes={contextsQuery.data?.coaching ?? []} />}
    </div>
  );
}
