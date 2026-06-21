import { useQuery } from "@tanstack/react-query";
import { getStreams } from "../../api/activities";
import { listZones } from "../../api/athletes";
import { bucketIntoZones } from "../../lib/zones";
import { formatDuration } from "../../lib/format";
import type { Activity, ZoneType } from "../../api/types";

const RESOLUTION_SECONDS = 5; // "medium" resolution steps every 5th sample
const ZONE_COLORS = ["var(--zone-1)", "var(--zone-2)", "var(--zone-3)", "var(--zone-4)", "var(--zone-5)"];

function powerZoneType(activity: Activity): ZoneType {
  return activity.sport === "run" ? "run_power" : "bike_power";
}

function ZoneList({
  title,
  color,
  athleteId,
  activityId,
  channel,
  zoneType,
}: {
  title: string;
  color: string;
  athleteId: string;
  activityId: string;
  channel: "power" | "heartrate";
  zoneType: ZoneType;
}) {
  const zonesQuery = useQuery({ queryKey: ["zones", athleteId], queryFn: () => listZones(athleteId) });
  const streamsQuery = useQuery({
    queryKey: ["activity-streams-zones", activityId, channel],
    queryFn: () => getStreams(activityId, [channel], "medium"),
  });

  const zoneSet = zonesQuery.data?.data.find((z) => z.type === zoneType);
  if (!zoneSet || !streamsQuery.data) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading…</div>;
  }

  const zoneTimes = bucketIntoZones(streamsQuery.data.fields[channel] ?? [], zoneSet, RESOLUTION_SECONDS);
  const maxSeconds = Math.max(1, ...zoneTimes.map((z) => z.seconds));

  return (
    <div>
      <div className="mono" style={{ fontSize: 11, color, fontWeight: 600, letterSpacing: "0.06em", marginBottom: 10 }}>
        {title.toUpperCase()}
      </div>
      {zoneTimes.map((zone, i) => (
        <div key={zone.name} style={{ display: "flex", alignItems: "center", gap: 10, padding: "5px 0", fontSize: 13 }}>
          <span style={{ width: 8, height: 8, borderRadius: 2, background: ZONE_COLORS[i % ZONE_COLORS.length] }} />
          <span style={{ width: 110 }}>{zone.name}</span>
          <div style={{ flex: 1, height: 6, background: "var(--elev)", borderRadius: 3 }}>
            <div
              style={{
                width: `${(zone.seconds / maxSeconds) * 100}%`,
                height: "100%",
                background: ZONE_COLORS[i % ZONE_COLORS.length],
                borderRadius: 3,
              }}
            />
          </div>
          <span className="mono" style={{ width: 60, textAlign: "right", color: "var(--ink2)" }}>
            {formatDuration(zone.seconds)}
          </span>
        </div>
      ))}
    </div>
  );
}

export function ZonesTab({ activity, athleteId }: { activity: Activity; athleteId: string }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 24 }}>
      <ZoneList
        title="Power zones"
        color="var(--ember)"
        athleteId={athleteId}
        activityId={activity.id}
        channel="power"
        zoneType={powerZoneType(activity)}
      />
      <ZoneList
        title="Heart rate zones"
        color="#e0442e"
        athleteId={athleteId}
        activityId={activity.id}
        channel="heartrate"
        zoneType="heart_rate"
      />
    </div>
  );
}
