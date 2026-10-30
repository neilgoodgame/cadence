import type { CSSProperties, Key, ReactNode } from "react";

export interface SegmentedOption<T> {
  key: Key;
  value: T;
  label: ReactNode;
  disabled?: boolean;
  title?: string;
}

/** The track-and-pill toggle used throughout the app for sport/period/view/metric filters
 * (see design_handoff_flat_fills) - a flat bordered track holding one or more pills, the
 * active one lifted with a flat var(--card) fill and a soft shadow. One shared
 * implementation so every toggle in the app looks and behaves identically, instead of each
 * screen's own slightly-different copy of the same handful of CSS properties. */
export function SegmentedControl<T>({
  options,
  value,
  onChange,
  fill = true,
  size = "md",
  style,
}: {
  options: SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
  /** true (default): segments share the track's width equally. false: segments hug their
   * own label's width (e.g. a long list of period windows). */
  fill?: boolean;
  /** "md" (default): the standard toggle size. "sm": the compact size for longer option
   * lists or tighter layouts. */
  size?: "sm" | "md";
  style?: CSSProperties;
}) {
  const padding = size === "sm" ? "5px 11px" : "6px 12px";
  const fontSize = size === "sm" ? 11.5 : 12.5;

  return (
    <div
      style={{
        display: "flex",
        gap: 3,
        background: "var(--canvas-solid)",
        border: "1px solid var(--line)",
        borderRadius: 9,
        padding: 3,
        width: fill ? undefined : "fit-content",
        ...style,
      }}
    >
      {options.map((opt) => {
        const active = opt.value === value;
        return (
          <div
            key={opt.key}
            onClick={() => !opt.disabled && onChange(opt.value)}
            title={opt.title}
            style={{
              flex: fill ? 1 : "none",
              textAlign: "center",
              padding,
              fontSize,
              fontWeight: 600,
              borderRadius: 7,
              cursor: opt.disabled ? "default" : "pointer",
              opacity: opt.disabled ? 0.4 : 1,
              whiteSpace: "nowrap",
              color: active ? "var(--ink)" : "var(--ink3)",
              background: active ? "var(--card)" : "transparent",
              boxShadow: active ? "0 1px 2px rgba(0,0,0,0.14)" : "none",
            }}
          >
            {opt.label}
          </div>
        );
      })}
    </div>
  );
}
