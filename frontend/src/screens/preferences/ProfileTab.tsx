import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
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

export function ProfileTab() {
  const { user, setUser } = useAuth();
  const [form, setForm] = useState<AthleteUpdate>({
    name: user?.name ?? "",
    age: user?.age ?? undefined,
    ftp: user?.ftp ?? undefined,
    critical_run_power: user?.critical_run_power ?? undefined,
    threshold_pace: user?.threshold_pace ?? undefined,
    lthr: user?.lthr ?? undefined,
    max_hr: user?.max_hr ?? undefined,
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
          <Field label="Critical run power" unit="W">
            <input
              type="number"
              className="mono"
              style={inputStyle}
              value={form.critical_run_power ?? ""}
              onChange={(e) => setForm({ ...form, critical_run_power: Number(e.target.value) })}
            />
          </Field>
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
    </div>
  );
}
