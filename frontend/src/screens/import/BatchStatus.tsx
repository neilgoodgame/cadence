import { getUploadBatch } from "../../api/uploads";
import type { Sport, Upload, UploadBatch } from "../../api/types";
import { usePolling } from "./usePolling";

const TERMINAL_STATUSES = new Set(["completed", "failed"]);

const SPORT_LABELS: Record<Sport, string> = {
  bike: "Cycling",
  run: "Running",
  swim: "Swimming",
  walk: "Walking",
};

function ProgressBar({ value }: { value: number }) {
  return (
    <div
      style={{
        height: 6,
        borderRadius: 3,
        background: "var(--line)",
        overflow: "hidden",
      }}
    >
      <div
        style={{
          height: "100%",
          width: `${Math.round(value * 100)}%`,
          background: "var(--ember)",
          borderRadius: 3,
          transition: "width 0.4s ease",
        }}
      />
    </div>
  );
}

function sportBreakdown(uploads: Upload[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const u of uploads) {
    if (u.status === "ready" && u.activity_sport) {
      counts[u.activity_sport] = (counts[u.activity_sport] ?? 0) + 1;
    }
  }
  return counts;
}

export function BatchStatus({ initial }: { initial: { data: UploadBatch; retryAfterSeconds: number | null } }) {
  const batch = usePolling(initial, getUploadBatch, (b) => b.id, (b) => TERMINAL_STATUSES.has(b.status));

  if (batch.status === "failed") {
    return <div style={{ color: "#e0442e", fontSize: 14 }}>Could not read the archive.</div>;
  }

  const { counts, progress } = batch;
  const done = batch.status === "completed";
  const byType = done ? sportBreakdown(batch.uploads) : {};

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <span style={{ fontSize: 13, fontWeight: 600 }} className="mono">{batch.filename}</span>
        <span style={{ fontSize: 12, color: "var(--ink3)" }}>
          {done
            ? `${counts.ready} imported, ${counts.duplicate} duplicate${counts.failed > 0 ? `, ${counts.failed} failed` : ""}`
            : `${counts.total - counts.processing} / ${counts.total}`}
        </span>
      </div>

      <ProgressBar value={done ? 1 : progress} />

      {done && (
        <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 4 }}>
          {counts.ready > 0 && (
            <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: "var(--ink2)", textTransform: "uppercase", letterSpacing: "0.05em" }}>
                Imported by type
              </div>
              <div style={{ display: "flex", gap: 16 }}>
                {Object.entries(byType).map(([sport, n]) => (
                  <div key={sport} style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 2 }}>
                    <span style={{ fontSize: 22, fontWeight: 800 }}>{n}</span>
                    <span style={{ fontSize: 11, color: "var(--ink3)" }}>{SPORT_LABELS[sport as Sport] ?? sport}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {(counts.failed > 0 || counts.duplicate > 0) && (
            <div style={{ display: "flex", gap: 16, fontSize: 13, color: "var(--ink2)" }}>
              {counts.duplicate > 0 && <span>{counts.duplicate} already imported</span>}
              {counts.failed > 0 && <span style={{ color: "#e0442e" }}>{counts.failed} failed</span>}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
