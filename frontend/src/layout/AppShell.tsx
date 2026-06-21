import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ThemeToggle } from "./ThemeToggle";

// Only screens with a real route get a link; the rest are later stages and stay inert
// labels rather than linking to a route that doesn't exist yet.
const NAV_ITEMS = [
  { label: "Dashboard", path: "/" },
  { label: "Activities", path: "/activities" },
  { label: "Calendar", path: null },
  { label: "Workouts", path: null },
  { label: "Gear", path: null },
  { label: "Preferences", path: null },
];

export function AppShell() {
  const { user, logout } = useAuth();

  return (
    <div style={{ display: "flex", minHeight: "100vh" }}>
      <aside
        style={{
          width: 228,
          flexShrink: 0,
          borderRight: "1px solid var(--line)",
          padding: 24,
          display: "flex",
          flexDirection: "column",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 32 }}>
          <div style={{ width: 24, height: 24, borderRadius: 7, background: "var(--ember)" }} />
          <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: "-0.01em" }}>Cadence</span>
        </div>

        <nav style={{ display: "flex", flexDirection: "column", gap: 2, flex: 1 }}>
          {NAV_ITEMS.map((item) =>
            item.path ? (
              <NavLink
                key={item.label}
                to={item.path}
                end={item.path === "/"}
                style={({ isActive }) => ({
                  padding: "9px 12px",
                  borderRadius: 8,
                  fontSize: 14,
                  fontWeight: 600,
                  textDecoration: "none",
                  color: isActive ? "var(--ink)" : "var(--ink2)",
                  background: isActive ? "var(--elev)" : "transparent",
                })}
              >
                {item.label}
              </NavLink>
            ) : (
              <div
                key={item.label}
                style={{ padding: "9px 12px", borderRadius: 8, fontSize: 14, fontWeight: 600, color: "var(--ink3)" }}
              >
                {item.label}
              </div>
            ),
          )}
        </nav>

        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <ThemeToggle />
          <div style={{ fontSize: 13, color: "var(--ink2)" }}>{user?.name}</div>
          <button
            onClick={logout}
            style={{
              border: "1px solid var(--line)",
              background: "var(--card)",
              borderRadius: 8,
              padding: "8px 12px",
              fontSize: 13,
              fontWeight: 600,
              color: "var(--ink)",
            }}
          >
            Log out
          </button>
        </div>
      </aside>

      <main style={{ flex: 1, padding: 32 }}>
        <Outlet />
      </main>
    </div>
  );
}
