import { useRef, useState } from "react";
import { getImportJob, startImport } from "../../api/dataImport";
import { downloadExport, getExportJob, startExport } from "../../api/export";
import type { ImportJob, ExportJob, Sport } from "../../api/types";
import { usePolling } from "../import/usePolling";
import { DataTransferProgressDialog } from "./DataTransferProgressDialog";

const TERMINAL_STATUSES = new Set(["ready", "failed"]);

// multisport/transition are composition artifacts of an activity, not a discipline someone
// would choose to export by - deliberately not offered here (see ExportWriter.writeActivities).
const SPORT_OPTIONS: { value: Sport | ""; label: string }[] = [
  { value: "", label: "All sports" },
  { value: "bike", label: "Bike" },
  { value: "run", label: "Run" },
  { value: "swim", label: "Swim" },
  { value: "walk", label: "Walk" },
  { value: "row", label: "Row" },
];

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

const SELECT_STYLE: React.CSSProperties = {
  border: "1px solid var(--line)",
  borderRadius: 8,
  padding: "8px 10px",
  fontSize: 14,
  background: "var(--elev)",
  color: "var(--ink)",
};

function triggerDownload(filename: string, blob: Blob): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

function ExportProgressDialog({
  initial,
  onClose,
}: {
  initial: { data: ExportJob; retryAfterSeconds: number | null };
  onClose: () => void;
}) {
  const job = usePolling(initial, getExportJob, (j) => j.id, (j) => TERMINAL_STATUSES.has(j.status));
  const [downloading, setDownloading] = useState(false);

  return (
    <DataTransferProgressDialog
      title="Exporting your data"
      status={job.status}
      currentStep={job.current_step}
      totalItems={job.total_items}
      processedItems={job.processed_items}
      errorMessage={job.error_message}
      onClose={onClose}
    >
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
          style={PRIMARY_BUTTON_STYLE}
        >
          {downloading ? "Downloading…" : "Download export"}
        </button>
        <button
          onClick={onClose}
          style={{ border: "1px solid var(--line)", borderRadius: 8, padding: "8px 16px", fontSize: 13, fontWeight: 600, background: "transparent", color: "var(--ink)" }}
        >
          Close
        </button>
      </div>
    </DataTransferProgressDialog>
  );
}

function ImportCountsSummary({ job }: { job: ImportJob }) {
  const { counts } = job;
  const parts = [
    [counts.activities_imported, "activities"],
    [counts.races_imported, "races"],
    [counts.workouts_imported, "workouts"],
    [counts.scheduled_workouts_imported, "scheduled workouts"],
    [counts.threshold_history_imported, "threshold history entries"],
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

function ImportProgressDialog({
  initial,
  onClose,
}: {
  initial: { data: ImportJob; retryAfterSeconds: number | null };
  onClose: () => void;
}) {
  const job = usePolling(initial, getImportJob, (j) => j.id, (j) => TERMINAL_STATUSES.has(j.status));

  return (
    <DataTransferProgressDialog
      title="Importing your data"
      status={job.status}
      currentStep={job.current_step}
      totalItems={job.total_items}
      processedItems={job.processed_items}
      errorMessage={job.error_message}
      onClose={onClose}
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <ImportCountsSummary job={job} />
        <button onClick={onClose} style={{ ...PRIMARY_BUTTON_STYLE, alignSelf: "flex-start" }}>
          Done
        </button>
      </div>
    </DataTransferProgressDialog>
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
        disabled={uploading || !!job}
        onClick={() => fileInputRef.current?.click()}
        style={{ ...PRIMARY_BUTTON_STYLE, opacity: uploading || job ? 0.6 : 1 }}
      >
        {uploading ? "Uploading…" : "Choose file to import"}
      </button>

      {job && <ImportProgressDialog initial={job} onClose={() => setJob(null)} />}
    </div>
  );
}

export function ExportTab() {
  const [job, setJob] = useState<{ data: ExportJob; retryAfterSeconds: number | null } | null>(null);
  const [sport, setSport] = useState<Sport | "">("");

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 28, maxWidth: 480 }}>
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <div>
          <h3 style={{ margin: "0 0 4px", fontSize: 16 }}>Export your data</h3>
          <p style={{ margin: 0, color: "var(--ink2)", fontSize: 14 }}>
            Download all of your activities (including recorded streams), races, workouts, and equipment as a single
            compressed JSON file (.json.gz). Optionally narrow it to a single sport.
          </p>
        </div>

        <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
          <select
            value={sport}
            disabled={!!job}
            onChange={(e) => setSport(e.target.value as Sport | "")}
            style={SELECT_STYLE}
          >
            {SPORT_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <button
            disabled={!!job}
            onClick={() => startExport(sport || undefined).then(setJob)}
            style={{ ...PRIMARY_BUTTON_STYLE, opacity: job ? 0.6 : 1 }}
          >
            Generate export
          </button>
        </div>

        {job && <ExportProgressDialog initial={job} onClose={() => setJob(null)} />}
      </div>

      <div style={{ borderTop: "1px solid var(--line)", paddingTop: 20 }}>
        <ImportSection />
      </div>
    </div>
  );
}
