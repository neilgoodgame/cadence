import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listRaces } from "../api/races";
import type { Sport } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { SegmentedControl } from "../components/SegmentedControl";
import { AddRaceModal } from "./calendar/AddRaceModal";
import { CurrentBlockCard } from "./races/CurrentBlockCard";
import { PastRacesTable } from "./races/PastRacesTable";
import { UpcomingList } from "./races/UpcomingList";

const SPORT_FILTERS: { key: "all" | Sport; label: string }[] = [
  { key: "all", label: "All" },
  { key: "run", label: "Run" },
  { key: "bike", label: "Bike" },
  { key: "multisport", label: "Multisport" },
];

export function RacesScreen() {
  const { user } = useAuth();
  const [sportFilter, setSportFilter] = useState<"all" | Sport>("all");
  const [showAddModal, setShowAddModal] = useState(false);
  const { data } = useQuery({ queryKey: ["races"], queryFn: listRaces });

  const races = (data?.data ?? []).filter((r) => sportFilter === "all" || r.sport === sportFilter);
  const today = new Date().toISOString().slice(0, 10);
  const upcoming = races.filter((r) => r.date >= today);
  const past = races.filter((r) => r.date < today);

  const nextA = [...upcoming].filter((r) => r.priority === "A").sort((a, b) => a.date.localeCompare(b.date))[0];
  const tuneUps = nextA ? upcoming.filter((r) => r.parent_race_id === nextA.id) : [];

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 24 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 16, flexWrap: "wrap" }}>
        <div>
          <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>Races</h1>
          <div style={{ fontSize: 13, color: "var(--ink3)", marginTop: 4 }}>
            {upcoming.length} upcoming &middot; {past.length} past &middot; A races get a {user?.race_block_weeks ?? 16}-week
            training block; B and C races sit inside them
          </div>
        </div>
        <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
          <SegmentedControl
            size="sm"
            options={SPORT_FILTERS.map((s) => ({ key: s.key, value: s.key, label: s.label }))}
            value={sportFilter}
            onChange={setSportFilter}
          />
          <button
            onClick={() => setShowAddModal(true)}
            style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", cursor: "pointer" }}
          >
            + Add race
          </button>
        </div>
      </div>

      {nextA && <CurrentBlockCard race={nextA} tuneUps={tuneUps} />}

      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
        <h2 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 10px" }}>Upcoming</h2>
        <UpcomingList races={upcoming} />
      </div>

      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 20 }}>
        <h2 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 10px" }}>Past races</h2>
        <PastRacesTable races={past} />
      </div>

      {showAddModal && <AddRaceModal date={today} onClose={() => setShowAddModal(false)} />}
    </div>
  );
}
