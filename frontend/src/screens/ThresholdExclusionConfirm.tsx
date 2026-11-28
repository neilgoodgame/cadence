import type { ThresholdExclusionLedgerRow, ThresholdFieldName } from "../api/types";
import { describeRow, FIELD_LABELS, formatValue, type PendingChange } from "./thresholdExclusions";

const MAX_ROWS = 6;

function RowList({ title, rows, field, activityId }: { title: string; rows: ThresholdExclusionLedgerRow[]; field: ThresholdFieldName; activityId: string }) {
  if (rows.length === 0) {
    return null;
  }
  return (
    <div>
      <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)" }}>{title}</div>
      <ul style={{ margin: "4px 0 0", paddingLeft: 18, fontSize: 12, color: "var(--ink2)", lineHeight: 1.5 }}>
        {rows.slice(0, MAX_ROWS).map((row) => (
          <li key={`${row.kind}-${row.source_activity_id ?? "none"}-${row.effective_from}-${row.current_from}`}>
            {describeRow(field, row, activityId)}
          </li>
        ))}
        {rows.length > MAX_ROWS && <li>and {rows.length - MAX_ROWS} more</li>}
      </ul>
    </div>
  );
}

const BUTTON_STYLE: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  borderRadius: 7,
  padding: "6px 12px",
  cursor: "pointer",
  border: "1px solid var(--line)",
  background: "var(--card)",
  color: "var(--ink)",
};

/** The preview diff for a pending change, with Confirm / Cancel. */
export function ThresholdExclusionConfirm({
  change,
  applying,
  onConfirm,
  onCancel,
}: {
  change: PendingChange;
  applying: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const { field, effect, exclude, activityId } = change;
  const label = FIELD_LABELS[field];
  const valueChanges = effect.value_before !== effect.value_after;
  const historyChanges = effect.rows_removed.length > 0 || effect.rows_added.length > 0;

  return (
    <div
      role="dialog"
      aria-label={exclude ? `Exclude from ${label}` : `Include in ${label} again`}
      style={{ background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 8, padding: "12px 14px", display: "flex", flexDirection: "column", gap: 10 }}
    >
      <div style={{ fontSize: 13, fontWeight: 700, color: "var(--ink)" }}>
        {exclude ? `Exclude this activity from ${label} detection?` : `Count this activity for ${label} again?`}
      </div>
      <div style={{ fontSize: 13, color: "var(--ink2)" }}>
        {valueChanges
          ? `Your current ${label} would change from ${formatValue(field, effect.value_before)} to ${formatValue(field, effect.value_after)}.`
          : `Your current ${label} stays ${formatValue(field, effect.value_before)}.`}
        {!historyChanges && " Your history doesn't change."}
      </div>
      <RowList title="Removed from your history" rows={effect.rows_removed} field={field} activityId={activityId} />
      <RowList title="Added to your history" rows={effect.rows_added} field={field} activityId={activityId} />
      <div style={{ fontSize: 12, color: "var(--ink3)" }}>
        {exclude
          ? "The activity still counts as training. "
          : ""}
        Later activities' intensity and TSS are re-rated, which can take a couple of minutes for an older activity.
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        <button type="button" onClick={onConfirm} disabled={applying} style={{ ...BUTTON_STYLE, background: "var(--ember)", borderColor: "var(--ember)", color: "#fff" }}>
          {applying ? "Applying…" : exclude ? "Exclude" : "Include again"}
        </button>
        <button type="button" onClick={onCancel} disabled={applying} style={BUTTON_STYLE}>
          Cancel
        </button>
      </div>
    </div>
  );
}
