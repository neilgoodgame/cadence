import { useMemo, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getDeviceAgreementTrend } from "../api/athletes";
import type { DeviceAgreementTrend, StreamComparisonField } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { Card } from "../components/Card";
import { DeviceAgreementTrendChart, type AgreementTrendPoint } from "./DeviceAgreementTrendChart";

const VALID_FIELDS: StreamComparisonField[] = ["power", "heartrate", "cadence"];

const FIELD_LABELS: Record<StreamComparisonField, string> = {
  power: "Power",
  heartrate: "Heartrate",
  cadence: "Cadence",
};

const FIELD_UNITS: Record<StreamComparisonField, string> = { power: "W", heartrate: "bpm", cadence: "rpm" };

// The noun a skipped-activity caption uses for the field's source ("no wrist HR source").
const SOURCE_NOUNS: Record<StreamComparisonField, string> = {
  power: "power source",
  heartrate: "HR source",
  cadence: "cadence source",
};

// Device-class tags per field (issue #540 phase 5) - the same vocabulary the backend validates
// device_a/device_b against, hardcoded client-side since there's no endpoint that lists them.
// Power's depends on sport (bike vs run have different tag sets), so it takes the current
// sport; the other two ignore it.
function deviceTags(field: StreamComparisonField, sport: string): string[] {
  if (field === "power") return sport === "run" ? ["native", "stryd"] : ["trainer", "pedals"];
  if (field === "heartrate") return ["strap", "wrist"];
  return ["pedals", "trainer", "watch"];
}

function deviceLabel(tag: string): string {
  return tag.charAt(0).toUpperCase() + tag.slice(1);
}

const SPORT_OPTIONS: { value: string; label: string }[] = [
  { value: "bike", label: "Bike" },
  { value: "run", label: "Run" },
  { value: "swim", label: "Swim" },
  { value: "walk", label: "Walk" },
  { value: "row", label: "Row" },
];

// Same ISO-date convention as thresholdSuggestions.ts's todayISODate (UTC-based).
function isoDaysAgo(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

function todayISODate(): string {
  return new Date().toISOString().slice(0, 10);
}

// "12 activities in range had no wrist HR source; ..." - only non-zero counters appear, so a
// fully-populated range renders no caption at all. The first non-zero clause carries the
// "activities in range" framing; later ones don't repeat it. Raw (lowercase) tags read better
// in prose than the pickers' capitalized labels.
function skipCaption(skipped: DeviceAgreementTrend["skipped"], field: StreamComparisonField, deviceA: string, deviceB: string): string | null {
  const parts: string[] = [];
  if (skipped.no_device_a > 0) {
    parts.push(`${skipped.no_device_a} had no ${deviceA} ${SOURCE_NOUNS[field]}`);
  }
  if (skipped.no_device_b > 0) {
    parts.push(`${skipped.no_device_b} had no ${deviceB} ${SOURCE_NOUNS[field]}`);
  }
  if (skipped.insufficient_overlap > 0) {
    parts.push(`${skipped.insufficient_overlap} had both sources but not enough overlap to compare`);
  }
  if (parts.length === 0) return null;
  const firstCount = skipped.no_device_a > 0 ? skipped.no_device_a : skipped.no_device_b > 0 ? skipped.no_device_b : skipped.insufficient_overlap;
  parts[0] = parts[0].replace(/^(\d+)/, `$1 ${firstCount === 1 ? "activity" : "activities"} in range`);
  return parts.join("; ");
}

const controlStyle: React.CSSProperties = {
  padding: "8px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 13,
  color: "var(--ink)",
};

function Control({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: "block" }}>
      <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>{label}</div>
      {children}
    </label>
  );
}

function DeviceAgreementTrendContent({ field }: { field: StreamComparisonField }) {
  const { user } = useAuth();
  // For power, sport is required (the device vocabulary differs between bike and run); for the
  // other fields it's an optional filter, so "" (All) is a valid value there.
  const [sport, setSport] = useState(field === "power" ? "bike" : "");
  const [deviceA, setDeviceA] = useState(() => deviceTags(field, sport)[0]);
  const [deviceB, setDeviceB] = useState(() => deviceTags(field, sport)[1]);
  // Default to the last ~3 months - long enough for a trend to be visible, short enough that
  // the endpoint's per-activity comparison cost stays bounded (see the phase 5 design doc).
  const [fromDate, setFromDate] = useState(() => isoDaysAgo(90));
  const [toDate, setToDate] = useState(() => todayISODate());
  // Only meaningful for field="power" (the backend ignores it otherwise).
  const [excludeDropouts, setExcludeDropouts] = useState(true);
  const [includeDuplicates, setIncludeDuplicates] = useState(false);

  // Switching bike<->run changes power's whole tag vocabulary, so reset both pickers to the
  // new defaults rather than keeping a now-invalid tag selected.
  function handleSportChange(next: string) {
    setSport(next);
    if (field === "power") {
      const tags = deviceTags(field, next);
      setDeviceA(tags[0]);
      setDeviceB(tags[1]);
    }
  }

  // The backend 400s on device_a == device_b - swap instead, so picking the other picker's
  // current tag just exchanges them rather than producing an error state.
  function handleDeviceAChange(next: string) {
    setDeviceA(next);
    if (next === deviceB) setDeviceB(deviceA);
  }

  function handleDeviceBChange(next: string) {
    setDeviceB(next);
    if (next === deviceA) setDeviceA(deviceB);
  }

  const trendQuery = useQuery({
    queryKey: ["device-agreement-trend", user?.id, field, deviceA, deviceB, sport, fromDate, toDate, excludeDropouts, includeDuplicates],
    queryFn: () =>
      getDeviceAgreementTrend(
        user!.id,
        field,
        deviceA,
        deviceB,
        sport || undefined,
        fromDate || undefined,
        toDate || undefined,
        excludeDropouts,
        includeDuplicates,
      ),
    enabled: !!user && deviceA !== deviceB,
  });

  const points: AgreementTrendPoint[] = useMemo(
    () =>
      (trendQuery.data?.points ?? []).map((p) => ({
        activityId: p.activity_id,
        name: p.name,
        date: new Date(p.date),
        value: p.bias,
        mae: p.mae,
        rmse: p.rmse,
        pearsonR: p.pearson_r,
        withinTolerancePct: p.within_tolerance_pct,
        lagSeconds: p.lag_seconds,
      })),
    [trendQuery.data],
  );

  if (!user) {
    return null;
  }

  const unit = FIELD_UNITS[field];
  const formatBias = (v: number) => `${v.toFixed(1)}${unit}`;
  const caption = trendQuery.data ? skipCaption(trendQuery.data.skipped, field, deviceA, deviceB) : null;
  const tags = deviceTags(field, sport);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <div style={{ maxWidth: 1200 }}>
        <Link to="/preferences" style={{ fontSize: 13, color: "var(--ink3)" }}>
          &larr; Back to preferences
        </Link>
        <h1 style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.02em", margin: "8px 0 0" }}>
          {FIELD_LABELS[field]} device agreement
        </h1>
        <p style={{ fontSize: 13, color: "var(--ink2)", marginTop: 6, maxWidth: 720, lineHeight: 1.5 }}>
          One point per activity where both {deviceLabel(deviceA)} and {deviceLabel(deviceB)} were
          recorded. Bias is the mean of ({deviceLabel(deviceB)} &minus; {deviceLabel(deviceA)}) per
          overlapping second - a sudden jump usually means one of the two sources changed that day.
        </p>
      </div>

      <Card style={{ padding: "18px 24px" }}>
        <div style={{ display: "flex", gap: 16, flexWrap: "wrap" }}>
          <Control label="Device A">
            <select style={controlStyle} value={deviceA} onChange={(e) => handleDeviceAChange(e.target.value)}>
              {tags.map((tag) => (
                <option key={tag} value={tag}>{deviceLabel(tag)}</option>
              ))}
            </select>
          </Control>
          <Control label="Device B">
            <select style={controlStyle} value={deviceB} onChange={(e) => handleDeviceBChange(e.target.value)}>
              {tags.map((tag) => (
                <option key={tag} value={tag}>{deviceLabel(tag)}</option>
              ))}
            </select>
          </Control>
          <Control label={field === "power" ? "Sport" : "Sport (optional)"}>
            <select style={controlStyle} value={sport} onChange={(e) => handleSportChange(e.target.value)}>
              {field !== "power" && <option value="">All sports</option>}
              {(field === "power" ? SPORT_OPTIONS.filter((o) => o.value === "bike" || o.value === "run") : SPORT_OPTIONS).map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </Control>
          <Control label="From">
            <input type="date" className="mono" style={controlStyle} value={fromDate} max={toDate || undefined} onChange={(e) => setFromDate(e.target.value)} />
          </Control>
          <Control label="To">
            <input type="date" className="mono" style={controlStyle} value={toDate} min={fromDate || undefined} onChange={(e) => setToDate(e.target.value)} />
          </Control>
          {field === "power" && (
            <Control label="Dropouts">
              <label style={{ ...controlStyle, display: "flex", alignItems: "center", gap: 8, cursor: "pointer" }}>
                <input type="checkbox" checked={excludeDropouts} onChange={(e) => setExcludeDropouts(e.target.checked)} />
                Exclude confirmed dropouts
              </label>
            </Control>
          )}
          <Control label="Duplicates">
            <label style={{ ...controlStyle, display: "flex", alignItems: "center", gap: 8, cursor: "pointer" }}>
              <input type="checkbox" checked={includeDuplicates} onChange={(e) => setIncludeDuplicates(e.target.checked)} />
              Include same-ride-twice duplicates
            </label>
          </Control>
        </div>
      </Card>

      <Card style={{ padding: "20px 24px" }}>
        {trendQuery.isLoading ? (
          <div style={{ color: "var(--ink3)" }}>Loading…</div>
        ) : trendQuery.isError ? (
          <div style={{ color: "var(--ink3)", fontSize: 13 }}>Couldn't load the agreement trend - try a different range.</div>
        ) : (
          <>
            <DeviceAgreementTrendChart points={points} formatValue={formatBias} unit={unit} />
            {caption && (
              <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 10 }}>{caption}</div>
            )}
          </>
        )}
      </Card>
    </div>
  );
}

/** Issue #540 phase 5 - per-field device agreement trend, reached from Preferences' "Device
 * agreement" section. An invalid :field redirects to power (the same guard ThresholdHistoryScreen
 * applies for /thresholds/:field, living here rather than in App.tsx). */
export function DeviceAgreementTrendScreen() {
  const { field: rawField } = useParams<{ field: string }>();
  const field = VALID_FIELDS.find((f) => f === rawField);

  if (!field) {
    return <Navigate to="/devices/power/agreement-trend" replace />;
  }

  // key=field resets the pickers' state when navigating between fields - same route element, so
  // React would otherwise keep power's sport/device selections alive on the heartrate page.
  return <DeviceAgreementTrendContent key={field} field={field} />;
}
