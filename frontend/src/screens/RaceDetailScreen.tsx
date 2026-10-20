import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { getActivity } from "../api/activities";
import { getCourse } from "../api/courses";
import { getRace, getRaceBlock, getRaceSplits, listRaces } from "../api/races";
import { useAuth } from "../auth/AuthContext";
import type { Race, RaceBlock, Sport } from "../api/types";
import { daysUntil } from "../lib/dates";
import { CommentsCard } from "./race-detail/CommentsCard";
import { ContextCard } from "./race-detail/ContextCard";
import { CourseCard } from "./race-detail/CourseCard";
import { DurabilityCard } from "./race-detail/DurabilityCard";
import { EditRaceModal } from "./race-detail/EditRaceModal";
import { GoalResultCard } from "./race-detail/GoalResultCard";
import { Header } from "./race-detail/Header";
import { HistoryAtDistanceCard } from "./race-detail/HistoryAtDistanceCard";
import { KeySessionsCard } from "./race-detail/KeySessionsCard";
import { PacingPlanCard } from "./race-detail/PacingPlanCard";
import { historyAtDistance, personalBestSeconds } from "./race-detail/raceHistory";
import { RaceDayFormCard } from "./race-detail/RaceDayFormCard";
import { downloadRaceReport } from "./race-detail/raceReport";
import { SplitsCard } from "./race-detail/SplitsCard";
import { TrainingBlockCard } from "./race-detail/TrainingBlockCard";
import { WeatherCard } from "./race-detail/WeatherCard";

export function RaceDetailScreen() {
  const { raceId } = useParams<{ raceId: string }>();
  const { user } = useAuth();
  const [editing, setEditing] = useState(false);
  const [selectedWeek, setSelectedWeek] = useState<number | null>(null);
  const [downloadingReport, setDownloadingReport] = useState(false);

  const { data: race } = useQuery({ queryKey: ["race", raceId], queryFn: () => getRace(raceId!), enabled: !!raceId });
  const { data: racesData } = useQuery({ queryKey: ["races"], queryFn: listRaces });
  const { data: block } = useQuery({ queryKey: ["race-block", raceId], queryFn: () => getRaceBlock(raceId!), enabled: !!raceId });
  const { data: splits } = useQuery({
    queryKey: ["race-splits", raceId, "5"],
    queryFn: () => getRaceSplits(raceId!, "5"),
    enabled: !!raceId && !!race?.result_time,
  });
  const { data: course } = useQuery({
    queryKey: ["course", race?.course_id],
    queryFn: () => getCourse(race!.course_id!),
    enabled: !!race?.course_id,
  });
  const { data: activity } = useQuery({
    queryKey: ["activity", race?.activity_id],
    queryFn: () => getActivity(race!.activity_id!),
    enabled: !!race?.activity_id,
  });
  const parent = race?.parent_race_id ? racesData?.data.find((r) => r.id === race.parent_race_id) ?? null : null;
  const { data: parentBlock } = useQuery({
    queryKey: ["race-block", parent?.id],
    queryFn: () => getRaceBlock(parent!.id),
    enabled: !!parent && !!race?.result_time,
  });

  if (!race || !block || !user) {
    return <div style={{ color: "var(--ink3)" }}>Loading…</div>;
  }

  const allRaces = racesData?.data ?? [];
  const tuneUps = race.priority === "A" ? allRaces.filter((r) => r.parent_race_id === race.id) : [];
  const history = race.distance_km != null ? historyAtDistance(allRaces, race.sport, race.distance_km, race.id) : [];
  const pbS = race.distance_km != null ? personalBestSeconds(history, race.result_time ? race : null) : null;
  const predictionS = block.prediction?.time_s ?? null;
  const sportCast = (race.sport || null) as Sport | null;

  const showPacingPlan = !race.result_time && race.sport === "run" && race.priority !== "C";
  const showSplits = !!race.result_time && race.sport === "run";
  const showDurability = race.priority === "A" && (race.sport === "run" || race.sport === "bike") && block.weeks.length >= 3 && !!block.durability;
  const showComments = !race.result_time;
  // Matches WeatherService's FORECAST_HORIZON_DAYS (both backends) - no point mounting the
  // query for a race the endpoint will just report as unavailable anyway.
  const showWeather = !!race.course_id && daysUntil(race.date) >= 0 && daysUntil(race.date) <= 10;
  const elevationAvailable = !!course && course.elevation_quality === "ok" && course.sections.length > 0;

  // Matches TrainingBlockCard's own "Compare: {name} {year}" label, for the report chart's legend.
  const comparisonRace = block.comparison ? allRaces.find((r) => r.id === block.comparison!.race_id) ?? null : null;
  const comparisonYear = block.comparison?.weeks[0] ? new Date(block.comparison.weeks[0].week_start + "T00:00:00").getFullYear() : null;
  const comparisonLabel = block.comparison ? `${comparisonRace?.name ?? "Comparison"}${comparisonYear ? ` ${comparisonYear}` : ""}` : null;

  // Narrowed (non-undefined) copies for handleDownloadReport's closure below - TS doesn't
  // carry the `if (!race || !block...)` guard's narrowing into a nested function body.
  const narrowedRace: Race = race;
  const narrowedBlock: RaceBlock = block;

  async function handleDownloadReport() {
    setDownloadingReport(true);
    try {
      await downloadRaceReport({
        race: narrowedRace,
        block: narrowedBlock,
        course: course ?? null,
        activity: activity ?? null,
        pbS,
        history,
        splitsByMode: splits ? { "5": splits } : {},
        showSplits,
        showWeather,
        elevationAvailable,
        comparisonLabel,
      });
    } finally {
      setDownloadingReport(false);
    }
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <Header race={race} onEdit={() => setEditing(true)} onDownloadReport={handleDownloadReport} downloadingReport={downloadingReport} />

      <ContextCard race={race} tuneUps={tuneUps} parent={parent} parentPrediction={parentBlock?.prediction ?? null} />

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(290px, 1fr))", gap: 16 }}>
        {showWeather && <WeatherCard raceId={race.id} />}
        <GoalResultCard race={race} pbS={pbS} predictionS={predictionS} halves={splits?.halves ?? null} activity={activity ?? null} />
        <RaceDayFormCard form={block.race_day_form} />
        <HistoryAtDistanceCard races={history} pbS={pbS} />
      </div>

      <CourseCard race={race} course={course ?? null} />

      {showPacingPlan && <PacingPlanCard race={race} sweatRateLPerH={block.sweat_rate_l_per_h} />}

      {showSplits && <SplitsCard raceId={race.id} distanceKm={race.distance_km} course={course ?? null} />}

      <TrainingBlockCard race={race} block={block} allRaces={allRaces} selectedIndex={selectedWeek != null ? selectedWeek - 1 : null} onSelectIndex={(i) => setSelectedWeek(i != null ? i + 1 : null)} />

      {showDurability && <DurabilityCard athleteId={user.id} block={block} />}

      {showComments && <CommentsCard raceId={race.id} selectedWeek={selectedWeek} onSelectWeek={setSelectedWeek} />}

      <KeySessionsCard
        key={race.id}
        keySessions={block.key_sessions}
        isARace={race.priority === "A"}
        sport={sportCast}
        pageSize={user.race_key_session_cap}
      />

      {editing && <EditRaceModal race={race} onClose={() => setEditing(false)} />}
    </div>
  );
}
