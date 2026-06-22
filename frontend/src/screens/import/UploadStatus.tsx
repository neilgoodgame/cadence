import { Link } from "react-router-dom";
import { getUpload } from "../../api/uploads";
import type { Upload } from "../../api/types";
import { usePolling } from "./usePolling";

const TERMINAL_STATUSES = new Set(["ready", "failed", "duplicate"]);

export function UploadStatus({ initial }: { initial: { data: Upload; retryAfterSeconds: number | null } }) {
  const upload = usePolling(initial, getUpload, (u) => u.id, (u) => TERMINAL_STATUSES.has(u.status));

  if (upload.status === "ready" && upload.activity_id) {
    return (
      <div style={{ color: "#2fa66a", fontSize: 14 }}>
        Imported -{" "}
        <Link to={`/activities/${upload.activity_id}`} style={{ color: "#2fa66a", fontWeight: 600 }}>
          view the activity →
        </Link>
      </div>
    );
  }

  if (upload.status === "duplicate" && upload.activity_id) {
    return (
      <div style={{ color: "var(--ink2)", fontSize: 14 }}>
        Already imported -{" "}
        <Link to={`/activities/${upload.activity_id}`} style={{ color: "var(--ember)", fontWeight: 600 }}>
          view the existing activity →
        </Link>
      </div>
    );
  }

  if (upload.status === "failed") {
    return <div style={{ color: "#e0442e", fontSize: 14 }}>{upload.error?.message ?? "Import failed."}</div>;
  }

  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, color: "var(--ink2)", fontSize: 14 }}>
      <span className="mono" style={{ fontSize: 13 }}>
        {upload.filename}
      </span>
      <span>{upload.status === "queued" ? "Queued…" : "Processing…"}</span>
    </div>
  );
}
