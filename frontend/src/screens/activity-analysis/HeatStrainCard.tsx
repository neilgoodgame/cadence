import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { getStreams } from "../../api/activities";

/**
 * CORE's own published Heat Strain Index is a 0-10 scale (from body-temperature-derived
 * physiological strain, not just ambient conditions); these tri-color bands approximate
 * CORE's own app - No Strain <2.0, Moderate 2.0-3.9, High 4.0+. Adjust here if CORE
 * publishes different exact cutoffs than this approximation.
 */
const BANDS = [
  { name: "No Strain", max: 2, color: "#34c98a" },
  { name: "Moderate", max: 4, color: "#f5c842" },
  { name: "High", max: Infinity, color: "#f0823a" },
];

function bandFor(value: number): (typeof BANDS)[number] {
  return BANDS.find((b) => value < b.max) ?? BANDS[BANDS.length - 1];
}

export function HeatStrainCard({ activityId }: { activityId: string }) {
  const { data } = useQuery({
    queryKey: ["activity-streams-heat-strain", activityId],
    queryFn: () => getStreams(activityId, ["time", "heat_strain"], "medium"),
  });

  const stats = useMemo(() => {
    if (!data) return null;
    const time = data.fields.time ?? [];
    const heatStrain = data.fields.heat_strain ?? [];
    const samples = time
      .map((t, i) => ({ t, v: heatStrain[i] }))
      .filter((s): s is { t: number; v: number } => s.t != null && s.v != null);
    if (samples.length === 0) return null;

    // Seconds-in-band, weighted by the actual gap to the next sample (robust to whatever
    // resolution/step the stream came back at, rather than assuming a fixed cadence).
    const secondsInBand = new Map(BANDS.map((b) => [b.name, 0]));
    let totalSeconds = 0;
    for (let i = 0; i < samples.length; i++) {
      const dt = i + 1 < samples.length ? samples[i + 1].t - samples[i].t : samples[i].t - (samples[i - 1]?.t ?? samples[i].t);
      const band = bandFor(samples[i].v);
      secondsInBand.set(band.name, (secondsInBand.get(band.name) ?? 0) + dt);
      totalSeconds += dt;
    }

    // Peak 60s: best rolling-average window over the actual sample values (window sized in
    // samples proportional to however many samples span ~60s at this stream's resolution).
    const avgStepSeconds = totalSeconds / samples.length;
    const windowSize = Math.max(1, Math.round(60 / avgStepSeconds));
    let peak60s = 0;
    if (samples.length >= windowSize) {
      let windowSum = samples.slice(0, windowSize).reduce((a, s) => a + s.v, 0);
      peak60s = windowSum / windowSize;
      for (let i = windowSize; i < samples.length; i++) {
        windowSum += samples[i].v - samples[i - windowSize].v;
        peak60s = Math.max(peak60s, windowSum / windowSize);
      }
    }

    // HSI·min: the time-integral of heat strain, in strain-units * minutes - a TRIMP-like
    // cumulative heat-load number, not just a peak snapshot.
    let hsiMinutes = 0;
    for (let i = 0; i < samples.length; i++) {
      const dt = i + 1 < samples.length ? samples[i + 1].t - samples[i].t : 0;
      hsiMinutes += (samples[i].v * dt) / 60;
    }

    return {
      peak60s,
      hsiMinutes,
      bands: BANDS.map((b) => ({
        ...b,
        pct: totalSeconds > 0 ? ((secondsInBand.get(b.name) ?? 0) / totalSeconds) * 100 : 0,
      })),
    };
  }, [data]);

  if (!stats) {
    return null;
  }

  return (
    <div
      style={{
        gridColumn: "span 2",
        background: "var(--card)",
        border: "1px solid var(--line)",
        borderRadius: 14,
        padding: "20px 22px",
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 18 }}>
        <div className="mono" style={{ fontSize: 11, letterSpacing: "0.08em", color: "var(--ink3)" }}>
          HEAT STRAIN INDEX
        </div>
        <div className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>
          Peak 60s {stats.peak60s.toFixed(1)} · {Math.round(stats.hsiMinutes)} HSI·min
        </div>
      </div>
      <div style={{ display: "flex", height: 30, borderRadius: 7, overflow: "hidden", marginBottom: 14 }}>
        {stats.bands.map((b) => (
          <div key={b.name} style={{ width: `${b.pct}%`, background: b.color }} />
        ))}
      </div>
      <div style={{ display: "flex", gap: 28, flexWrap: "wrap" }}>
        {stats.bands.map((b) => (
          <div key={b.name} style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13 }}>
            <span style={{ width: 9, height: 9, borderRadius: 3, background: b.color }} />
            <span style={{ color: "var(--ink2)" }}>{b.name}</span>
            <span className="mono" style={{ color: "var(--ink)" }}>
              {Math.round(b.pct)}%
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
