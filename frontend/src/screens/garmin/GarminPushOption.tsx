/** The "also do this on Garmin Connect" checkbox shared by the workout editor and the scheduling views. */
export function GarminPushOption({
  checked,
  onChange,
  label,
  hint,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  hint?: string;
}) {
  return (
    <label style={{ display: "flex", gap: 8, alignItems: "flex-start", fontSize: 13, cursor: "pointer" }}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} style={{ marginTop: 3 }} />
      <span>
        <span style={{ fontWeight: 600 }}>{label}</span>
        {hint && <span style={{ display: "block", fontSize: 12, color: "var(--ink3)" }}>{hint}</span>}
      </span>
    </label>
  );
}
