export function StatCell({
  label,
  value,
  unit,
  subtitle,
  color,
}: {
  label: string;
  value: string | number;
  unit?: string;
  subtitle?: string;
  color?: string;
}) {
  return (
    <div>
      <div
        className="mono"
        style={{ fontSize: 11, letterSpacing: "0.06em", color: "var(--ink3)", fontWeight: 600, marginBottom: 6 }}
      >
        {label.toUpperCase()}
      </div>
      <div style={{ display: "flex", alignItems: "baseline", gap: 6 }}>
        <span className="mono" style={{ fontSize: 25, fontWeight: 600, color: color ?? "var(--ink)" }}>
          {value}
        </span>
        {unit && <span style={{ fontSize: 13, fontWeight: 500, color: "var(--ink2)" }}>{unit}</span>}
      </div>
      {subtitle && <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>{subtitle}</div>}
    </div>
  );
}
