import { Link, NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ProfileChip } from "./ProfileChip";
import { ThemeToggle } from "./ThemeToggle";
import { TrainingContextSwitcher } from "./TrainingContextSwitcher";

// Only screens with a real route get a link; the rest are later stages and stay inert
// labels rather than linking to a route that doesn't exist yet.
const NAV_ITEMS = [
  { label: "Dashboard", path: "/" },
  { label: "Activities", path: "/activities" },
  { label: "Best efforts", path: "/best-efforts" },
  { label: "Import", path: "/import" },
  { label: "Calendar", path: "/calendar" },
  { label: "Workouts", path: "/workouts" },
  { label: "Gear", path: "/gear" },
  { label: "Preferences", path: "/preferences" },
];

export function AppShell() {
  const { logout } = useAuth();

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

      <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
        <header
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "flex-end",
            gap: 8,
            padding: "12px 32px",
            borderBottom: "1px solid var(--line)",
            background: "var(--card)",
            position: "sticky",
            top: 0,
            zIndex: 10,
          }}
        >
          <Link
            to="/import"
            style={{
              padding: "8px 15px",
              borderRadius: 8,
              background: "var(--ember)",
              color: "#fff",
              fontSize: 13,
              fontWeight: 600,
              textDecoration: "none",
            }}
          >
            + Import activity
          </Link>
          {/* No notifications feature exists yet (no route, no API) - inert placeholder,
              same convention as NAV_ITEMS above for not-yet-built screens. */}
          <div
            aria-hidden
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              width: 38,
              height: 38,
              borderRadius: 9,
              border: "1px solid var(--line)",
              background: "var(--card)",
              color: "var(--ink3)",
              flexShrink: 0,
            }}
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6">
              <path d="M8 2a4 4 0 0 0-4 4c0 4-1.5 5-1.5 5h11S12 10 12 6a4 4 0 0 0-4-4Z" />
              <path d="M6.5 13.5a1.6 1.6 0 0 0 3 0" />
            </svg>
          </div>
          <div style={{ width: 1, height: 28, background: "var(--line)", margin: "0 3px" }} />
          <TrainingContextSwitcher />
          <ProfileChip />
        </header>

        <main style={{ flex: 1, padding: 32 }}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
