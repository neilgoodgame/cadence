import { useState } from "react";
import { useAuth } from "../auth/AuthContext";
import { ProfileTab } from "./preferences/ProfileTab";
import { SharingTab } from "./preferences/SharingTab";
import { TokensTab } from "./preferences/TokensTab";
import { ZoneEditorTab } from "./preferences/ZoneEditorTab";
import type { ZoneType } from "../api/types";

type TabKey = "profile" | ZoneType | "sharing" | "tokens";

const TABS: { key: TabKey; label: string }[] = [
  { key: "profile", label: "Profile" },
  { key: "heart_rate", label: "Heart rate" },
  { key: "bike_power", label: "Bike power" },
  { key: "run_power", label: "Run power" },
  { key: "pace", label: "Pace" },
  { key: "sharing", label: "Sharing" },
  { key: "tokens", label: "API tokens" },
];

const ZONE_TYPES: ZoneType[] = ["heart_rate", "bike_power", "run_power", "pace"];

export function PreferencesScreen() {
  const { user } = useAuth();
  const [tab, setTab] = useState<TabKey>("profile");

  if (!user) {
    return null;
  }

  return (
    <div style={{ display: "flex", gap: 32 }}>
      <nav style={{ width: 178, flexShrink: 0, display: "flex", flexDirection: "column", gap: 2 }}>
        {TABS.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            style={{
              textAlign: "left",
              border: "none",
              padding: "9px 12px",
              borderRadius: 8,
              fontSize: 14,
              fontWeight: 600,
              background: tab === t.key ? "var(--elev)" : "transparent",
              color: tab === t.key ? "var(--ink)" : "var(--ink2)",
            }}
          >
            {t.label}
          </button>
        ))}
      </nav>

      <div style={{ flex: 1 }}>
        {tab === "profile" && <ProfileTab />}
        {ZONE_TYPES.includes(tab as ZoneType) && <ZoneEditorTab athleteId={user.id} type={tab as ZoneType} />}
        {tab === "sharing" && <SharingTab />}
        {tab === "tokens" && <TokensTab />}
      </div>
    </div>
  );
}
