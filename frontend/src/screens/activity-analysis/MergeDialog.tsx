import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { mergeActivity, unmergeActivity } from "../../api/activities";
import {
  ApiError,
  MERGE_ACTIVITY_FIELDS,
  MERGE_STREAM_FIELDS,
  type Activity,
  type MergeActivityField,
  type MergeNullPolicy,
  type MergeResult,
  type MergeStreamField,
} from "../../api/types";
import { MergeOverlayChart } from "./MergeOverlayChart";

function errorMessage(err: unknown): string {
  return err instanceof ApiError ? err.message : "Something went wrong. Try again.";
}

/** The bike default is power-only (the Kickr/pedal-offset case #501 was written for); other
 * sports start with nothing pre-ticked, since there's no one obviously-right channel to prefer. */
function defaultFields(sport: Activity["sport"]): string[] {
  return sport === "bike" ? ["power"] : [];
}

const FIELD_LABELS: Record<string, string> = {
  power: "Power",
  heartrate: "Heart rate",
  cadence: "Cadence",
  altitude: "Altitude",
  lat: "Latitude",
  lng: "Longitude",
  speed: "Speed",
  distance_km: "Distance",
  air_temp: "Air temperature",
  humidity: "Humidity",
  skin_temp: "Skin temperature",
  core_temp: "Core temperature",
  heat_strain: "Heat strain",
  name: "Name",
  avg_left_balance_pct: "L/R balance",
  start_weight_kg: "Start weight",
  end_weight_kg: "End weight",
  fluids_ml: "Fluids",
  bike_id: "Bike",
  shoe_id: "Shoe",
};

const MODAL_OVERLAY: React.CSSProperties = {
  position: "fixed",
  inset: 0,
  background: "rgba(0,0,0,0.4)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  zIndex: 100,
};

const SECONDARY_BUTTON: React.CSSProperties = {
  border: "1px solid var(--line)",
  borderRadius: 8,
  padding: "8px 16px",
  fontSize: 13,
  fontWeight: 600,
  background: "transparent",
  color: "var(--ink)",
  cursor: "pointer",
};

const PRIMARY_BUTTON: React.CSSProperties = {
  border: "none",
  borderRadius: 8,
  padding: "8px 16px",
  fontSize: 13,
  fontWeight: 600,
  background: "var(--ember)",
  color: "#fff",
  cursor: "pointer",
};

function FieldCheckbox({
  field,
  label,
  checked,
  onChange,
}: {
  field: string;
  label: string;
  checked: boolean;
  onChange: (field: string, checked: boolean) => void;
}) {
  return (
    <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 13, color: "var(--ink)", cursor: "pointer" }}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(field, e.target.checked)} />
      {label}
    </label>
  );
}

function SummaryRow({ label, before, after, unit }: { label: string; before: number | null; after: number | null; unit: string }) {
  const changed = before !== after;
  return (
    <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13 }}>
      <span style={{ color: "var(--ink2)" }}>{label}</span>
      <span style={{ fontFamily: "var(--mono, monospace)" }}>
        {before ?? "–"}
        {unit} {changed && <span style={{ color: "var(--ink3)" }}>→</span>}{" "}
        {changed && <span style={{ color: "var(--ember)", fontWeight: 600 }}>{after ?? "–"}{unit}</span>}
      </span>
    </div>
  );
}

/** Issue #501 phase 2. Preview → pick fields/null-policy/offset → merge → undo, for one
 * target/source pair. `target` keeps its id; `source`'s selected fields are copied in. */
export function MergeDialog({
  target,
  source,
  defaultHideSource = false,
  onClose,
}: {
  target: Activity;
  source: Activity;
  defaultHideSource?: boolean;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [fields, setFields] = useState<Set<string>>(new Set(defaultFields(target.sport)));
  const [nullPolicy, setNullPolicy] = useState<MergeNullPolicy>("keep_original");
  const [offsetOverride, setOffsetOverride] = useState<number | null>(null);
  const [hideSource, setHideSource] = useState(defaultHideSource);
  const [applied, setApplied] = useState<MergeResult | null>(null);

  const fieldsArray = useMemo(() => Array.from(fields).sort(), [fields]);

  const previewQuery = useQuery({
    queryKey: ["merge-preview", target.id, source.id, fieldsArray.join(","), nullPolicy, offsetOverride],
    queryFn: () =>
      mergeActivity(target.id, {
        source_id: source.id,
        fields: fieldsArray,
        null_policy: nullPolicy,
        offset_seconds: offsetOverride,
        preview: true,
      }),
    enabled: fieldsArray.length > 0 && !applied,
  });

  const mergeMutation = useMutation({
    mutationFn: () =>
      mergeActivity(target.id, {
        source_id: source.id,
        fields: fieldsArray,
        null_policy: nullPolicy,
        offset_seconds: offsetOverride,
        hide_source: hideSource,
        preview: false,
      }),
    onSuccess: (result) => {
      setApplied(result);
      queryClient.invalidateQueries({ queryKey: ["activity"] });
      queryClient.invalidateQueries({ queryKey: ["activities"] });
      queryClient.invalidateQueries({ queryKey: ["duplicate-candidates"] });
      queryClient.invalidateQueries({ queryKey: ["duplicates"] });
    },
  });

  const undoMutation = useMutation({
    mutationFn: () => unmergeActivity(target.id),
    onSuccess: () => {
      // Invalidating ["activity", ...] here also matches DuplicatesCard's own merge-source
      // query (same prefix), which remounts this dialog with fresh state before a lingering
      // "undone" message could render - so there's nothing to show it to. Close instead; the
      // card underneath already reflects the reverted state once its own queries refetch.
      queryClient.invalidateQueries({ queryKey: ["activity"] });
      queryClient.invalidateQueries({ queryKey: ["activities"] });
      queryClient.invalidateQueries({ queryKey: ["duplicate-candidates"] });
      queryClient.invalidateQueries({ queryKey: ["duplicates"] });
      onClose();
    },
  });

  function toggleField(field: string, checked: boolean) {
    setFields((prev) => {
      const next = new Set(prev);
      if (checked) next.add(field);
      else next.delete(field);
      return next;
    });
  }

  const result = applied ?? previewQuery.data;
  const effectiveOffset = offsetOverride ?? result?.offset_seconds ?? 0;

  return (
    <div style={MODAL_OVERLAY} onClick={onClose}>
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: "var(--card)",
          borderRadius: 14,
          width: 640,
          maxHeight: "90vh",
          display: "flex",
          flexDirection: "column",
        }}
      >
        <div style={{ padding: "24px 24px 0" }}>
          <h2 style={{ fontSize: 16, fontWeight: 700, margin: "0 0 4px" }}>Merge with another recording</h2>
          <p style={{ margin: 0, fontSize: 13, color: "var(--ink2)" }}>
            Copies the selected fields from <strong>{source.name}</strong> ({source.device || source.source || "unknown source"})
            into <strong>{target.name}</strong>. The target keeps its id, comments and tags - nothing is created or deleted.
          </p>
        </div>

        {/* minHeight: 0 lets this scroll instead of growing the flex column past maxHeight - the
            header and footer stay put either way, so the Merge/Undo button never needs the
            field-picker/chart's own height scrolled past to reach it. */}
        <div style={{ flex: 1, minHeight: 0, overflowY: "auto", padding: "16px 24px", display: "flex", flexDirection: "column", gap: 16 }}>
          {applied ? (
            <>
              <div style={{ fontSize: 13, color: "var(--ink2)" }}>Merged.</div>
              <div style={{ display: "flex", flexDirection: "column", gap: 6, padding: 12, background: "var(--elev)", borderRadius: 8 }}>
                <SummaryRow label="Avg power" before={applied.before.avg_power} after={applied.after.avg_power} unit="w" />
                <SummaryRow label="Normalized power" before={applied.before.norm_power} after={applied.after.norm_power} unit="w" />
                <SummaryRow label="TSS" before={applied.before.tss} after={applied.after.tss} unit="" />
              </div>
              {undoMutation.isError && <div style={{ fontSize: 13, color: "var(--danger, #c0392b)" }}>{errorMessage(undoMutation.error)}</div>}
            </>
          ) : (
            <>
              <div>
                <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink3)", marginBottom: 6 }}>Stream fields</div>
                <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 8 }}>
                  {MERGE_STREAM_FIELDS.map((f: MergeStreamField) => (
                    <FieldCheckbox key={f} field={f} label={FIELD_LABELS[f]} checked={fields.has(f)} onChange={toggleField} />
                  ))}
                </div>
              </div>

              <div>
                <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink3)", marginBottom: 6 }}>Activity-level fields</div>
                <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 8 }}>
                  {MERGE_ACTIVITY_FIELDS.map((f: MergeActivityField) => (
                    <FieldCheckbox key={f} field={f} label={FIELD_LABELS[f]} checked={fields.has(f)} onChange={toggleField} />
                  ))}
                </div>
              </div>

              <div style={{ display: "flex", gap: 20 }}>
                <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13 }}>
                  <input
                    type="radio"
                    checked={nullPolicy === "keep_original"}
                    onChange={() => setNullPolicy("keep_original")}
                  />
                  Keep original where the source has no data
                </label>
                <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13 }}>
                  <input type="radio" checked={nullPolicy === "overwrite"} onChange={() => setNullPolicy("overwrite")} />
                  Copy gaps too
                </label>
              </div>

              <div style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 13 }}>
                <span style={{ color: "var(--ink2)" }}>Offset</span>
                <button style={{ ...SECONDARY_BUTTON, padding: "4px 10px" }} onClick={() => setOffsetOverride(effectiveOffset - 1)}>
                  −1s
                </button>
                <span style={{ fontFamily: "var(--mono, monospace)", minWidth: 50, textAlign: "center" }}>{effectiveOffset}s</span>
                <button style={{ ...SECONDARY_BUTTON, padding: "4px 10px" }} onClick={() => setOffsetOverride(effectiveOffset + 1)}>
                  +1s
                </button>
                {offsetOverride != null && (
                  <button style={{ ...SECONDARY_BUTTON, padding: "4px 10px" }} onClick={() => setOffsetOverride(null)}>
                    Auto
                  </button>
                )}
                {result?.alignment_score != null && (
                  <span style={{ color: "var(--ink3)" }}>Alignment score: {result.alignment_score.toFixed(2)}</span>
                )}
              </div>

              <MergeOverlayChart target={target} source={source} offsetSeconds={effectiveOffset} />

              <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13, color: "var(--ink2)" }}>
                <input type="checkbox" checked={hideSource} onChange={(e) => setHideSource(e.target.checked)} />
                Hide the source as a duplicate of this activity
              </label>

              {fieldsArray.length === 0 && (
                <div style={{ fontSize: 13, color: "var(--ink3)" }}>Pick at least one field to preview the merge.</div>
              )}

              {result && (
                <div style={{ display: "flex", flexDirection: "column", gap: 6, padding: 12, background: "var(--elev)", borderRadius: 8 }}>
                  <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink3)" }}>Preview</div>
                  <SummaryRow label="Avg power" before={result.before.avg_power} after={result.after.avg_power} unit="w" />
                  <SummaryRow label="Normalized power" before={result.before.norm_power} after={result.after.norm_power} unit="w" />
                  <SummaryRow label="TSS" before={result.before.tss} after={result.after.tss} unit="" />
                </div>
              )}

              {mergeMutation.isError && <div style={{ fontSize: 13, color: "var(--danger, #c0392b)" }}>{errorMessage(mergeMutation.error)}</div>}
              {previewQuery.isError && <div style={{ fontSize: 13, color: "var(--danger, #c0392b)" }}>{errorMessage(previewQuery.error)}</div>}
            </>
          )}
        </div>

        <div style={{ padding: "16px 24px 24px", borderTop: "1px solid var(--line)", display: "flex", gap: 10, justifyContent: "flex-end" }}>
          {applied ? (
            <>
              <button style={SECONDARY_BUTTON} disabled={undoMutation.isPending} onClick={() => undoMutation.mutate()}>
                {undoMutation.isPending ? "Undoing…" : "Undo"}
              </button>
              <button style={PRIMARY_BUTTON} onClick={onClose}>
                Done
              </button>
            </>
          ) : (
            <>
              <button style={SECONDARY_BUTTON} onClick={onClose}>
                Cancel
              </button>
              <button
                style={{ ...PRIMARY_BUTTON, opacity: fieldsArray.length === 0 || mergeMutation.isPending ? 0.6 : 1 }}
                disabled={fieldsArray.length === 0 || mergeMutation.isPending}
                onClick={() => mergeMutation.mutate()}
              >
                {mergeMutation.isPending ? "Merging…" : "Merge"}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
