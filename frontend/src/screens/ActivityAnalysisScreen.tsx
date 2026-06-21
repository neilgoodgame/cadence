import { useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getActivity } from "../api/activities";
import { useAuth } from "../auth/AuthContext";
import { CurvesTab } from "./activity-analysis/CurvesTab";
import { Header } from "./activity-analysis/Header";
import { HydrationBlock } from "./activity-analysis/HydrationBlock";
import { LapsTab } from "./activity-analysis/LapsTab";
import { RouteMap } from "./activity-analysis/RouteMap";
import { StatRow } from "./activity-analysis/StatRow";
import { StreamChart } from "./activity-analysis/StreamChart";
import { ZonesTab } from "./activity-analysis/ZonesTab";

type Tab = "laps" | "zones" | "curves";
const TABS: { key: Tab; label: string }[] = [
  { key: "laps", label: "Laps" },
  { key: "zones", label: "Zones" },
  { key: "curves", label: "Curves" },
];

export function ActivityAnalysisScreen() {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const [tab, setTab] = useState<Tab>("laps");

  const { data: activity, isLoading } = useQuery({
    queryKey: ["activity", id],
    queryFn: () => getActivity(id!),
    enabled: !!id,
  });

  if (isLoading || !activity || !user) {
    return <div style={{ color: "var(--ink3)" }}>Loading…</div>;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <Header activity={activity} />
      <StatRow activity={activity} />

      <div style={{ display: "grid", gridTemplateColumns: "1.4fr 1fr", gap: 20 }}>
        <div>
          <StreamChart activity={activity} />
        </div>
        <RouteMap activity={activity} />
      </div>

      <HydrationBlock activity={activity} />

      <div>
        <div style={{ display: "flex", gap: 4, borderBottom: "1px solid var(--line)", marginBottom: 16 }}>
          {TABS.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              style={{
                border: "none",
                background: "none",
                padding: "0 0 10px",
                marginRight: 20,
                fontSize: 14,
                fontWeight: 700,
                borderBottom: tab === t.key ? "2px solid var(--ember)" : "2px solid transparent",
                color: tab === t.key ? "var(--ink)" : "var(--ink3)",
              }}
            >
              {t.label}
            </button>
          ))}
        </div>

        {tab === "laps" && <LapsTab activityId={activity.id} />}
        {tab === "zones" && <ZonesTab activity={activity} athleteId={user.id} />}
        {tab === "curves" && <CurvesTab activityId={activity.id} />}
      </div>
    </div>
  );
}
