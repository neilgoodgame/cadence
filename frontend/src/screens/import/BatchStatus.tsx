import { Link } from "react-router-dom";
import { getUploadBatch } from "../../api/uploads";
import type { UploadBatch } from "../../api/types";
import { usePolling } from "./usePolling";

const TERMINAL_STATUSES = new Set(["completed", "failed"]);

export function BatchStatus({ initial }: { initial: { data: UploadBatch; retryAfterSeconds: number | null } }) {
  const batch = usePolling(initial, getUploadBatch, (b) => b.id, (b) => TERMINAL_STATUSES.has(b.status));

  if (batch.status === "failed") {
    return <div style={{ color: "#e0442e", fontSize: 14 }}>Could not read the archive.</div>;
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <div style={{ flex: 1, height: 6, borderRadius: 4, background: "var(--elev)", overflow: "hidden" }}>
          <div
            style={{
              height: "100%",
              width: `${batch.progress * 100}%`,
              background: "var(--ember)",
              transition: "width 300ms ease",
            }}
          />
        </div>
        <span className="mono" style={{ fontSize: 12, color: "var(--ink2)", minWidth: 36, textAlign: "right" }}>
          {Math.round(batch.progress * 100)}%
        </span>
      </div>

      <div style={{ display: "flex", gap: 16, fontSize: 13, color: "var(--ink2)" }}>
        <span className="mono">{batch.filename}</span>
        <span>{batch.counts.ready} ready</span>
        <span>{batch.counts.processing} processing</span>
        {batch.counts.failed > 0 && <span style={{ color: "#e0442e" }}>{batch.counts.failed} failed</span>}
        {batch.counts.duplicate > 0 && <span>{batch.counts.duplicate} duplicate</span>}
        {batch.counts.skipped > 0 && <span>{batch.counts.skipped} skipped</span>}
        <span>of {batch.counts.total}</span>
      </div>

      {batch.status === "completed" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          {/* Skipped metadata stubs can outnumber real files a thousand to one in a Garmin
              account export; the summary count covers them, so keep them out of the list. */}
          {batch.uploads.filter((upload) => upload.status !== "skipped").map((upload) => (
            <div key={upload.id} style={{ display: "flex", gap: 10, fontSize: 13, alignItems: "center" }}>
              <span className="mono" style={{ color: "var(--ink2)", flex: 1 }}>
                {upload.filename}
              </span>
              {upload.status === "ready" && upload.activity_id && (
                <Link to={`/activities/${upload.activity_id}`} style={{ color: "#2fa66a" }}>
                  Imported →
                </Link>
              )}
              {upload.status === "duplicate" && <span style={{ color: "var(--ink3)" }}>Duplicate</span>}
              {upload.status === "failed" && (
                <span style={{ color: "#e0442e" }}>{upload.error?.message ?? "Failed"}</span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
