import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { deleteAllActivities } from "../../api/activities";
import { updateAthlete } from "../../api/athletes";
import { useAuth } from "../../auth/AuthContext";
import type { AthleteUpdate } from "../../api/types";

const inputStyle: React.CSSProperties = {
  width: "100%",
  padding: "10px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 14,
  color: "var(--ink)",
};

function Field({
  label,
  unit,
  children,
}: {
  label: string;
  unit?: string;
  children: React.ReactNode;
}) {
  return (
    <label style={{ display: "block" }}>
      <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>
        {label}
        {unit && <span style={{ color: "var(--ink3)", fontWeight: 400 }}> ({unit})</span>}
      </div>
      {children}
    </label>
  );
}

function DeleteAllActivitiesDialog({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: deleteAllActivities,
    onSuccess: () => {
      queryClient.invalidateQueries();
      onClose();
    },
  });

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ background: "var(--card)", borderRadius: 14, padding: 24, width: 420, display: "flex", flexDirection: "column", gap: 16 }}
      >
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Remove all activities?</h2>
        <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0, lineHeight: 1.5 }}>
          This will permanently remove all existing activities from your account, including their
          laps, streams, and tags. This cannot be undone. Do you want to proceed?
        </p>
        {mutation.isError && (
          <div style={{ fontSize: 13, color: "#e0442e" }}>Something went wrong - no activities were removed. Please try again.</div>
        )}
        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8 }}>
          <button
            onClick={onClose}
            style={{ border: "1px solid var(--line)", borderRadius: 8, background: "transparent", color: "var(--ink2)", fontSize: 13, fontWeight: 600, padding: "8px 16px" }}
          >
            Cancel
          </button>
          <button
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
            style={{ border: "none", borderRadius: 8, background: "#e0442e", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px" }}
          >
            {mutation.isPending ? "Removing…" : "Remove all activities"}
          </button>
        </div>
      </div>
    </div>
  );
}

export function ProfileTab() {
  const { user, setUser } = useAuth();
  const [confirmingDeleteAll, setConfirmingDeleteAll] = useState(false);
  const [form, setForm] = useState<AthleteUpdate>({
    name: user?.name ?? "",
    age: user?.age ?? undefined,
    weight_kg: user?.weight_kg ?? undefined,
    ftp: user?.ftp ?? undefined,
    critical_run_power: user?.critical_run_power ?? undefined,
    threshold_pace: user?.threshold_pace ?? undefined,
    lthr: user?.lthr ?? undefined,
    max_hr: user?.max_hr ?? undefined,
    resting_hr: user?.resting_hr ?? undefined,
    threshold_warning_days: user?.threshold_warning_days ?? 21,
    ftp_calculation_method: user?.ftp_calculation_method ?? undefined,
    running_power_source: user?.running_power_source ?? undefined,
    decoupling_vi_limit_bike: user?.decoupling_vi_limit_bike ?? 1.06,
    decoupling_vi_limit_run: user?.decoupling_vi_limit_run ?? 1.04,
    decoupling_if_limit: user?.decoupling_if_limit ?? 0.85,
    decoupling_min_steady_minutes: user?.decoupling_min_steady_minutes ?? 60,
    decoupling_warmup_minutes: user?.decoupling_warmup_minutes ?? 5,
    decoupling_use_workout_warmup: user?.decoupling_use_workout_warmup ?? false,
    decoupling_warm_air_temp: user?.decoupling_warm_air_temp ?? 25.0,
    decoupling_warm_skin_temp: user?.decoupling_warm_skin_temp ?? 33.0,
    decoupling_hot_air_temp: user?.decoupling_hot_air_temp ?? 30.0,
    decoupling_hot_skin_temp: user?.decoupling_hot_skin_temp ?? 34.0,
    default_shoe_limit_km: user?.default_shoe_limit_km ?? 800,
  });

  const mutation = useMutation({
    mutationFn: () => updateAthlete(user!.id, form),
    onSuccess: (updated) => setUser(updated),
  });

  if (!user) {
    return null;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20, maxWidth: 460 }}>
      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Athlete</h3>
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <Field label="Name">
            <input style={inputStyle} value={form.name ?? ""} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
          <Field label="Age">
            <input
              type="number"
              className="mono"
              style={inputStyle}
              value={form.age ?? ""}
              onChange={(e) => setForm({ ...form, age: Number(e.target.value) })}
            />
          </Field>
          <Field label="Weight" unit="kg">
            <input
              type="number"
              step="0.1"
              className="mono"
              style={inputStyle}
              value={form.weight_kg ?? ""}
              onChange={(e) => setForm({ ...form, weight_kg: Number(e.target.value) })}
            />
          </Field>
        </div>
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Thresholds</h3>
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <Field label="FTP" unit="W">
            <input
              type="number"
              className="mono"
              style={inputStyle}
              value={form.ftp ?? ""}
              onChange={(e) => setForm({ ...form, ftp: Number(e.target.value) })}
            />
          </Field>
          <Field label="FTP calculation method">
            <select
              style={inputStyle}
              value={form.ftp_calculation_method ?? "twenty_min_test"}
              onChange={(e) =>
                setForm({ ...form, ftp_calculation_method: e.target.value as AthleteUpdate["ftp_calculation_method"] })
              }
            >
              <option value="twenty_min_test">20-minute test (best 20-min power × 0.95)</option>
              <option value="sixty_min_direct">60-minute direct (best 60-min power)</option>
            </select>
          </Field>
          <Field label="Critical run power" unit="W">
            <input
              type="number"
              className="mono"
              style={inputStyle}
              value={form.critical_run_power ?? ""}
              onChange={(e) => setForm({ ...form, critical_run_power: Number(e.target.value) })}
            />
          </Field>
          <Field label="Running power source">
            <select
              style={inputStyle}
              value={form.running_power_source ?? "stryd"}
              onChange={(e) =>
                setForm({ ...form, running_power_source: e.target.value as AthleteUpdate["running_power_source"] })
              }
            >
              <option value="stryd">Stryd</option>
              <option value="native">Native (e.g. Garmin Running Power)</option>
            </select>
          </Field>
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: "-6px 0 0" }}>
            Native running-power algorithms tend to read meaningfully higher than Stryd for the
            same effort - the source you don't pick is ignored entirely on future uploads, not
            used as a fallback. Doesn't retroactively change already-imported activities'
            stored data, but best efforts, TSS, and critical running power all re-check this
            preference whenever recomputed.
          </p>
          <Field label="Threshold pace" unit="MM:SS /km">
            <input
              className="mono"
              style={inputStyle}
              placeholder="4:00"
              value={form.threshold_pace ?? ""}
              onChange={(e) => setForm({ ...form, threshold_pace: e.target.value })}
            />
          </Field>
          <Field label="LTHR" unit="bpm">
            <input
              type="number"
              className="mono"
              style={inputStyle}
              value={form.lthr ?? ""}
              onChange={(e) => setForm({ ...form, lthr: Number(e.target.value) })}
            />
          </Field>
          <Field label="Max heart rate" unit="bpm">
            <input
              type="number"
              className="mono"
              style={inputStyle}
              value={form.max_hr ?? ""}
              onChange={(e) => setForm({ ...form, max_hr: Number(e.target.value) })}
            />
          </Field>
          <Field label="Resting heart rate" unit="bpm">
            <input
              type="number"
              className="mono"
              style={inputStyle}
              value={form.resting_hr ?? ""}
              onChange={(e) => setForm({ ...form, resting_hr: Number(e.target.value) })}
            />
          </Field>
          <Field label="Expiry warning" unit="days before a threshold's best effort ages out">
            <select
              style={inputStyle}
              value={String(form.threshold_warning_days ?? 21)}
              onChange={(e) => setForm({ ...form, threshold_warning_days: Number(e.target.value) })}
            >
              <option value="14">14 days</option>
              <option value="21">21 days (recommended)</option>
              <option value="28">28 days</option>
              <option value="0">Off</option>
            </select>
          </Field>
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: "-6px 0 0", lineHeight: 1.5 }}>
            Expiring thresholds appear under Suggestions on Thresholds &amp; zones from this many
            days out, and on the Dashboard from 10 days. Small drops (under 2%), thresholds
            you've nearly matched in the last 14 days, and ones a booked race should refresh are
            skipped. With a trailing window shorter than 12 weeks, a quarter of the window is
            used instead.
          </p>
        </div>
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Aerobic decoupling</h3>
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <Field label="VI limit - bike" unit="variability index">
            <input
              type="number"
              step="0.01"
              className="mono"
              style={inputStyle}
              value={form.decoupling_vi_limit_bike ?? ""}
              onChange={(e) => setForm({ ...form, decoupling_vi_limit_bike: Number(e.target.value) })}
            />
          </Field>
          <Field label="VI limit - run" unit="variability index">
            <input
              type="number"
              step="0.01"
              className="mono"
              style={inputStyle}
              value={form.decoupling_vi_limit_run ?? ""}
              onChange={(e) => setForm({ ...form, decoupling_vi_limit_run: Number(e.target.value) })}
            />
          </Field>
          <Field label="IF limit" unit="intensity factor">
            <input
              type="number"
              step="0.01"
              className="mono"
              style={inputStyle}
              value={form.decoupling_if_limit ?? ""}
              onChange={(e) => setForm({ ...form, decoupling_if_limit: Number(e.target.value) })}
            />
          </Field>
          <Field label="Minimum steady time" unit="minutes">
            <input
              type="number"
              className="mono"
              style={inputStyle}
              value={form.decoupling_min_steady_minutes ?? ""}
              onChange={(e) => setForm({ ...form, decoupling_min_steady_minutes: Number(e.target.value) })}
            />
          </Field>
          <Field label="Warm-up trim" unit="minutes">
            <input
              type="number"
              className="mono"
              style={inputStyle}
              value={form.decoupling_warmup_minutes ?? ""}
              onChange={(e) => setForm({ ...form, decoupling_warmup_minutes: Number(e.target.value) })}
            />
          </Field>
          <label style={{ display: "flex", alignItems: "flex-start", gap: 9, fontSize: 13, cursor: "pointer" }}>
            <input
              type="checkbox"
              checked={form.decoupling_use_workout_warmup ?? false}
              onChange={(e) => setForm({ ...form, decoupling_use_workout_warmup: e.target.checked })}
              style={{ marginTop: 2 }}
            />
            <span>
              When an activity is matched to a designed workout with its own warmup step, trim
              that step's exact duration instead of the flat warm-up trim above
            </span>
          </label>
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: "-6px 0 0", lineHeight: 1.5 }}>
            Gates whether a session's Pw:HR decoupling % gets computed at all - too variable, too
            intense, or too short a steady effort and it's skipped instead of scored. Read at
            compute time, not retroactive: changing these doesn't repaint already-scored
            activities on its own.
          </p>
        </div>
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Heat-confound flags</h3>
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <Field label="Warm - air temp" unit="°C">
            <input
              type="number"
              step="0.5"
              className="mono"
              style={inputStyle}
              value={form.decoupling_warm_air_temp ?? ""}
              onChange={(e) => setForm({ ...form, decoupling_warm_air_temp: Number(e.target.value) })}
            />
          </Field>
          <Field label="Warm - skin temp" unit="°C">
            <input
              type="number"
              step="0.5"
              className="mono"
              style={inputStyle}
              value={form.decoupling_warm_skin_temp ?? ""}
              onChange={(e) => setForm({ ...form, decoupling_warm_skin_temp: Number(e.target.value) })}
            />
          </Field>
          <Field label="Hot - air temp" unit="°C">
            <input
              type="number"
              step="0.5"
              className="mono"
              style={inputStyle}
              value={form.decoupling_hot_air_temp ?? ""}
              onChange={(e) => setForm({ ...form, decoupling_hot_air_temp: Number(e.target.value) })}
            />
          </Field>
          <Field label="Hot - skin temp" unit="°C">
            <input
              type="number"
              step="0.5"
              className="mono"
              style={inputStyle}
              value={form.decoupling_hot_skin_temp ?? ""}
              onChange={(e) => setForm({ ...form, decoupling_hot_skin_temp: Number(e.target.value) })}
            />
          </Field>
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: "-6px 0 0", lineHeight: 1.5 }}>
            Flags a decoupling reading as heat-confounded, not excluded from scoring. Both warm
            and hot trigger from air OR skin temp alone - either one elevated is enough. Core
            temp isn't used here: a long steady session drives it up from sustained effort
            alone, even on a cool day.
          </p>
        </div>
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Gear</h3>
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <Field label="Default shoe wear limit" unit="km">
            <input
              type="number"
              className="mono"
              style={inputStyle}
              value={form.default_shoe_limit_km ?? ""}
              onChange={(e) => setForm({ ...form, default_shoe_limit_km: Number(e.target.value) })}
            />
          </Field>
          <p style={{ fontSize: 12, color: "var(--ink3)", margin: "-6px 0 0", lineHeight: 1.5 }}>
            Applied to a new shoe's wear limit whenever one isn't set explicitly - both the "Add
            shoes" form and a gear CSV import. Doesn't change any shoe you've already added.
          </p>
        </div>
      </div>

      <div>
        <button
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending}
          style={{ padding: "8px 16px", borderRadius: 8, border: "none", background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700 }}
        >
          {mutation.isPending ? "Saving…" : "Save changes"}
        </button>
        {mutation.isSuccess && (mutation.data.zones_recomputed.length > 0) && (
          <span style={{ marginLeft: 10, fontSize: 13, color: "#2fa66a" }}>
            Saved - recomputed {mutation.data.zones_recomputed.join(", ")} zones.
          </span>
        )}
        {mutation.isSuccess && mutation.data.zones_recomputed.length === 0 && (
          <span style={{ marginLeft: 10, fontSize: 13, color: "#2fa66a" }}>Saved.</span>
        )}
      </div>

      <div style={{ borderTop: "1px solid var(--line)", paddingTop: 20 }}>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 6px", color: "#e0442e" }}>Danger zone</h3>
        <p style={{ fontSize: 13, color: "var(--ink2)", margin: "0 0 12px" }}>
          Remove every activity from your account. Uploaded files can be imported again afterwards.
        </p>
        <button
          onClick={() => setConfirmingDeleteAll(true)}
          style={{ padding: "8px 16px", borderRadius: 8, border: "1px solid #e0442e", background: "transparent", color: "#e0442e", fontSize: 13, fontWeight: 700 }}
        >
          Remove all activities…
        </button>
      </div>

      {confirmingDeleteAll && <DeleteAllActivitiesDialog onClose={() => setConfirmingDeleteAll(false)} />}
    </div>
  );
}
