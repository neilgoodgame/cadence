import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../auth/AuthContext";
import { deleteGarminConnection, getGarminConnection, saveGarminConnection } from "../../api/garmin";
import { GarminWorkoutQueue } from "../garmin/GarminWorkoutQueue";

const EXPIRY_WARNING_DAYS = 14;
const DEFAULT_SYNC_LIMIT = 50;
const MAX_SYNC_LIMIT = 100;

const inputStyle = {
  padding: "8px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  color: "var(--ink)",
} as const;

function daysUntil(date: string): number {
  const today = new Date(new Date().toISOString().slice(0, 10)).getTime();
  return Math.round((new Date(date).getTime() - today) / 86_400_000);
}

export function GarminTab() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const { data: connection } = useQuery({ queryKey: ["garmin-connection"], queryFn: getGarminConnection });

  // undefined = untouched, so the field shows the saved/default value until the user edits it.
  const [apiKey, setApiKey] = useState("");
  const [emailDraft, setEmailDraft] = useState<string | undefined>(undefined);
  const [expiryDraft, setExpiryDraft] = useState<string | undefined>(undefined);
  const [limitDraft, setLimitDraft] = useState<string | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  const connected = connection?.connected ?? false;
  const email = emailDraft ?? connection?.email ?? user?.email ?? "";
  const expiry = expiryDraft ?? connection?.key_expires_on ?? "";
  const syncLimit = limitDraft ?? String(connection?.sync_limit ?? DEFAULT_SYNC_LIMIT);
  const limitValue = Number(syncLimit);
  const limitValid = Number.isInteger(limitValue) && limitValue >= 1 && limitValue <= MAX_SYNC_LIMIT;

  const saveMutation = useMutation({
    mutationFn: () =>
      saveGarminConnection({
        ...(apiKey.trim() ? { api_key: apiKey.trim() } : {}),
        email: email.trim(),
        key_expires_on: expiry || null,
        sync_limit: limitValue,
      }),
    onSuccess: () => {
      setApiKey("");
      setEmailDraft(undefined);
      setExpiryDraft(undefined);
      setLimitDraft(undefined);
      setError(null);
      queryClient.invalidateQueries({ queryKey: ["garmin-connection"] });
    },
    onError: (err) => setError(err instanceof Error ? err.message : "Could not save the Garmin connection."),
  });
  const removeMutation = useMutation({
    mutationFn: deleteGarminConnection,
    onSuccess: () => {
      setApiKey("");
      setEmailDraft(undefined);
      setExpiryDraft(undefined);
      setLimitDraft(undefined);
      queryClient.invalidateQueries({ queryKey: ["garmin-connection"] });
    },
  });

  const canSave = (connected || !!apiKey.trim()) && !!email.trim() && limitValid && !saveMutation.isPending;
  const expiryDays = connection?.key_expires_on ? daysUntil(connection.key_expires_on) : null;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 24, maxWidth: 520 }}>
      <p style={{ fontSize: 13, color: "var(--ink3)", margin: 0 }}>
        Link your Garmin Connect account with a Personal API key so activities can be pulled in on request. Create a key at{" "}
        <a href="https://connect.garmin.com/app/settings/personalApiToken" target="_blank" rel="noreferrer noopener">
          connect.garmin.com
        </a>{" "}
        — Garmin shows it only once. It is stored encrypted and never shown again. See{" "}
        <a href="https://developer.garmin.com/gc-developer-program/personal/personal-api/" target="_blank" rel="noreferrer noopener">
          Garmin's instructions
        </a>{" "}
        for how to set up a Personal API key.
      </p>

      {connected && (
        <div style={{ border: "1px solid var(--line)", borderRadius: 10, padding: 14, background: "var(--elev)", fontSize: 13 }}>
          <div style={{ fontWeight: 700, color: "#2fa66a" }}>Connected as {connection?.email}</div>
          <div style={{ color: "var(--ink3)", marginTop: 4 }}>
            {connection?.last_run_at ? `Last synced ${new Date(connection.last_run_at).toLocaleString()}` : "Not synced yet"}
            {connection?.last_activity && ` · latest activity ${new Date(connection.last_activity.start_time).toLocaleString()}`}
          </div>
          {expiryDays !== null && (
            <div style={{ marginTop: 4, color: expiryDays <= EXPIRY_WARNING_DAYS ? "#e0442e" : "var(--ink3)" }}>
              {expiryDays < 0
                ? "This key has expired — create a new one in Garmin Connect and replace it below."
                : `Key expires in ${expiryDays} day${expiryDays === 1 ? "" : "s"}${expiryDays <= EXPIRY_WARNING_DAYS ? " — replace it soon" : ""}.`}
            </div>
          )}
        </div>
      )}

      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13, fontWeight: 600 }}>
          {connected ? "Replace API key (leave blank to keep the current one)" : "Personal API key"}
          <input
            type="password"
            autoComplete="off"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder={connected ? "••••••••••••" : "Paste your key"}
            style={inputStyle}
          />
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13, fontWeight: 600 }}>
          Garmin account email
          <input type="email" value={email} onChange={(e) => setEmailDraft(e.target.value)} style={inputStyle} />
          <span style={{ fontWeight: 400, color: "var(--ink3)" }}>Defaults to your Cadence email; change it if your Garmin account uses a different one.</span>
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13, fontWeight: 600 }}>
          Key expiry date (optional)
          <input type="date" value={expiry} onChange={(e) => setExpiryDraft(e.target.value)} style={inputStyle} />
          <span style={{ fontWeight: 400, color: "var(--ink3)" }}>Garmin keys last 30, 90, 180 or 365 days. We'll warn you before it lapses.</span>
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13, fontWeight: 600 }}>
          Activities per sync
          <input
            type="number"
            min={1}
            max={MAX_SYNC_LIMIT}
            step={1}
            value={syncLimit}
            onChange={(e) => setLimitDraft(e.target.value)}
            style={{ ...inputStyle, width: 120 }}
          />
          <span style={{ fontWeight: 400, color: limitValid ? "var(--ink3)" : "#e0442e" }}>
            {limitValid
              ? `The most activities one sync downloads, oldest first (default ${DEFAULT_SYNC_LIMIT}, maximum ${MAX_SYNC_LIMIT}). Sync again to continue a longer backlog.`
              : `Enter a whole number from 1 to ${MAX_SYNC_LIMIT}.`}
          </span>
        </label>
        {error && <div style={{ fontSize: 13, color: "#e0442e" }}>{error}</div>}
        <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
          <button
            onClick={() => saveMutation.mutate()}
            disabled={!canSave}
            style={{
              padding: "8px 16px",
              borderRadius: 8,
              border: "none",
              background: "var(--ember)",
              color: "#fff",
              fontSize: 13,
              fontWeight: 700,
              opacity: canSave ? 1 : 0.4,
              cursor: canSave ? "pointer" : "not-allowed",
            }}
          >
            {saveMutation.isPending ? "Saving…" : connected ? "Save changes" : "Connect"}
          </button>
          {connected && (
            <button
              onClick={() => removeMutation.mutate()}
              disabled={removeMutation.isPending}
              style={{ fontSize: 13, fontWeight: 600, border: "none", background: "none", color: "#e0442e" }}
            >
              Remove connection
            </button>
          )}
        </div>
      </div>

      {connected && <GarminWorkoutQueue />}
    </div>
  );
}
