import { useRef, useState } from "react";
import { clearUploadHistory, uploadActivity, uploadActivityBatch, type UploadHistoryPurge, type UploadMetadata } from "../api/uploads";
import { ApiError } from "../api/types";
import type { Upload, UploadBatch } from "../api/types";
import { BatchStatus } from "./import/BatchStatus";
import { GarminSyncCard } from "./import/GarminSyncCard";
import { SessionDetailsFields } from "./import/SessionDetailsFields";
import { emptySessionDetails, toUploadMetadata, type SessionDetailsDraft } from "./import/sessionDetails";
import { UploadStatus } from "./import/UploadStatus";

export function ImportScreen() {
  const [file, setFile] = useState<File | null>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [details, setDetails] = useState<SessionDetailsDraft>(emptySessionDetails);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploadResult, setUploadResult] = useState<{ data: Upload; retryAfterSeconds: number | null } | null>(null);
  const [batchResult, setBatchResult] = useState<{ data: UploadBatch; retryAfterSeconds: number | null } | null>(
    null,
  );
  const [clearState, setClearState] = useState<"idle" | "confirming" | "clearing" | UploadHistoryPurge>("idle");
  const fileInputRef = useRef<HTMLInputElement>(null);

  function reset() {
    setFile(null);
    setDetails(emptySessionDetails);
    setError(null);
  }

  function pickFile(picked: File) {
    reset();
    setFile(picked);
  }

  async function handleSubmit() {
    if (!file) return;
    setSubmitting(true);
    setError(null);
    try {
      if (file.name.toLowerCase().endsWith(".zip")) {
        setBatchResult(await uploadActivityBatch(file));
      }
      else {
        const metadata: UploadMetadata = toUploadMetadata(details);
        setUploadResult(await uploadActivity(file, metadata));
      }
      setFile(null);
    }
    catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not upload the file.");
    }
    finally {
      setSubmitting(false);
    }
  }

  async function handleClearHistory() {
    setClearState("clearing");
    try {
      const result = await clearUploadHistory();
      setClearState(result);
    }
    catch {
      setClearState("idle");
      setError("Could not clear import history.");
    }
  }

  const isZip = file?.name.toLowerCase().endsWith(".zip");

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20, maxWidth: 560 }}>
      <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>
        {isZip ? "Import activities" : "Import an activity"}
      </h1>

      <GarminSyncCard />

      <div
        onDragOver={(e) => {
          e.preventDefault();
          setIsDragOver(true);
        }}
        onDragLeave={() => setIsDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setIsDragOver(false);
          const dropped = e.dataTransfer.files[0];
          if (dropped) pickFile(dropped);
        }}
        onClick={() => fileInputRef.current?.click()}
        style={{
          border: `1px dashed ${isDragOver ? "var(--ember)" : "var(--line)"}`,
          borderRadius: 14,
          padding: 40,
          textAlign: "center",
          cursor: "pointer",
          background: isDragOver ? "var(--ember-soft)" : "var(--card)",
        }}
      >
        <input
          ref={fileInputRef}
          type="file"
          accept=".fit,.gpx,.tcx,.zip"
          style={{ display: "none" }}
          onChange={(e) => {
            const picked = e.target.files?.[0];
            if (picked) pickFile(picked);
          }}
        />
        <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 6 }}>
          {file ? file.name : "Drop a .fit file here, or browse"}
        </div>
        <div style={{ fontSize: 12, color: "var(--ink3)" }}>Also accepts .tcx, .gpx, and .zip (bulk import)</div>
      </div>

      {file && !isZip && (
        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <h3 style={{ fontSize: 14, fontWeight: 700, margin: 0 }}>Session details (optional)</h3>
          <SessionDetailsFields value={details} onChange={setDetails} />
        </div>
      )}

      {file && (
        <button
          onClick={handleSubmit}
          disabled={submitting}
          style={{
            alignSelf: "flex-start",
            padding: "10px 20px",
            borderRadius: 10,
            border: "none",
            background: "var(--ember)",
            color: "#fff",
            fontSize: 14,
            fontWeight: 700,
            opacity: submitting ? 0.7 : 1,
          }}
        >
          {submitting ? "Uploading…" : isZip ? "Upload archive" : "Save & import"}
        </button>
      )}

      {error && <div style={{ fontSize: 13, color: "#e0442e" }}>{error}</div>}

      {uploadResult && <UploadStatus key={uploadResult.data.id} initial={uploadResult} />}
      {batchResult && <BatchStatus key={batchResult.data.id} initial={batchResult} />}

      <div style={{ borderTop: "1px solid var(--line)", paddingTop: 20, marginTop: 8 }}>
        <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 6 }}>Import history</div>
        <div style={{ fontSize: 13, color: "var(--ink2)", marginBottom: 12 }}>
          Remove skipped, failed, and duplicate import records. Your activities are not affected.
        </div>
        {clearState === "idle" && (
          <button
            onClick={() => setClearState("confirming")}
            style={{
              padding: "8px 16px",
              borderRadius: 8,
              border: "1px solid var(--line)",
              background: "transparent",
              color: "var(--ink)",
              fontSize: 13,
              fontWeight: 600,
              cursor: "pointer",
            }}
          >
            Clear import history
          </button>
        )}
        {clearState === "confirming" && (
          <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
            <span style={{ fontSize: 13, color: "var(--ink2)" }}>This cannot be undone.</span>
            <button
              onClick={() => setClearState("idle")}
              style={{
                padding: "7px 14px",
                borderRadius: 8,
                border: "1px solid var(--line)",
                background: "transparent",
                color: "var(--ink)",
                fontSize: 13,
                fontWeight: 600,
                cursor: "pointer",
              }}
            >
              Cancel
            </button>
            <button
              onClick={handleClearHistory}
              style={{
                padding: "7px 14px",
                borderRadius: 8,
                border: "none",
                background: "var(--ember)",
                color: "#fff",
                fontSize: 13,
                fontWeight: 700,
                cursor: "pointer",
              }}
            >
              Clear
            </button>
          </div>
        )}
        {clearState === "clearing" && (
          <span style={{ fontSize: 13, color: "var(--ink2)" }}>Clearing…</span>
        )}
        {typeof clearState === "object" && (
          <span style={{ fontSize: 13, color: "#2fa66a" }}>
            Cleared {clearState.uploads_deleted.toLocaleString()} import records.
          </span>
        )}
      </div>
    </div>
  );
}
