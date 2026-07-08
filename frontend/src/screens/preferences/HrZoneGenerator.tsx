import { useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../auth/AuthContext";
import { updateAthlete, replaceZoneSet } from "../../api/athletes";
import {
  HR_ZONE_METHODS,
  generateHrZones,
  hrZoneBandsToZones,
  validateHrZoneParams,
  type HrZoneMethod,
} from "../../lib/hrZones";

const inputStyle: React.CSSProperties = {
  width: 72,
  border: "1px solid var(--line)",
  borderRadius: 6,
  padding: "6px 8px",
  background: "var(--elev)",
  color: "var(--ink)",
  fontSize: 13,
};

function parseBpm(value: string): number | null {
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n : null;
}

export function HrZoneGenerator({ athleteId }: { athleteId: string }) {
  const { user, setUser } = useAuth();
  const queryClient = useQueryClient();

  const [method, setMethod] = useState<HrZoneMethod>("coggan");
  const [restingHrInput, setRestingHrInput] = useState("");
  const [lthrInput, setLthrInput] = useState(user?.lthr ? String(user.lthr) : "");
  const [maxHrInput, setMaxHrInput] = useState(user?.max_hr ? String(user.max_hr) : "");

  const params = useMemo(
    () => ({
      restingHr: parseBpm(restingHrInput),
      lthr: parseBpm(lthrInput) ?? 0,
      maxHr: parseBpm(maxHrInput) ?? 0,
    }),
    [restingHrInput, lthrInput, maxHrInput],
  );

  const validationError = validateHrZoneParams(method, params);
  const bands = validationError ? null : generateHrZones(method, params);

  const mutation = useMutation({
    mutationFn: async () => {
      // The backend stores HR zones as % of LTHR, so the profile's LTHR (the reference)
      // has to be updated first for the percentages to resolve to the previewed bpm.
      const athlete = await updateAthlete(athleteId, { lthr: params.lthr, max_hr: params.maxHr });
      await replaceZoneSet(athleteId, "heart_rate", hrZoneBandsToZones(bands!, params.lthr));
      return athlete;
    },
    onSuccess: (athlete) => {
      setUser(athlete);
      queryClient.invalidateQueries({ queryKey: ["zones", athleteId] });
    },
  });

  return (
    <div style={{ border: "1px solid var(--line)", borderRadius: 12, padding: 18, marginBottom: 24 }}>
      <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 4px" }}>Recompute zones</h3>
      <p style={{ fontSize: 12, color: "var(--ink3)", margin: "0 0 14px" }}>
        Generate all five zones from your physiology. Applying updates your profile's LTHR and max HR too.
      </p>

      <div style={{ display: "flex", gap: 4, marginBottom: 12 }}>
        {(Object.keys(HR_ZONE_METHODS) as HrZoneMethod[]).map((key) => (
          <button
            key={key}
            onClick={() => setMethod(key)}
            style={{
              border: "1px solid var(--line)",
              borderRadius: 8,
              padding: "6px 12px",
              fontSize: 13,
              fontWeight: 600,
              background: method === key ? "var(--elev)" : "transparent",
              color: method === key ? "var(--ink)" : "var(--ink3)",
            }}
          >
            {HR_ZONE_METHODS[key].label}
          </button>
        ))}
      </div>
      <p style={{ fontSize: 12, color: "var(--ink3)", margin: "0 0 14px" }}>{HR_ZONE_METHODS[method].blurb}</p>

      <div style={{ display: "flex", gap: 18, alignItems: "flex-end", marginBottom: 14 }}>
        <label>
          <div style={{ fontSize: 11, fontWeight: 600, color: "var(--ink2)", marginBottom: 4 }}>
            RESTING HR{method !== "karvonen" && <span style={{ color: "var(--ink3)", fontWeight: 400 }}> (optional)</span>}
          </div>
          <input
            type="number"
            className="mono"
            style={inputStyle}
            value={restingHrInput}
            onChange={(e) => setRestingHrInput(e.target.value)}
            placeholder="45"
          />
        </label>
        <label>
          <div style={{ fontSize: 11, fontWeight: 600, color: "var(--ink2)", marginBottom: 4 }}>LTHR</div>
          <input
            type="number"
            className="mono"
            style={inputStyle}
            value={lthrInput}
            onChange={(e) => setLthrInput(e.target.value)}
            placeholder="155"
          />
        </label>
        <label>
          <div style={{ fontSize: 11, fontWeight: 600, color: "var(--ink2)", marginBottom: 4 }}>MAX HR</div>
          <input
            type="number"
            className="mono"
            style={inputStyle}
            value={maxHrInput}
            onChange={(e) => setMaxHrInput(e.target.value)}
            placeholder="176"
          />
        </label>
      </div>

      {validationError && (lthrInput || maxHrInput || restingHrInput) && (
        <div style={{ fontSize: 13, color: "var(--ember)", marginBottom: 12 }}>{validationError}</div>
      )}

      {bands && (
        <>
          <table style={{ width: "100%", fontSize: 13, borderCollapse: "collapse", marginBottom: 14 }}>
            <thead>
              <tr style={{ textAlign: "left", color: "var(--ink3)", fontSize: 11 }}>
                <th style={{ paddingBottom: 6 }}>Zone</th>
                <th style={{ paddingBottom: 6 }}>Range</th>
                <th style={{ paddingBottom: 6 }}>Description</th>
              </tr>
            </thead>
            <tbody>
              {bands.map((band) => (
                <tr key={band.name} style={{ borderTop: "1px solid var(--line)" }}>
                  <td style={{ padding: "7px 12px 7px 0", whiteSpace: "nowrap", fontWeight: 600 }}>
                    {band.name} {band.label}
                  </td>
                  <td className="mono" style={{ padding: "7px 12px 7px 0", whiteSpace: "nowrap", color: "var(--ink2)" }}>
                    {band.minBpm}–{band.maxBpm} bpm
                  </td>
                  <td style={{ padding: "7px 0", color: "var(--ink3)", fontSize: 12 }}>{band.description}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <button
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
            style={{
              padding: "8px 16px",
              borderRadius: 8,
              border: "none",
              background: "var(--ember)",
              color: "#fff",
              fontSize: 13,
              fontWeight: 700,
            }}
          >
            {mutation.isPending ? "Applying…" : "Apply zones"}
          </button>
          {mutation.isSuccess && <span style={{ marginLeft: 10, fontSize: 13, color: "#2fa66a" }}>Zones updated.</span>}
          {mutation.isError && (
            <span style={{ marginLeft: 10, fontSize: 13, color: "var(--ember)" }}>Something went wrong. Try again.</span>
          )}
        </>
      )}
    </div>
  );
}
