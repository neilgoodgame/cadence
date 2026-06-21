import { useAuth } from "../auth/AuthContext";

export function DashboardScreen() {
  const { user } = useAuth();

  return (
    <div>
      <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em" }}>Welcome, {user?.name}.</h1>
      <p style={{ color: "var(--ink3)" }}>The dashboard is built in Stage 2.</p>
    </div>
  );
}
