import { useAuth } from "../../auth/AuthContext";
import type { OverlapHandling } from "../../api/types";

const inputStyle: React.CSSProperties = {
  width: "100%",
  padding: "10px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 14,
  color: "var(--ink)",
};

const labelStyle: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 };

const OVERLAP_LABELS: Record<OverlapHandling, string> = {
  off: "Off",
  flag: "Flag only",
  mark: "Mark the duplicate",
  merge: "Merge (coming soon — currently behaves like Flag)",
};

/** Lets this one import override the athlete's own `overlap_handling` default (see
 * Preferences → Profile → Import) for same-ride-twice detection. Shared between ImportScreen
 * (single file + zip) and GarminSyncCard, since both ultimately resolve `on_overlap` the same
 * way server-side. */
export function OverlapOverrideField({
  value,
  onChange,
}: {
  value: OverlapHandling | "";
  onChange: (next: OverlapHandling | "") => void;
}) {
  const { user } = useAuth();
  const defaultHandling = user?.overlap_handling ?? "off";

  return (
    <label>
      <div style={labelStyle}>Overlapping activities</div>
      <select style={inputStyle} value={value} onChange={(e) => onChange(e.target.value as OverlapHandling | "")}>
        <option value="">Use my default ({OVERLAP_LABELS[defaultHandling]})</option>
        <option value="off">{OVERLAP_LABELS.off}</option>
        <option value="flag">{OVERLAP_LABELS.flag}</option>
        <option value="mark">{OVERLAP_LABELS.mark}</option>
        <option value="merge">{OVERLAP_LABELS.merge}</option>
      </select>
    </label>
  );
}
