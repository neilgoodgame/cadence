import { Card } from "../../components/Card";
import type { CoachedAthlete } from "../../api/types";

function complianceColor(compliance: number): string {
  if (compliance >= 0.9) return "#2fa66a";
  if (compliance >= 0.8) return "#f0a02e";
  return "#e0442e";
}

function AthleteCard({ athlete }: { athlete: CoachedAthlete }) {
  return (
    <Card>
      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <div
          style={{
            width: 32,
            height: 32,
            borderRadius: "50%",
            background: "var(--role-coach)",
            color: "#fff",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            fontSize: 13,
            fontWeight: 700,
          }}
        >
          {athlete.name.charAt(0)}
        </div>
        <div>
          <div style={{ fontWeight: 600, fontSize: 14 }}>{athlete.name}</div>
          <div className="mono" style={{ fontSize: 11, color: complianceColor(athlete.compliance) }}>
            {Math.round(athlete.compliance * 100)}% compliance · TSB {athlete.tsb}
          </div>
        </div>
      </div>
    </Card>
  );
}

/**
 * Viewing a coached athlete's own activities needs a delegated JWT scoped to their
 * athlete_id (POST /v1/auth/jwt) - GET /v1/activities has no athlete_id query param, it
 * always scopes to the caller. Out of scope for the Dashboard; this section sticks to
 * what GET /v1/me/contexts already supplies directly (name/compliance/TSB) rather than
 * faking a per-athlete activity feed with the coach's own data.
 */
export function CoachingSection({ athletes }: { athletes: CoachedAthlete[] }) {
  if (athletes.length === 0) {
    return null;
  }
  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 14 }}>
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Your athletes</h2>
        <a href="/coach" style={{ fontSize: 13, color: "var(--ember)", fontWeight: 600 }}>
          Open roster →
        </a>
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 13 }}>
        {athletes.map((athlete) => (
          <AthleteCard key={athlete.user_id} athlete={athlete} />
        ))}
      </div>
    </div>
  );
}
