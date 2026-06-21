import { useState } from "react";
import { applyTheme, getStoredTheme, type Theme } from "../styles/theme";

const OPTIONS: { value: Theme; label: string }[] = [
  { value: "teal", label: "Teal" },
  { value: "violet", label: "Violet" },
  { value: "day", label: "Day" },
];

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(getStoredTheme);

  function select(value: Theme) {
    applyTheme(value);
    setTheme(value);
  }

  return (
    <div
      style={{
        display: "flex",
        gap: 3,
        background: "var(--card)",
        border: "1px solid var(--line)",
        borderRadius: 9,
        padding: 3,
      }}
    >
      {OPTIONS.map((option) => (
        <button
          key={option.value}
          onClick={() => select(option.value)}
          style={{
            border: "none",
            borderRadius: 7,
            padding: "6px 12px",
            fontSize: 12,
            fontWeight: 600,
            background: theme === option.value ? "var(--elev)" : "transparent",
            color: theme === option.value ? "var(--ink)" : "var(--ink3)",
          }}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
