import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { detectStreamFlags, patchStream, unpatchStream } from "../../api/activities";
import { ApiError, type StreamComparisonField, type StreamFlagKind } from "../../api/types";

function errorMessage(err: unknown): string {
  return err instanceof ApiError ? err.message : "Something went wrong. Try again.";
}

const KIND_LABELS: Record<StreamFlagKind, string> = {
  contact_loss: "Contact loss",
  cadence_lock: "Cadence lock",
};

const SMALL_BUTTON: React.CSSProperties = {
  border: "1px solid var(--line)",
  borderRadius: 6,
  padding: "3px 8px",
  fontSize: 11,
  fontWeight: 600,
  background: "transparent",
  color: "var(--ink)",
  cursor: "pointer",
};

const SMALL_PRIMARY_BUTTON: React.CSSProperties = {
  ...SMALL_BUTTON,
  border: "none",
  background: "var(--ember)",
  color: "#fff",
};

function segmentKey(startT: number, endT: number): string {
  return `${startT}-${endT}`;
}

/** A row in the rendered list - either a freshly live-detected segment (patchId null) or a
 * patch this session already applied (patchId set). A row stays in the "applied" shape even
 * once the underlying issue it fixed no longer shows up in a fresh detectStreamFlags result -
 * otherwise a successful patch would make its own "Undo" button vanish the instant the fix
 * worked, which is exactly backwards: that's the one moment an athlete most plausibly wants
 * Undo available. */
interface Row {
  key: string;
  startT: number;
  endT: number;
  referenceConfirmed: boolean;
  appliedPatchId: string | null;
}

/** Issue #540 (phase 6): detect -> preview -> patch -> undo for one field's contact-loss/
 * cadence-lock runs, against the same reference source StreamComparisonPanel already has
 * selected. A new component (not grown into StreamComparisonPanel/SourcesTab) per the design
 * doc's own note - SourcesTab was already handling three fields' worth of source-switching UI
 * before this, and the real number of new interaction states (detect, times up to two detector
 * kinds, each segment independently preview -> patch -> undo) reads more clearly on its own.
 *
 * Undo only ever works within this same mount, the same convention MergeDialog's own undo
 * button already uses for merges - there's no GET endpoint listing past patches, and
 * unpatchStream's "pick the only active one" convention is scoped to the whole activity, not
 * just this field, so a patch_id from a previous mount can't be reliably rediscovered anyway. */
export function StreamFlagsCard({
  activityId,
  field,
  referenceSourceId,
  referenceLabel,
  onSegmentsChange,
}: {
  activityId: string;
  field: StreamComparisonField;
  referenceSourceId: string;
  referenceLabel: string;
  onSegmentsChange?: (segments: [number, number][]) => void;
}) {
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<StreamFlagKind>("contact_loss");
  const [detectEnabled, setDetectEnabled] = useState(false);
  const [previewingKey, setPreviewingKey] = useState<string | null>(null);
  // Session-local only - see this component's own docstring for why. Keyed by segmentKey so a
  // just-applied patch keeps rendering (see Row's own docstring) even once flagsQuery no longer
  // reports it.
  const [appliedPatches, setAppliedPatches] = useState<
    Record<string, { startT: number; endT: number; patchId: string }>
  >({});

  const flagsQuery = useQuery({
    queryKey: ["stream-flags", activityId, field, kind, referenceSourceId],
    queryFn: () => detectStreamFlags(activityId, field, kind, referenceSourceId),
    enabled: detectEnabled,
  });

  const rows: Row[] = useMemo(() => {
    const detected = (flagsQuery.data?.segments ?? [])
      .filter((s) => !(segmentKey(s.start_t, s.end_t) in appliedPatches))
      .map((s): Row => ({
        key: segmentKey(s.start_t, s.end_t),
        startT: s.start_t,
        endT: s.end_t,
        referenceConfirmed: s.reference_confirmed,
        appliedPatchId: null,
      }));
    const applied = Object.entries(appliedPatches).map(
      ([key, v]): Row => ({ key, startT: v.startT, endT: v.endT, referenceConfirmed: true, appliedPatchId: v.patchId }),
    );
    return [...detected, ...applied].sort((a, b) => a.startT - b.startT);
  }, [flagsQuery.data, appliedPatches]);

  useEffect(() => {
    onSegmentsChange?.(rows.map((r): [number, number] => [r.startT, r.endT]));
  }, [rows, onSegmentsChange]);

  const previewingRow = rows.find((r) => r.key === previewingKey) ?? null;
  const previewQuery = useQuery({
    queryKey: ["stream-patch-preview", activityId, field, referenceSourceId, previewingKey],
    queryFn: () =>
      patchStream(activityId, {
        field,
        from_source: referenceSourceId,
        segments: [[previewingRow!.startT, previewingRow!.endT]],
        preview: true,
      }),
    enabled: previewingRow != null,
  });

  function invalidateAfterWrite() {
    queryClient.invalidateQueries({ queryKey: ["stream-comparison", activityId, field] });
    queryClient.invalidateQueries({ queryKey: ["stream-flags", activityId, field] });
    queryClient.invalidateQueries({ queryKey: ["activity", activityId] });
  }

  const patchMutation = useMutation({
    mutationFn: (row: Row) =>
      patchStream(activityId, {
        field,
        from_source: referenceSourceId,
        segments: [[row.startT, row.endT]],
        preview: false,
      }),
    onSuccess: (result, row) => {
      setAppliedPatches((prev) => ({ ...prev, [row.key]: { startT: row.startT, endT: row.endT, patchId: result.patch_id! } }));
      setPreviewingKey(null);
      invalidateAfterWrite();
    },
  });

  const undoMutation = useMutation({
    mutationFn: (patchId: string) => unpatchStream(activityId, patchId),
    onSuccess: (_result, patchId) => {
      setAppliedPatches((prev) => {
        const next = { ...prev };
        for (const [key, v] of Object.entries(next)) {
          if (v.patchId === patchId) delete next[key];
        }
        return next;
      });
      invalidateAfterWrite();
    },
  });

  const showKindToggle = field === "heartrate";

  return (
    <div style={{ marginTop: 14, paddingTop: 14, borderTop: "1px solid var(--line)" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
        <div className="mono" style={{ fontSize: 11, color: "var(--ink3)", letterSpacing: "0.08em" }}>
          CONTACT-LOSS / CADENCE-LOCK
        </div>
        {showKindToggle &&
          (["contact_loss", "cadence_lock"] as StreamFlagKind[]).map((k) => (
            <button
              key={k}
              onClick={() => setKind(k)}
              style={{
                ...SMALL_BUTTON,
                background: kind === k ? "var(--elev)" : "transparent",
                color: kind === k ? "var(--ink)" : "var(--ink3)",
              }}
            >
              {KIND_LABELS[k]}
            </button>
          ))}
        <button style={SMALL_BUTTON} disabled={flagsQuery.isFetching} onClick={() => setDetectEnabled(true)}>
          {detectEnabled ? (flagsQuery.isFetching ? "Detecting…" : "Re-detect") : "Detect issues"}
        </button>
      </div>

      {detectEnabled && flagsQuery.isError && (
        <div style={{ fontSize: 12, color: "var(--danger, #c0392b)", marginTop: 10 }}>
          {errorMessage(flagsQuery.error)}
        </div>
      )}

      {detectEnabled && flagsQuery.data && rows.length === 0 && (
        <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 10 }}>No issues detected.</div>
      )}

      {rows.length > 0 && (
        <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 10 }}>
          {rows.map((row) => {
            const isPreviewing = previewingKey === row.key;
            return (
              <div
                key={row.key}
                style={{
                  display: "flex",
                  flexDirection: "column",
                  gap: 6,
                  padding: "8px 10px",
                  background: "var(--elev)",
                  borderRadius: 8,
                }}
              >
                <div style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 12 }}>
                  <span className="mono" style={{ color: "var(--ink)" }}>
                    t={row.startT}–{row.endT} ({row.endT - row.startT + 1}s)
                  </span>
                  {!row.referenceConfirmed && (
                    <span className="mono" style={{ fontSize: 10, color: "var(--ink3)" }}>
                      unconfirmed - no reference corroboration
                    </span>
                  )}
                  <div style={{ flex: 1 }} />
                  {row.appliedPatchId ? (
                    <>
                      <span style={{ color: "var(--ink3)" }}>Patched ✓</span>
                      <button
                        style={SMALL_BUTTON}
                        disabled={undoMutation.isPending}
                        onClick={() => {
                          if (window.confirm("Undo this patch? This restores the original values.")) {
                            undoMutation.mutate(row.appliedPatchId!);
                          }
                        }}
                      >
                        {undoMutation.isPending ? "Undoing…" : "Undo"}
                      </button>
                    </>
                  ) : isPreviewing ? null : (
                    <button style={SMALL_BUTTON} onClick={() => setPreviewingKey(row.key)}>
                      Patch from {referenceLabel}
                    </button>
                  )}
                </div>

                {isPreviewing && !row.appliedPatchId && (
                  <div style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 11 }}>
                    {previewQuery.isLoading && <span style={{ color: "var(--ink3)" }}>Previewing…</span>}
                    {previewQuery.isError && (
                      <span style={{ color: "var(--danger, #c0392b)" }}>{errorMessage(previewQuery.error)}</span>
                    )}
                    {previewQuery.data && (
                      <span className="mono" style={{ color: "var(--ink3)" }}>
                        copied {previewQuery.data.counts.copied}, nulled {previewQuery.data.counts.nulled}
                      </span>
                    )}
                    <div style={{ flex: 1 }} />
                    <button style={SMALL_BUTTON} onClick={() => setPreviewingKey(null)}>
                      Cancel
                    </button>
                    <button
                      style={SMALL_PRIMARY_BUTTON}
                      disabled={!previewQuery.data || patchMutation.isPending}
                      onClick={() => patchMutation.mutate(row)}
                    >
                      {patchMutation.isPending ? "Applying…" : "Apply patch"}
                    </button>
                  </div>
                )}
                {patchMutation.isError && patchMutation.variables?.key === row.key && (
                  <div style={{ fontSize: 11, color: "var(--danger, #c0392b)" }}>{errorMessage(patchMutation.error)}</div>
                )}
              </div>
            );
          })}
        </div>
      )}
      {undoMutation.isError && (
        <div style={{ fontSize: 11, color: "var(--danger, #c0392b)", marginTop: 6 }}>{errorMessage(undoMutation.error)}</div>
      )}
    </div>
  );
}
