import { useQuery } from "@tanstack/react-query";
import { getStreams } from "../../api/activities";
import { bucketIntoZones } from "../../lib/zones";
import type { ZoneSet } from "../../api/types";

const ZONE_COLORS = ["var(--zone-1)", "var(--zone-2)", "var(--zone-3)", "var(--zone-4)", "var(--zone-5)"];

export function ZoneBar({ activityId, hrZones }: { activityId: string; hrZones: ZoneSet | undefined }) {
  const { data, isLoading } = useQuery({
    queryKey: ["activity-streams-zonebar", activityId],
    queryFn: () => getStreams(activityId, ["heartrate"], "low"),
    enabled: !!hrZones,
  });

  if (!hrZones) {
    return null;
  }
  if (isLoading || !data) {
    return <div style={{ fontSize: 12, color: "var(--ink3)" }}>Loading zones…</div>;
  }

  const zoneTimes = bucketIntoZones(data.fields.heartrate ?? [], hrZones);
  const total = zoneTimes.reduce((sum, z) => sum + z.seconds, 0);
  if (total === 0) {
    return <div style={{ fontSize: 12, color: "var(--ink3)" }}>No heart rate data for this activity.</div>;
  }

  return (
    <div>
      <div style={{ display: "flex", height: 10, borderRadius: 5, overflow: "hidden", marginBottom: 10 }}>
        {zoneTimes.map((zone, i) =>
          zone.fraction > 0 ? (
            <div key={zone.name} style={{ width: `${zone.fraction * 100}%`, background: ZONE_COLORS[i % ZONE_COLORS.length] }} />
          ) : null,
        )}
      </div>
      <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
        {zoneTimes.map((zone, i) => (
          <div key={zone.name} style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12, color: "var(--ink2)" }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, background: ZONE_COLORS[i % ZONE_COLORS.length] }} />
            {zone.name} · {Math.round(zone.fraction * 100)}%
          </div>
        ))}
      </div>
    </div>
  );
}
