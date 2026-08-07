import { useState } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ShoeCatalogTab } from "./admin/ShoeCatalogTab";

// Grows to "users" | "grants" | "audit" as those tabs land - kept a single-member union
// for now rather than shipping placeholder screens for tabs that don't do anything yet.
type TabKey = "shoe_catalog";

const TABS: { key: TabKey; label: string }[] = [{ key: "shoe_catalog", label: "Shoe catalog" }];

export function AdminScreen() {
  const { user, isAdminAccount } = useAuth();
  const [tab, setTab] = useState<TabKey>("shoe_catalog");

  if (!isAdminAccount) {
    return <Navigate to="/" replace />;
  }
  if (!user) {
    return null;
  }

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", gap: 9, marginBottom: 4 }}>
        <h1 style={{ fontSize: 21, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>Admin</h1>
        <span
          style={{
            fontFamily: "monospace",
            fontSize: 11,
            fontWeight: 600,
            padding: "3px 9px",
            borderRadius: 6,
            background: "rgba(236,74,38,0.13)",
            color: "var(--ember)",
          }}
        >
          RESTRICTED
        </span>
      </div>
      <div style={{ fontSize: 13, color: "var(--ink3)", marginBottom: 28 }}>
        Signed in as {user.name} · is_admin
      </div>

      <div style={{ display: "flex", gap: 32 }}>
        <nav style={{ width: 178, flexShrink: 0, display: "flex", flexDirection: "column", gap: 2 }}>
          {TABS.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              style={{
                textAlign: "left",
                border: "none",
                padding: "9px 12px",
                borderRadius: 8,
                fontSize: 14,
                fontWeight: 600,
                background: tab === t.key ? "var(--elev)" : "transparent",
                color: tab === t.key ? "var(--ink)" : "var(--ink2)",
              }}
            >
              {t.label}
            </button>
          ))}
        </nav>

        <div style={{ flex: 1 }}>{tab === "shoe_catalog" && <ShoeCatalogTab />}</div>
      </div>
    </div>
  );
}
