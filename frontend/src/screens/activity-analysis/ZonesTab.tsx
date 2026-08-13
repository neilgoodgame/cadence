import { useQuery } from "@tanstack/react-query";
import { getStreams } from "../../api/activities";
import { listZones } from "../../api/athletes";
import { bucketIntoZones } from "../../lib/zones";
import { formatDuration, formatPace } from "../../lib/format";
import type { Activity, ZoneSet, ZoneType } from "../../api/types";
import { HeatStrainCard } from "./HeatStrainCard";

const RESOLUTION_SECONDS = 5; // "medium" resolution steps every 5th sample
const ZONE_COLORS = ["var(--zone-1)", "var(--zone-2)", "var(--zone-3)", "var(--zone-4)", "var(--zone-5)"];

function powerZoneType(activity: Activity): ZoneType {
  return activity.sport === "run" ? "run_power" : "bike_power";
}

/** The threshold values this activity's zones are actually computed from - the power/pace ones
 * come from the ThresholdHistory ledger entry effective as of this activity's own date (via
 * listZones' activity-scoped `reference`, see reference_for), LTHR is always live since
 * heart-rate zones never activity-scope. `null` reference means no ledger entry was effective
 * yet at this activity's own date, not "unknown" from a fetch that hasn't resolved. */
function referenceSummary(activity: Activity, zones: ZoneSet[] | undefined, lthr: number | null): string | null {
  const parts: string[] = [];
  const referenceFor = (type: ZoneType) => zones?.find((z) => z.type === type)?.reference;

  if (activity.sport === "run") {
    const criticalRunPower = referenceFor("run_power");
    if (criticalRunPower != null) parts.push(`Critical power ${criticalRunPower}W`);
    const thresholdPace = referenceFor("pace");
    if (thresholdPace != null) parts.push(`Threshold pace ${formatPace(thresholdPace)}`);
  }
  else {
    const ftp = referenceFor("bike_power");
    if (ftp != null) parts.push(`FTP ${ftp}W`);
  }
  if (lthr != null) {
    parts.push(`LTHR ${lthr}bpm`);
  }
  return parts.length > 0 ? parts.join(" · ") : null;
}

function ZoneList({
  title,
  athleteId,
  activityId,
  channel,
  zoneType,
  unit,
}: {
  title: string;
  athleteId: string;
  activityId: string;
  channel: "power" | "heartrate";
  zoneType: ZoneType;
  unit: string;
}) {
  // Scoped to this activity's own threshold snapshot, not the athlete's current profile - see
  // listZones. Harmless to pass for the heart_rate zone list too: the backend only ever
  // activity-scopes bike_power/run_power/pace, heart_rate always reads live regardless.
  const zonesQuery = useQuery({
    queryKey: ["zones", athleteId, activityId],
    queryFn: () => listZones(athleteId, activityId),
  });
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
  const reference = zoneSet.reference;

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "20px 22px" }}>
      <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", letterSpacing: "0.08em", marginBottom: 18 }}>
        {title.toUpperCase()}
      </div>
      {zoneTimes.map((zone, i) => {
        const def = zoneSet.zones[i];
        const isLast = i === zoneSet.zones.length - 1;
        let rangeLabel: string | null = null;
        if (reference != null) {
          const low = Math.round((def.low_pct / 100) * reference);
          const high = Math.round((def.high_pct / 100) * reference);
          rangeLabel = isLast ? `${low}+ ${unit}` : `${low}–${high} ${unit}`;
        }
        return (
          <div key={zone.name} style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 14 }}>
            <span style={{ width: 10, height: 10, borderRadius: 3, flexShrink: 0, background: ZONE_COLORS[i % ZONE_COLORS.length] }} />
            <div style={{ width: 120, flexShrink: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)" }}>{zone.name}</div>
              {rangeLabel != null && (
                <div className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>
                  {rangeLabel}
                </div>
              )}
            </div>
            <div style={{ flex: 1, height: 18, borderRadius: 5, background: "var(--canvas)", overflow: "hidden" }}>
              <div
                style={{
                  height: "100%",
                  width: `${(zone.seconds / maxSeconds) * 100}%`,
                  background: ZONE_COLORS[i % ZONE_COLORS.length],
                  borderRadius: 5,
                }}
              />
            </div>
            <span className="mono" style={{ fontSize: 13, color: "var(--ink)", width: 54, textAlign: "right", flexShrink: 0 }}>
              {formatDuration(zone.seconds)}
            </span>
          </div>
        );
      })}
    </div>
  );
}

export function ZonesTab({ activity, athleteId }: { activity: Activity; athleteId: string }) {
  // Same queryKey as each ZoneList's own fetch below, so react-query serves this from the
  // same cache entry rather than firing a second request.
  const zonesQuery = useQuery({
    queryKey: ["zones", athleteId, activity.id],
    queryFn: () => listZones(athleteId, activity.id),
  });
  const lthr = zonesQuery.data?.data.find((z) => z.type === "heart_rate")?.reference ?? null;
  const summary = referenceSummary(activity, zonesQuery.data?.data, lthr);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      {summary && (
        <div className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>
          Zones based on: {summary}
        </div>
      )}
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
        <ZoneList
          title="Power zones"
          athleteId={athleteId}
          activityId={activity.id}
          channel="power"
          zoneType={powerZoneType(activity)}
          unit="W"
        />
        <ZoneList
          title="Heart rate zones"
          athleteId={athleteId}
          activityId={activity.id}
          channel="heartrate"
          zoneType="heart_rate"
          unit="bpm"
        />
        <HeatStrainCard activityId={activity.id} />
      </div>
    </div>
  );
}
