import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { publicRaceUrl } from "../../api/public";
import { deleteRace, updateRace } from "../../api/races";
import type { Race, RacePriority } from "../../api/types";
import { ShareButtons } from "../../components/ShareButtons";
import { daysUntil } from "../../lib/dates";

const PRIORITY_STYLE: Record<RacePriority, React.CSSProperties> = {
  A: { border: "1px solid var(--ember)", color: "var(--ember)" },
  B: { border: "1px solid var(--ink2)", color: "var(--ink2)" },
  C: { border: "1px dashed var(--ink3)", color: "var(--ink3)" },
};

function statusLabel(race: Race): string {
  if (race.result_time) return "Completed";
  const days = daysUntil(race.date);
  if (days < 0) return "No result";
  if (days === 0) return "Today";
  return `In ${days} day${days === 1 ? "" : "s"}`;
}

/** Race detail page header - README §4.2.1. */
export function Header({
  race,
  onEdit,
  onDownloadReport,
  downloadingReport,
}: {
  race: Race;
  onEdit: () => void;
  onDownloadReport: () => void;
  downloadingReport: boolean;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);

  const visibilityMutation = useMutation({
    mutationFn: (isPublic: boolean) => updateRace(race.id, { is_public: isPublic }),
    onSuccess: (updated) => queryClient.setQueryData(["race", race.id], updated),
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteRace(race.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["races"] });
      navigate("/races");
    },
  });

  return (
    <div>
      <Link to="/races" style={{ fontSize: 13, color: "var(--ink3)", textDecoration: "none" }}>
        ← All races
      </Link>
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 8, flexWrap: "wrap" }}>
        <h1 style={{ fontSize: 24, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>{race.name}</h1>
        <span
          style={{
            fontSize: 11.5,
            fontWeight: 700,
            padding: "2px 10px",
            borderRadius: 20,
            color: race.result_time ? "#2fa66a" : "var(--ink2)",
            border: `1px solid ${race.result_time ? "#2fa66a" : "var(--line)"}`,
          }}
        >
          {statusLabel(race)}
        </span>
        <span
          className="mono"
          style={{ fontSize: 9.5, fontWeight: 700, padding: "1px 6px", borderRadius: 5, ...PRIORITY_STYLE[race.priority] }}
        >
          {race.priority}
        </span>
        <div style={{ marginLeft: "auto", display: "flex", gap: 8 }}>
          <ShareButtons
            noun="race"
            isPublic={race.is_public}
            publicUrl={publicRaceUrl(race.id)}
            setPublic={visibilityMutation.mutateAsync}
            pending={visibilityMutation.isPending}
            failed={visibilityMutation.isError}
          />
          <button
            onClick={onDownloadReport}
            disabled={downloadingReport}
            style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, fontWeight: 600, padding: "6px 14px", cursor: "pointer", color: "var(--ink)", opacity: downloadingReport ? 0.6 : 1 }}
          >
            {downloadingReport ? "Preparing…" : "Download report"}
          </button>
          <button
            onClick={onEdit}
            style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, fontWeight: 600, padding: "6px 14px", cursor: "pointer", color: "var(--ink)" }}
          >
            Edit
          </button>
          {confirming ? (
            <>
              <button
                onClick={() => deleteMutation.mutate()}
                disabled={deleteMutation.isPending}
                style={{ border: "none", background: "#e0442e", color: "#fff", borderRadius: 8, fontSize: 13, fontWeight: 600, padding: "6px 14px", cursor: "pointer" }}
              >
                {deleteMutation.isPending ? "Deleting…" : "Confirm delete"}
              </button>
              <button
                onClick={() => setConfirming(false)}
                style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, fontWeight: 600, padding: "6px 14px", cursor: "pointer", color: "var(--ink)" }}
              >
                Cancel
              </button>
            </>
          ) : (
            <button
              onClick={() => setConfirming(true)}
              style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, fontWeight: 600, padding: "6px 14px", cursor: "pointer", color: "#e0442e" }}
            >
              Delete
            </button>
          )}
        </div>
      </div>
      <div style={{ fontSize: 13, color: "var(--ink3)", marginTop: 6, display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
        <span>
          {race.date}
          {race.sport && ` · ${race.sport.charAt(0).toUpperCase() + race.sport.slice(1)}`}
          {race.distance_km != null && ` · ${race.distance_km} km`}
        </span>
        {race.url && (
          <a href={race.url} target="_blank" rel="noopener noreferrer" style={{ color: "var(--ember)" }}>
            Website ↗
          </a>
        )}
        {race.results_url && (
          <a href={race.results_url} target="_blank" rel="noopener noreferrer" style={{ color: "var(--ember)" }}>
            Results ↗
          </a>
        )}
        {race.activity_id && (
          <Link to={`/activities/${race.activity_id}`} style={{ color: "var(--ember)" }}>
            Activity
          </Link>
        )}
      </div>
      {race.notes && <div style={{ fontSize: 13, color: "var(--ink2)", marginTop: 8 }}>{race.notes}</div>}
    </div>
  );
}
