import { Link } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

/** Top-bar profile chip: avatar + name + FTP/LTHR, linking to Preferences. Reflects the
 * currently-active profile (see AuthContext), so it shows the coached athlete's own
 * thresholds while viewing as them, not the signed-in principal's. */
export function ProfileChip() {
  const { user } = useAuth();
  if (!user) {
    return null;
  }

  const metaParts: string[] = [];
  if (user.ftp != null) {
    metaParts.push(`FTP ${user.ftp}`);
  }
  if (user.lthr != null) {
    metaParts.push(`LTHR ${user.lthr}`);
  }

  return (
    <Link
      to="/preferences"
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        padding: "5px 12px 5px 5px",
        borderRadius: 11,
        border: "1px solid var(--line)",
        background: "var(--card)",
        textDecoration: "none",
      }}
    >
      <div style={{ width: 32, height: 32, borderRadius: "50%", background: "var(--canvas)", border: "1px solid var(--line)", flexShrink: 0 }} />
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)", lineHeight: 1.15, whiteSpace: "nowrap" }}>{user.name}</div>
        {metaParts.length > 0 && (
          <div style={{ fontSize: 11, color: "var(--ink3)", fontFamily: "'JetBrains Mono', monospace" }}>{metaParts.join(" · ")}</div>
        )}
      </div>
      <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="var(--ink3)" strokeWidth="1.4" style={{ marginLeft: 3, flexShrink: 0 }}>
        <circle cx="8" cy="8" r="2.1" />
        <path d="M8 1.6v1.8M8 12.6v1.8M1.6 8h1.8M12.6 8h1.8M3.5 3.5l1.3 1.3M11.2 11.2l1.3 1.3M12.5 3.5l-1.3 1.3M4.8 11.2l-1.3 1.3" />
      </svg>
    </Link>
  );
}
