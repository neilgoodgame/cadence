import { useState } from "react";
import type { ChangeEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  addShoeCatalogVersion,
  createOrAppendShoeCatalogEntry,
  deleteShoeCatalogModel,
  importShoeCatalog,
  listAdminShoeCatalog,
} from "../../api/admin";
import { ApiError } from "../../api/types";
import type { AdminShoeCatalogEntry, AdminShoeCatalogImportResult } from "../../api/types";
import { parseCsv } from "../../lib/csv";

const inputStyle: React.CSSProperties = {
  width: "100%",
  padding: "9px 11px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  color: "var(--ink)",
  fontSize: 13,
  outline: "none",
};

const btnStyle: React.CSSProperties = {
  padding: "8px 14px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 13,
  fontWeight: 600,
  color: "var(--ink2)",
  cursor: "pointer",
};

const iconBtnStyle: React.CSSProperties = {
  width: 24,
  height: 24,
  borderRadius: 7,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  cursor: "pointer",
  flexShrink: 0,
  border: "none",
  background: "none",
};

function AddVersionIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.8}>
      <line x1="8" y1="3" x2="8" y2="13" />
      <line x1="3" y1="8" x2="13" y2="8" />
    </svg>
  );
}

function TrashIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.6}>
      <path d="M3 4h10M6 4V2.6h4V4M4.5 4l.6 9h5.8l.6-9" />
    </svg>
  );
}

function CatalogRow({ entry }: { entry: AdminShoeCatalogEntry }) {
  const qc = useQueryClient();
  const [addingVersion, setAddingVersion] = useState(false);
  const [newVersion, setNewVersion] = useState("");
  const [rowError, setRowError] = useState<string | null>(null);

  const addVersionMutation = useMutation({
    mutationFn: () => addShoeCatalogVersion(entry.id, newVersion.trim()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-shoe-catalog"] });
      setAddingVersion(false);
      setNewVersion("");
      setRowError(null);
    },
    onError: (err) => setRowError(err instanceof ApiError ? err.message : "Could not add that version."),
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteShoeCatalogModel(entry.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-shoe-catalog"] });
      qc.invalidateQueries({ queryKey: ["admin-audit-log"] });
    },
    onError: (err) => setRowError(err instanceof ApiError ? err.message : "Could not delete this shoe model."),
  });

  return (
    <div style={{ borderBottom: "1px solid var(--line)" }}>
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "2fr 60px 60px 60px",
          gap: 8,
          padding: "12px 14px",
          alignItems: "center",
        }}
      >
        <div style={{ minWidth: 0, overflow: "hidden" }}>
          <div style={{ fontSize: 13.5, fontWeight: 700, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
            {entry.manufacturer}
          </div>
          <div style={{ fontSize: 12.5, color: "var(--ink2)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
            {entry.model}
          </div>
        </div>
        <span
          className="mono"
          title={entry.versions.map((v) => `${v.version} — ${v.usage_count} in use`).join("\n")}
          style={{ fontSize: 12, color: "var(--ink2)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
        >
          {entry.versions.map((v) => `${v.version}(${v.usage_count})`).join(", ")}
        </span>
        <span style={{ fontSize: 11.5, color: "var(--ink3)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
          {entry.added_by ?? "system"}
        </span>
        <div style={{ display: "flex", gap: 4, justifySelf: "end", flexShrink: 0 }}>
          <button
            onClick={() => setAddingVersion((v) => !v)}
            title="Add version"
            style={{ ...iconBtnStyle, color: "var(--ink3)" }}
          >
            <AddVersionIcon />
          </button>
          <button
            onClick={() => {
              if (window.confirm(`Delete ${entry.manufacturer} ${entry.model}? This removes all its versions.`)) {
                deleteMutation.mutate();
              }
            }}
            disabled={deleteMutation.isPending}
            title="Delete"
            style={{ ...iconBtnStyle, color: "#e0442e" }}
          >
            <TrashIcon />
          </button>
        </div>
      </div>

      {addingVersion && (
        <div style={{ display: "flex", gap: 6, alignItems: "center", padding: "0 14px 12px" }}>
          <input
            autoFocus
            value={newVersion}
            onChange={(e) => setNewVersion(e.target.value)}
            placeholder="New version"
            style={{ ...inputStyle, width: 120, padding: "6px 9px" }}
          />
          <button
            onClick={() => newVersion.trim() && addVersionMutation.mutate()}
            disabled={addVersionMutation.isPending || !newVersion.trim()}
            style={{ ...btnStyle, padding: "6px 12px", fontSize: 12 }}
          >
            {addVersionMutation.isPending ? "Adding…" : "Add"}
          </button>
          <button
            onClick={() => {
              setAddingVersion(false);
              setRowError(null);
            }}
            style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12, cursor: "pointer" }}
          >
            Cancel
          </button>
        </div>
      )}
      {rowError && <div style={{ fontSize: 12, color: "#e0442e", padding: "0 14px 12px" }}>{rowError}</div>}
    </div>
  );
}

interface ParsedImportRow {
  manufacturer: string;
  model: string;
  version: string;
  sourceRows: number;
}

/** Groups parsed CSV rows into distinct (manufacturer, model, version) catalog entries - a
 * version-less row (e.g. a Strava export's "Evo SL" with no generation number) is kept with an
 * empty version rather than being dropped, so the composed display name omits it instead of
 * showing a misleading "1", and rows that only differ by a column the catalog doesn't track
 * (colourway, mileage, ...) collapse into the same entry instead of trying to add the same
 * version twice. */
function groupImportRows(rows: string[][]): ParsedImportRow[] {
  if (rows.length < 2) {
    throw new Error("This file has no data rows.");
  }
  const header = rows[0].map((h) => h.trim().toLowerCase());
  const manufacturerIdx = header.indexOf("manufacturer");
  const modelIdx = header.indexOf("model");
  const versionIdx = header.indexOf("version");
  if (manufacturerIdx === -1 || modelIdx === -1) {
    throw new Error('Expected a header row with "Manufacturer" and "Model" columns.');
  }

  const byKey = new Map<string, ParsedImportRow>();
  for (const row of rows.slice(1)) {
    const manufacturer = (row[manufacturerIdx] ?? "").trim();
    const model = (row[modelIdx] ?? "").trim();
    const version = versionIdx === -1 ? "" : (row[versionIdx] ?? "").trim();
    if (!manufacturer || !model) continue;
    const key = `${manufacturer.toLowerCase()}|${model.toLowerCase()}|${version.toLowerCase()}`;
    const existing = byKey.get(key);
    if (existing) existing.sourceRows++;
    else byKey.set(key, { manufacturer, model, version, sourceRows: 1 });
  }
  if (byKey.size === 0) {
    throw new Error("No valid manufacturer/model rows found.");
  }
  return [...byKey.values()];
}

function ImportCsvPanel() {
  const qc = useQueryClient();
  const [parsed, setParsed] = useState<ParsedImportRow[] | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<AdminShoeCatalogImportResult | null>(null);

  const importMutation = useMutation({
    mutationFn: () =>
      importShoeCatalog(parsed!.map(({ manufacturer, model, version }) => ({ manufacturer, model, version }))),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ["admin-shoe-catalog"] });
      qc.invalidateQueries({ queryKey: ["admin-audit-log"] });
      setResult(res);
      setParsed(null);
      setFileName(null);
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : "Could not import this file."),
  });

  async function onFileSelected(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setResult(null);
    setError(null);
    setParsed(null);
    setFileName(file.name);
    try {
      setParsed(groupImportRows(parseCsv(await file.text())));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not read this file.");
    }
  }

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "20px 22px" }}>
      <div style={{ fontSize: 15, fontWeight: 700, marginBottom: 14 }}>Import from CSV</div>
      <input type="file" accept=".csv,text/csv" onChange={onFileSelected} style={{ fontSize: 12.5, color: "var(--ink2)", width: "100%" }} />
      <div style={{ fontSize: 11.5, color: "var(--ink3)", lineHeight: 1.5, marginTop: 10 }}>
        Expects a header row with Manufacturer, Model and (optionally) Version columns - e.g. a Strava shoe-rotation
        export. Other columns are ignored. A row with no version is imported without one. Entries that already
        exist in the catalog are skipped, so re-importing the same file is safe.
      </div>
      {error && <div style={{ fontSize: 12, color: "#e0442e", marginTop: 10 }}>{error}</div>}
      {parsed && (
        <div style={{ marginTop: 14 }}>
          <div style={{ fontSize: 12.5, color: "var(--ink2)", marginBottom: 8 }}>
            {fileName}: {parsed.length} shoe {parsed.length === 1 ? "entry" : "entries"} found.
          </div>
          <div style={{ maxHeight: 180, overflowY: "auto", border: "1px solid var(--line)", borderRadius: 8, marginBottom: 10 }}>
            {parsed.map((p, i) => (
              <div
                key={`${p.manufacturer}|${p.model}|${p.version}`}
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  gap: 8,
                  padding: "6px 10px",
                  fontSize: 12,
                  borderBottom: i < parsed.length - 1 ? "1px solid var(--line)" : "none",
                }}
              >
                <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {p.manufacturer} {p.model}
                </span>
                <span className="mono" style={{ color: "var(--ink3)", flexShrink: 0 }}>
                  {p.version ? `v${p.version}` : ""}
                </span>
              </div>
            ))}
          </div>
          <button
            onClick={() => importMutation.mutate()}
            disabled={importMutation.isPending}
            style={{
              width: "100%",
              textAlign: "center",
              padding: "10px 0",
              borderRadius: 8,
              border: "none",
              background: "var(--ember)",
              color: "#fff",
              fontSize: 13,
              fontWeight: 700,
              cursor: "pointer",
              opacity: importMutation.isPending ? 0.6 : 1,
            }}
          >
            {importMutation.isPending ? "Importing…" : `Import ${parsed.length} ${parsed.length === 1 ? "entry" : "entries"}`}
          </button>
        </div>
      )}
      {result && (
        <div style={{ fontSize: 12.5, color: "#2fa66a", marginTop: 12, lineHeight: 1.5 }}>
          Imported {result.models_created} new model{result.models_created === 1 ? "" : "s"} and {result.versions_added}{" "}
          version{result.versions_added === 1 ? "" : "s"}
          {result.skipped > 0 ? ` (${result.skipped} already in the catalog, skipped)` : ""}.
        </div>
      )}
    </div>
  );
}

export function ShoeCatalogTab() {
  const qc = useQueryClient();
  const [search, setSearch] = useState("");
  const [manufacturer, setManufacturer] = useState("");
  const [model, setModel] = useState("");
  const [version, setVersion] = useState("");
  const [importError, setImportError] = useState<string | null>(null);

  const { data } = useQuery({
    queryKey: ["admin-shoe-catalog", search],
    queryFn: () => listAdminShoeCatalog(search),
  });

  const importMutation = useMutation({
    mutationFn: () => createOrAppendShoeCatalogEntry({ manufacturer, model, version }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-shoe-catalog"] });
      qc.invalidateQueries({ queryKey: ["admin-audit-log"] });
      setManufacturer("");
      setModel("");
      setVersion("");
      setImportError(null);
    },
    onError: (err) => setImportError(err instanceof ApiError ? err.message : "Could not import that shoe model."),
  });

  const rows = data?.data ?? [];
  const canImport = manufacturer.trim() && model.trim() && version.trim();

  return (
    <div style={{ display: "grid", gridTemplateColumns: "1fr 320px", gap: 16, alignItems: "start" }}>
      <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, overflow: "hidden" }}>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 12,
            flexWrap: "wrap",
            padding: "14px 18px",
            borderBottom: "1px solid var(--line)",
          }}
        >
          <div style={{ fontSize: 15, fontWeight: 700, whiteSpace: "nowrap" }}>Shoe catalog</div>
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search manufacturer or model…"
            style={{ ...inputStyle, flex: 1, minWidth: 140, maxWidth: 220, padding: "7px 12px", fontSize: 12.5 }}
          />
        </div>
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "2fr 60px 60px 60px",
            gap: 8,
            padding: "9px 14px",
            fontFamily: "monospace",
            fontSize: 10,
            textTransform: "uppercase",
            letterSpacing: "0.05em",
            color: "var(--ink3)",
            borderBottom: "1px solid var(--line)",
            whiteSpace: "nowrap",
          }}
        >
          <span>Model</span>
          <span>Ver.</span>
          <span>By</span>
          <span style={{ textAlign: "right", paddingRight: 2 }}>•••</span>
        </div>
        {rows.length === 0 && (
          <div style={{ padding: "24px 18px", fontSize: 13, color: "var(--ink3)" }}>No shoe models found.</div>
        )}
        {rows.map((entry) => (
          <CatalogRow key={entry.id} entry={entry} />
        ))}
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "20px 22px" }}>
          <div style={{ fontSize: 15, fontWeight: 700, marginBottom: 14 }}>Import shoe model</div>
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <div>
              <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", color: "var(--ink3)", textTransform: "uppercase", marginBottom: 6 }}>
                Manufacturer
              </div>
              <input value={manufacturer} onChange={(e) => setManufacturer(e.target.value)} placeholder="e.g. Saucony" style={inputStyle} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", color: "var(--ink3)", textTransform: "uppercase", marginBottom: 6 }}>
                Model
              </div>
              <input value={model} onChange={(e) => setModel(e.target.value)} placeholder="e.g. Endorphin Speed" style={inputStyle} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", color: "var(--ink3)", textTransform: "uppercase", marginBottom: 6 }}>
                Version
              </div>
              <input value={version} onChange={(e) => setVersion(e.target.value)} placeholder="e.g. 4" style={inputStyle} />
            </div>
            <button
              onClick={() => importMutation.mutate()}
              disabled={!canImport || importMutation.isPending}
              style={{
                textAlign: "center",
                padding: "10px 0",
                borderRadius: 8,
                border: "none",
                background: "var(--ember)",
                color: "#fff",
                fontSize: 13,
                fontWeight: 700,
                cursor: "pointer",
                marginTop: 4,
                opacity: !canImport || importMutation.isPending ? 0.6 : 1,
              }}
            >
              {importMutation.isPending ? "Importing…" : "Import"}
            </button>
            {importError && <div style={{ fontSize: 12, color: "#e0442e" }}>{importError}</div>}
            <div style={{ fontSize: 11.5, color: "var(--ink3)", lineHeight: 1.5 }}>
              If this manufacturer + model already exists, the version is appended to it instead of creating a
              duplicate entry. Visible to all athletes immediately.
            </div>
          </div>
        </div>

        <ImportCsvPanel />
      </div>
    </div>
  );
}
