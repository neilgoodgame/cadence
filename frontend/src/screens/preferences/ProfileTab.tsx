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
