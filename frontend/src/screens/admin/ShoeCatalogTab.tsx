import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  addShoeCatalogVersion,
  createOrAppendShoeCatalogEntry,
  deleteShoeCatalogModel,
  listAdminShoeCatalog,
} from "../../api/admin";
import { ApiError } from "../../api/types";
import type { AdminShoeCatalogEntry } from "../../api/types";

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
    </div>
  );
}
