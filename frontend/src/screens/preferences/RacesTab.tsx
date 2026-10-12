import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { updateAthlete } from "../../api/athletes";
import { useAuth } from "../../auth/AuthContext";
import type { AthleteUpdate } from "../../api/types";

const BLOCK_WEEKS = [12, 16, 20, 24];
const KEY_SESSION_CAPS = [6, 8, 10, 12, 15, 20];

/** Race CRUD now lives on the race detail page (`/races/:id`) - this tab is just a pointer
 * there, plus the two block-level settings (README §4 "Preferences › Races"). */
export function RacesTab() {
  const { user, setUser } = useAuth();
  const [form, setForm] = useState<AthleteUpdate>({
    race_block_weeks: user?.race_block_weeks ?? 16,
    race_key_session_cap: user?.race_key_session_cap ?? 10,
  });

  const mutation = useMutation({
    mutationFn: () => updateAthlete(user!.id, form),
    onSuccess: (updated) => setUser(updated),
  });

  if (!user) {
    return null;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 24, maxWidth: 460 }}>
      <Link
        to="/races"
        style={{
          display: "block",
          padding: "14px 18px",
          borderRadius: 10,
          border: "1px solid var(--line)",
          background: "var(--card)",
          color: "var(--ink)",
          fontSize: 14,
          fontWeight: 600,
          textDecoration: "none",
        }}
      >
        Go to Races →
        <div style={{ fontSize: 12, color: "var(--ink3)", fontWeight: 400, marginTop: 2 }}>
          Add, edit and delete races, link courses, and set goals from the race page itself.
        </div>
      </Link>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 4px" }}>Training block length</h3>
        <div style={{ fontSize: 12, color: "var(--ink3)", marginBottom: 10 }}>
          How many weeks an A race's training block covers. B and C races always use a fixed 5-week window around the
          race itself.
        </div>
        <div style={{ display: "flex", gap: 6 }}>
          {BLOCK_WEEKS.map((n) => (
            <button
              key={n}
              type="button"
              onClick={() => setForm({ ...form, race_block_weeks: n })}
              style={{
                flex: 1,
                padding: "7px 0",
                borderRadius: 8,
                border: `1px solid ${form.race_block_weeks === n ? "var(--ember)" : "var(--line)"}`,
                background: form.race_block_weeks === n ? "var(--ember)22" : "transparent",
                color: form.race_block_weeks === n ? "var(--ember)" : "var(--ink2)",
                fontSize: 13,
                fontWeight: 700,
                cursor: "pointer",
              }}
            >
              {n}
            </button>
          ))}
        </div>
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 4px" }}>Key sessions shown per race</h3>
        <div style={{ fontSize: 12, color: "var(--ink3)", marginBottom: 10 }}>
          How many of the ranked key sessions to show on a race's training-block card.
        </div>
        <select
          value={form.race_key_session_cap}
          onChange={(e) => setForm({ ...form, race_key_session_cap: Number(e.target.value) })}
          style={{ padding: "6px 8px", borderRadius: 6, border: "1px solid var(--line)", fontSize: 13, maxWidth: 160 }}
        >
          {KEY_SESSION_CAPS.map((n) => (
            <option key={n} value={n}>{n}</option>
          ))}
        </select>
      </div>

      <div>
        <button
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending}
          style={{ padding: "8px 16px", borderRadius: 8, border: "none", background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700 }}
        >
          {mutation.isPending ? "Saving…" : "Save changes"}
        </button>
        {mutation.isSuccess && <span style={{ marginLeft: 10, fontSize: 13, color: "#2fa66a" }}>Saved.</span>}
      </div>
    </div>
  );
}
