import type { CSSProperties } from "react";

export const tableStyle: CSSProperties = { width: "100%", borderCollapse: "collapse", fontSize: 13 };

export const thStyle: CSSProperties = {
  textAlign: "left",
  padding: "8px 12px",
  fontSize: 11,
  fontWeight: 600,
  color: "var(--ink3)",
  borderBottom: "1px solid var(--line)",
  whiteSpace: "nowrap",
};

export const tdStyle: CSSProperties = { padding: "10px 12px", borderBottom: "1px solid var(--line)", color: "var(--ink2)" };
