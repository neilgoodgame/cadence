import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateAthlete } from "../../api/athletes";
import { ApiError } from "../../api/types";
import type { ThresholdEditResult, ThresholdFieldName } from "../../api/types";
import { ActivityNameLink } from "../../components/ActivityNameLink";
import { GarminPushOption } from "../garmin/GarminPushOption";
import { dateStatus, previewMessageKind, todayISODate } from "./thresholdCorrection";

const inputStyle: React.CSSProperties = {
  width: "100%",
  padding: "10px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 14,
  color: "var(--ink)",
};

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : "Something went wrong. Try again.";
}

/** A deliberate, backdated correction to a manual threshold value - a separate flow from
 * ProfileTab's plain "Save changes" (which always dates today and never previews) since
 * backdating has consequences (correction-only outcome, re-rating past activities) worth
 * surfacing before committing. See issue #493 - the ledger row is always written; it only
 * becomes the live value when it isn't superseded by a later row and isn't already stale. */
export function ThresholdCorrectionModal({
  field,
  label,
  athleteId,
  currentValue,
  thresholdWindowDays,
  onSaved,
  onClose,
}: {
  field: ThresholdFieldName;
  label: string;
  athleteId: string;
  currentValue: number | string | undefined;
  thresholdWindowDays: number;
  onSaved: (field: ThresholdFieldName, value: number | string) => void;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const [value, setValue] = useState(currentValue != null ? String(currentValue) : "");
  const [effectiveFrom, setEffectiveFrom] = useState(todayISODate());
  const [rerateActivities, setRerateActivities] = useState(true);
  const [previewResult, setPreviewResult] = useState<ThresholdEditResult | null>(null);

  const { isPastDate, isFutureDate, outsideWindow } = dateStatus(effectiveFrom, thresholdWindowDays);

  const patch = () => ({
    [field]: field === "threshold_pace" ? value : Number(value),
    effective_from: effectiveFrom,
    rerate_activities: rerateActivities,
  });

  const previewMutation = useMutation({
    mutationFn: () => updateAthlete(athleteId, { ...patch(), preview: true }),
    onSuccess: (data) => setPreviewResult(data.threshold_edit_results?.[field] ?? null),
  });

  const saveMutation = useMutation({
    mutationFn: () => updateAthlete(athleteId, { ...patch(), preview: false }),
    onSuccess: (data) => {
      onSaved(field, data[field] as number | string);
      qc.invalidateQueries({ queryKey: ["thresholds", athleteId] });
      qc.invalidateQueries({ queryKey: ["threshold-history", athleteId, field] });
      qc.invalidateQueries({ queryKey: ["zones", athleteId] });
      qc.invalidateQueries({ queryKey: ["activities"] });
      onClose();
    },
  });

  const valueValid = field === "threshold_pace" ? /^\d{1,2}:\d{2}$/.test(value) : value !== "" && !Number.isNaN(Number(value));
  const disabled = !valueValid || isFutureDate || previewMutation.isPending || saveMutation.isPending;
  // The backend also rejects a date before the athlete's first activity of this sport - not
  // something this modal can know in advance, so that one's error only shows up once a save or
  // preview is actually attempted (mutationError below), not proactively like isFutureDate.
  const mutationError = previewMutation.isError || saveMutation.isError
    ? errorMessage(previewMutation.error ?? saveMutation.error)
    : null;

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ background: "var(--card)", borderRadius: 14, padding: 24, width: 460, display: "flex", flexDirection: "column", gap: 16 }}
      >
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Backdate a {label} correction</h2>
        <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0, lineHeight: 1.5 }}>
          Records the real value for a date in the past, instead of today. It only becomes your
          current {label} if nothing later already took effect and it isn't already outside your
          threshold window - otherwise it's kept as a correction, used to re-rate the activities
          it covers, but not shown as current anywhere.
        </p>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>{label}</div>
          <input
            type={field === "threshold_pace" ? "text" : "number"}
            className="mono"
            style={inputStyle}
            placeholder={field === "threshold_pace" ? "4:00" : undefined}
            value={value}
            onChange={(e) => {
              setValue(e.target.value);
              setPreviewResult(null);
            }}
          />
        </label>

        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Effective from</div>
          <input
            type="date"
            max={todayISODate()}
            value={effectiveFrom}
            onChange={(e) => {
              setEffectiveFrom(e.target.value);
              setPreviewResult(null);
            }}
            style={inputStyle}
          />
          {isFutureDate && (
            <div style={{ fontSize: 12, color: "#e0442e", marginTop: 6 }}>Must not be in the future.</div>
          )}
          {!isFutureDate && mutationError && (
            <div style={{ fontSize: 12, color: "#e0442e", marginTop: 6 }}>{mutationError}</div>
          )}
          {!isFutureDate && !mutationError && outsideWindow && (
            <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 6 }}>
              This is outside your {thresholdWindowDays}-day window, so it won't change your current {label}. It
              will still re-rate any affected rides if ticked below.
            </div>
          )}
        </label>

        {isPastDate && (
          <GarminPushOption
            checked={rerateActivities}
            onChange={(checked) => {
              setRerateActivities(checked);
              setPreviewResult(null);
            }}
            label="Re-rate affected activities"
            hint="Recomputes intensity and TSS for every activity on or after this date using the new value."
          />
        )}

        {previewResult && (
          <div style={{ fontSize: 13, color: "var(--ink2)", background: "var(--elev)", borderRadius: 8, padding: 12, lineHeight: 1.5 }}>
            {(() => {
              switch (previewMessageKind(previewResult, outsideWindow)) {
                case "no-change":
                  return <span>No change - this already matches what was in effect on {effectiveFrom}.</span>;
                case "correction-outside-window":
                  return (
                    <span>
                      Saved as a correction for this date. Your current {label} is unchanged, since it's outside
                      {" "}
                      your {thresholdWindowDays}-day window.
                    </span>
                  );
                case "correction-later-row":
                  return (
                    <span>
                      Saved as a correction for this date. A later entry is still current, so your current {label}
                      {" "}
                      is unchanged.
                    </span>
                  );
                default:
                  return <span>This will become your current {label}.</span>;
              }
            })()}
            {!!previewResult.would_rerate?.length && (
              <div style={{ marginTop: 10 }}>
                <div style={{ fontWeight: 600, marginBottom: 6 }}>
                  {previewResult.would_rerate.length} activit{previewResult.would_rerate.length === 1 ? "y" : "ies"} would be re-rated
                </div>
                <div style={{ display: "flex", flexDirection: "column", gap: 4, maxHeight: 160, overflowY: "auto" }}>
                  {previewResult.would_rerate.map((diff) => (
                    <div key={diff.activity_id} className="mono" style={{ fontSize: 12, display: "flex", gap: 8, justifyContent: "space-between" }}>
                      <ActivityNameLink id={diff.activity_id} />
                      <span style={{ color: "var(--ink3)" }}>
                        {diff.old_intensity?.toFixed(2) ?? "—"}→{diff.new_intensity?.toFixed(2) ?? "—"} IF &middot;{" "}
                        {diff.old_tss ?? "—"}→{diff.new_tss ?? "—"} TSS
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8 }}>
          <button
            onClick={onClose}
            style={{ border: "1px solid var(--line)", borderRadius: 8, background: "transparent", color: "var(--ink2)", fontSize: 13, fontWeight: 600, padding: "8px 16px" }}
          >
            Cancel
          </button>
          <button
            onClick={() => previewMutation.mutate()}
            disabled={disabled}
            style={{ border: "1px solid var(--line)", borderRadius: 8, background: "transparent", color: "var(--ink)", fontSize: 13, fontWeight: 600, padding: "8px 16px" }}
          >
            {previewMutation.isPending ? "Previewing…" : "Preview"}
          </button>
          <button
            onClick={() => saveMutation.mutate()}
            disabled={disabled}
            style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px" }}
          >
            {saveMutation.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}
