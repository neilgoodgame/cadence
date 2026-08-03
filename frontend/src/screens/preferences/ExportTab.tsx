import { useRef, useState } from "react";
import { getImportJob, startImport } from "../../api/dataImport";
import { downloadExport, getExportJob, startExport } from "../../api/export";
import type { ImportJob, ExportJob } from "../../api/types";
import { usePolling } from "../import/usePolling";

const TERMINAL_STATUSES = new Set(["ready", "failed"]);

const PRIMARY_BUTTON_STYLE: React.CSSProperties = {
  alignSelf: "flex-start",
  border: "none",
  borderRadius: 8,
  padding: "9px 16px",
  fontSize: 14,
  fontWeight: 600,
  background: "var(--ember)",
  color: "#fff",
};

function triggerDownload(filename: string, blob: Blob): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

function ExportStatus({
  initial,
  onReset,
}: {
  initial: { data: ExportJob; retryAfterSeconds: number | null };
  onReset: () => void;
}) {
  const job = usePolling(initial, getExportJob, (j) => j.id, (j) => TERMINAL_STATUSES.has(j.status));
  const [downloading, setDownloading] = useState(false);

  if (job.status === "ready") {
    return (
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <button
          disabled={downloading}
          onClick={async () => {
            setDownloading(true);
            try {
              const blob = await downloadExport(job.id);
              triggerDownload(`cadence-export-${new Date().toISOString().slice(0, 10)}.json.gz`, blob);
            }
            finally {
              setDownloading(false);
            }
          }}
          style={{
            border: "none",
            borderRadius: 8,
            padding: "9px 16px",
            fontSize: 14,
            fontWeight: 600,
            background: "var(--ember)",
            color: "#fff",
          }}
        >
          {downloading ? "Downloading…" : "Download export"}
        </button>
        <span style={{ color: "var(--ink2)", fontSize: 13 }}>
          Ready{job.completed_at ? ` · ${new Date(job.completed_at).toLocaleString()}` : ""}
        </span>
      </div>
    );
  }

  if (job.status === "failed") {
    return (
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <span style={{ color: "#e0442e", fontSize: 14 }}>{job.error_message ?? "Export failed."}</span>
        <button
          onClick={onReset}
          style={{ border: "1px solid var(--line)", borderRadius: 8, padding: "6px 12px", fontSize: 13, background: "transparent" }}
        >
          Try again
        </button>
      </div>
    );
  }

  return (
    <div style={{ color: "var(--ink2)", fontSize: 14 }}>
      {job.status === "queued" ? "Queued…" : "Preparing your export…"}
    </div>
  );
}

function ImportCountsSummary({ job }: { job: ImportJob }) {
  const { counts } = job;
  const parts = [
    [counts.activities_imported, "activities"],
    [counts.races_imported, "races"],
    [counts.workouts_imported, "workouts"],
    [counts.scheduled_workouts_imported, "scheduled workouts"],
    [counts.bikes_imported + counts.shoes_imported + counts.components_imported, "equipment items"],
  ] as const;
  return (
    <div style={{ color: "var(--ink2)", fontSize: 13 }}>
      Imported {parts.map(([n, label]) => `${n} ${label}`).join(", ")}
      {counts.items_skipped > 0 ? ` · ${counts.items_skipped} item(s) skipped` : ""}
      {job.completed_at ? ` · ${new Date(job.completed_at).toLocaleString()}` : ""}
    </div>
  );
}

function ImportStatusView({
  initial,
  onReset,
}: {
  initial: { data: ImportJob; retryAfterSeconds: number | null };
  onReset: () => void;
}) {
  const job = usePolling(initial, getImportJob, (j) => j.id, (j) => TERMINAL_STATUSES.has(j.status));

  if (job.status === "ready") {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <ImportCountsSummary job={job} />
        <button
          onClick={onReset}
          style={{ alignSelf: "flex-start", border: "1px solid var(--line)", borderRadius: 8, padding: "6px 12px", fontSize: 13, background: "transparent" }}
        >
          Import another file
        </button>
      </div>
    );
  }

  if (job.status === "failed") {
    return (
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <span style={{ color: "#e0442e", fontSize: 14 }}>{job.error_message ?? "Import failed."}</span>
        <button
          onClick={onReset}
          style={{ border: "1px solid var(--line)", borderRadius: 8, padding: "6px 12px", fontSize: 13, background: "transparent" }}
        >
          Try again
        </button>
      </div>
    );
  }

  return (
    <div style={{ color: "var(--ink2)", fontSize: 14 }}>
      {job.status === "queued" ? "Queued…" : "Importing your data…"}
    </div>
  );
}

function ImportSection() {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [job, setJob] = useState<{ data: ImportJob; retryAfterSeconds: number | null } | null>(null);
  const [uploading, setUploading] = useState(false);

  return (
    <div>
      <h3 style={{ margin: "0 0 4px", fontSize: 16 }}>Import your data</h3>
      <p style={{ margin: "0 0 12px", color: "var(--ink2)", fontSize: 14 }}>
        Restore activities, races, workouts, and equipment from a previously-exported .json.gz file. This always
        creates new entries - importing the same file twice will create duplicates.
      </p>

      {job ? (
        <ImportStatusView initial={job} onReset={() => setJob(null)} />
      ) : (
        <>
          <input
            ref={fileInputRef}
            type="file"
            accept=".gz"
            style={{ display: "none" }}
            onChange={async (e) => {
              const file = e.target.files?.[0];
              e.target.value = "";
              if (!file) return;
              setUploading(true);
              try {
                setJob(await startImport(file));
              }
              finally {
                setUploading(false);
              }
            }}
          />
          <button
            disabled={uploading}
            onClick={() => fileInputRef.current?.click()}
            style={PRIMARY_BUTTON_STYLE}
          >
            {uploading ? "Uploading…" : "Choose file to import"}
          </button>
        </>
      )}
    </div>
  );
}

export function ExportTab() {
  const [job, setJob] = useState<{ data: ExportJob; retryAfterSeconds: number | null } | null>(null);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 28, maxWidth: 480 }}>
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <div>
          <h3 style={{ margin: "0 0 4px", fontSize: 16 }}>Export your data</h3>
          <p style={{ margin: 0, color: "var(--ink2)", fontSize: 14 }}>
            Download all of your activities (including recorded streams), races, workouts, and equipment as a single
            compressed JSON file (.json.gz).
          </p>
        </div>

        {job ? (
          <ExportStatus initial={job} onReset={() => setJob(null)} />
        ) : (
          <button onClick={() => startExport().then(setJob)} style={PRIMARY_BUTTON_STYLE}>
            Generate export
          </button>
        )}
      </div>

      <div style={{ borderTop: "1px solid var(--line)", paddingTop: 20 }}>
        <ImportSection />
      </div>
    </div>
  );
}
