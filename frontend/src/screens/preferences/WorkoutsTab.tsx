import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { updateAthlete } from "../../api/athletes";
import { useAuth } from "../../auth/AuthContext";
import type { AthleteUpdate } from "../../api/types";

export function WorkoutsTab() {
  const { user, setUser } = useAuth();
  const [form, setForm] = useState<AthleteUpdate>({
    rename_matched_activities: user?.rename_matched_activities ?? false,
    append_match_date_to_name: user?.append_match_date_to_name ?? false,
    copy_matched_workout_tags: user?.copy_matched_workout_tags ?? false,
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
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Workout matching</h3>
        <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <label style={{ display: "flex", alignItems: "flex-start", gap: 9, fontSize: 13, cursor: "pointer" }}>
            <input
              type="checkbox"
              checked={form.rename_matched_activities ?? false}
              onChange={(e) => {
                const checked = e.target.checked;
                setForm((f) => ({
                  ...f,
                  rename_matched_activities: checked,
                  append_match_date_to_name: checked ? f.append_match_date_to_name : false,
                }));
              }}
              style={{ marginTop: 2 }}
            />
            <span>
              Rename auto-matched activities to their workout's name
              <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>
                When an upload matches a scheduled workout, use the workout's name instead of the default.
              </div>
            </span>
          </label>
          <label
            style={{
              display: "flex",
              alignItems: "flex-start",
              gap: 9,
              fontSize: 13,
              marginLeft: 24,
              cursor: form.rename_matched_activities ? "pointer" : "default",
              opacity: form.rename_matched_activities ? 1 : 0.5,
            }}
          >
            <input
              type="checkbox"
              checked={form.append_match_date_to_name ?? false}
              disabled={!form.rename_matched_activities}
              onChange={(e) => setForm({ ...form, append_match_date_to_name: e.target.checked })}
              style={{ marginTop: 2 }}
            />
            <span>
              Also append the date
              <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>
                e.g. "Tempo run - 2026-06-15" instead of just "Tempo run".
              </div>
            </span>
          </label>
          <label style={{ display: "flex", alignItems: "flex-start", gap: 9, fontSize: 13, cursor: "pointer" }}>
            <input
              type="checkbox"
              checked={form.copy_matched_workout_tags ?? false}
              onChange={(e) => setForm({ ...form, copy_matched_workout_tags: e.target.checked })}
              style={{ marginTop: 2 }}
            />
            <span>
              Copy the workout's tags to auto-matched activities
              <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>
                When an upload matches a scheduled workout, also apply the workout's tags to the activity.
              </div>
            </span>
          </label>
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
        {mutation.isSuccess && (
          <span style={{ marginLeft: 10, fontSize: 13, color: "#2fa66a" }}>Saved.</span>
        )}
      </div>
    </div>
  );
}
