import { useState } from "react";
import type { ChangeEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../auth/AuthContext";
import { importShoes } from "../../api/gear";
import { ApiError } from "../../api/types";
import type { ShoeImportEntry, ShoeImportResult } from "../../api/types";
import { parseCsv } from "../../lib/csv";

interface ParsedImportRow extends ShoeImportEntry {
  key: string;
}

/** Groups parsed CSV rows into import entries - unlike the admin catalog's bulk import (which
 * only tracks manufacturer+model+version), colourway and distance genuinely distinguish one
 * physical pair from another here, so every row (after dropping exact full duplicates) becomes
 * its own entry rather than being collapsed. A version-less row is imported with an empty
 * version - matching the admin catalog importer's own handling, so the same CSV resolves to the
 * same catalog identity through either importer, and the composed name omits the version
 * instead of showing a misleading "1". */
function parseImportRows(rows: string[][]): ParsedImportRow[] {
  if (rows.length < 2) {
    throw new Error("This file has no data rows.");
  }
  const header = rows[0].map((h) => h.trim().toLowerCase());
  const manufacturerIdx = header.indexOf("manufacturer");
  const modelIdx = header.indexOf("model");
  const versionIdx = header.indexOf("version");
  const colourwayIdx = header.indexOf("colourway");
  const distanceIdx = header.findIndex((h) => h === "distance_km" || h === "distance");
  if (manufacturerIdx === -1 || modelIdx === -1) {
    throw new Error('Expected a header row with "Manufacturer" and "Model" columns.');
  }

  const seen = new Map<string, ParsedImportRow>();
  for (const row of rows.slice(1)) {
    const manufacturer = (row[manufacturerIdx] ?? "").trim();
    const model = (row[modelIdx] ?? "").trim();
    if (!manufacturer || !model) continue;
    const version = versionIdx === -1 ? "" : (row[versionIdx] ?? "").trim();
    const colourway = colourwayIdx === -1 ? "" : (row[colourwayIdx] ?? "").trim();
    const rawDistance = distanceIdx === -1 ? "" : (row[distanceIdx] ?? "").trim();
    const distance_km = rawDistance ? Number(rawDistance) : undefined;

    const key = `${manufacturer.toLowerCase()}|${model.toLowerCase()}|${version.toLowerCase()}|${colourway.toLowerCase()}`;
    if (!seen.has(key)) {
      seen.set(key, { manufacturer, model, version, colourway, distance_km, key });
    }
  }
  if (seen.size === 0) {
    throw new Error("No valid manufacturer/model rows found.");
  }
  return [...seen.values()];
}

export function ImportShoesPanel({ onDone }: { onDone: () => void }) {
  const { isAdminAccount } = useAuth();
  const queryClient = useQueryClient();
  const [parsed, setParsed] = useState<ParsedImportRow[] | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ShoeImportResult | null>(null);

  const importMutation = useMutation({
    mutationFn: () =>
      importShoes(parsed!.map(({ manufacturer, model, version, colourway, distance_km }) => ({
        manufacturer,
        model,
        version,
        colourway,
        distance_km,
      }))),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ["shoes"] });
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
      setParsed(parseImportRows(parseCsv(await file.text())));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not read this file.");
    }
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10, marginBottom: 16, padding: 14, borderRadius: 10, border: "1px solid var(--line)", background: "var(--card)" }}>
      <input type="file" accept=".csv,text/csv" onChange={onFileSelected} style={{ fontSize: 13, color: "var(--ink2)" }} />
      <div style={{ fontSize: 12, color: "var(--ink3)", lineHeight: 1.5 }}>
        Expects a header row with Manufacturer, Model and (optionally) Version, Colourway and Distance_km columns -
        e.g. a Strava shoe-rotation export. A row with no version is imported without one.{" "}
        {isAdminAccount
          ? "As an admin, any shoe not already in the shared catalog is added to it as part of this import."
          : "Only shoes already in the shared catalog can be imported this way - rows that don't match are skipped (ask an admin to add them to the catalog first)."}
        {" "}Re-importing the same file is safe - a pair you already have is skipped, not duplicated.
      </div>
      {error && <div style={{ fontSize: 12, color: "#e0442e" }}>{error}</div>}
      {parsed && (
        <div>
          <div style={{ fontSize: 12.5, color: "var(--ink2)", marginBottom: 8 }}>
            {fileName}: {parsed.length} {parsed.length === 1 ? "pair" : "pairs"} found.
          </div>
          <div style={{ maxHeight: 180, overflowY: "auto", border: "1px solid var(--line)", borderRadius: 8, marginBottom: 10 }}>
            {parsed.map((p, i) => (
              <div
                key={p.key}
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
                  {p.manufacturer} {p.model}{p.version ? ` v${p.version}` : ""}
                  {p.colourway ? ` · ${p.colourway}` : ""}
                </span>
                <span className="mono" style={{ color: "var(--ink3)", flexShrink: 0 }}>
                  {p.distance_km != null ? `${p.distance_km} km` : "—"}
                </span>
              </div>
            ))}
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <button
              onClick={() => importMutation.mutate()}
              disabled={importMutation.isPending}
              style={{
                border: "none",
                borderRadius: 8,
                background: "var(--ember)",
                color: "#fff",
                fontSize: 13,
                fontWeight: 700,
                padding: "8px 16px",
                cursor: "pointer",
                opacity: importMutation.isPending ? 0.6 : 1,
              }}
            >
              {importMutation.isPending ? "Importing…" : `Import ${parsed.length} ${parsed.length === 1 ? "pair" : "pairs"}`}
            </button>
            <button onClick={() => setParsed(null)} style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 13 }}>
              Cancel
            </button>
          </div>
        </div>
      )}
      {result && (
        <div style={{ fontSize: 12.5, color: "#2fa66a", lineHeight: 1.5 }}>
          Imported {result.shoes_created} {result.shoes_created === 1 ? "pair" : "pairs"}
          {result.catalog_models_created > 0 || result.catalog_versions_created > 0
            ? ` (added ${result.catalog_models_created} new model${result.catalog_models_created === 1 ? "" : "s"} and ${result.catalog_versions_created} version${result.catalog_versions_created === 1 ? "" : "s"} to the catalog)`
            : ""}
          {result.skipped_already_in_gear > 0 ? `, ${result.skipped_already_in_gear} already in your gear` : ""}
          {result.skipped_no_catalog_match > 0
            ? `, ${result.skipped_no_catalog_match} skipped (not yet in the catalog)`
            : ""}
          .
        </div>
      )}
      <button onClick={onDone} style={{ alignSelf: "flex-start", border: "none", background: "none", color: "var(--ink3)", fontSize: 12.5, padding: 0, cursor: "pointer" }}>
        Close
      </button>
    </div>
  );
}
