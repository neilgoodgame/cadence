import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listShoes } from "../api/gear";
import { uploadActivity, uploadActivityBatch, type UploadMetadata } from "../api/uploads";
import { ApiError } from "../api/types";
import type { Upload, UploadBatch } from "../api/types";
import { BatchStatus } from "./import/BatchStatus";
import { UploadStatus } from "./import/UploadStatus";

const inputStyle: React.CSSProperties = {
  width: "100%",
  padding: "10px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 14,
  color: "var(--ink)",
};

export function ImportScreen() {
  const [file, setFile] = useState<File | null>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [weightBefore, setWeightBefore] = useState("");
  const [weightAfter, setWeightAfter] = useState("");
  const [fluids, setFluids] = useState("");
  const [shoeId, setShoeId] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploadResult, setUploadResult] = useState<{ data: Upload; retryAfterSeconds: number | null } | null>(null);
  const [batchResult, setBatchResult] = useState<{ data: UploadBatch; retryAfterSeconds: number | null } | null>(
    null,
  );
  const fileInputRef = useRef<HTMLInputElement>(null);

  const { data: shoes } = useQuery({ queryKey: ["shoes"], queryFn: listShoes });

  function reset() {
    setFile(null);
    setWeightBefore("");
    setWeightAfter("");
    setFluids("");
    setShoeId("");
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
        const metadata: UploadMetadata = {
          weightBeforeKg: weightBefore ? Number(weightBefore) : undefined,
          weightAfterKg: weightAfter ? Number(weightAfter) : undefined,
          fluidsMl: fluids ? Number(fluids) : undefined,
          shoeId: shoeId || undefined,
        };
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

  const isZip = file?.name.toLowerCase().endsWith(".zip");

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20, maxWidth: 560 }}>
      <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>Import an activity</h1>

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
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <label>
              <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>
                Weight before (kg)
              </div>
              <input
                type="number"
                step="0.1"
                className="mono"
                style={inputStyle}
                value={weightBefore}
                onChange={(e) => setWeightBefore(e.target.value)}
                placeholder="72.4"
              />
            </label>
            <label>
              <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>
                Weight after (kg)
              </div>
              <input
                type="number"
                step="0.1"
                className="mono"
                style={inputStyle}
                value={weightAfter}
                onChange={(e) => setWeightAfter(e.target.value)}
                placeholder="70.9"
              />
            </label>
            <label>
              <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>
                Fluids consumed (ml)
              </div>
              <input
                type="number"
                step="50"
                className="mono"
                style={inputStyle}
                value={fluids}
                onChange={(e) => setFluids(e.target.value)}
                placeholder="750"
              />
            </label>
            <label>
              <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Shoes</div>
              <select style={inputStyle} value={shoeId} onChange={(e) => setShoeId(e.target.value)}>
                <option value="">None</option>
                {shoes?.data.map((shoe) => (
                  <option key={shoe.id} value={shoe.id}>
                    {shoe.name}
                  </option>
                ))}
              </select>
            </label>
          </div>
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

      {uploadResult && <UploadStatus initial={uploadResult} />}
      {batchResult && <BatchStatus initial={batchResult} />}
    </div>
  );
}
