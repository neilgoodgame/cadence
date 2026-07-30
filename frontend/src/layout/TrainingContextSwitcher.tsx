import { useState, type CSSProperties } from "react";
import { useQuery } from "@tanstack/react-query";
import { getContexts } from "../api/auth";
import { useAuth } from "../auth/AuthContext";

/**
 * Lets a coach switch between viewing their own training and any athlete they coach.
 * Hidden entirely for non-coaching accounts. `isCoachAccount` (not `user.is_coach`) gates
 * this - see AuthContext for why that distinction matters while a switch is active.
 */
export function TrainingContextSwitcher() {
  const { user, activeAthleteId, isCoachAccount, switchToAthlete, switchToSelf } = useAuth();
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);

  const contextsQuery = useQuery({
    queryKey: ["contexts"],
    queryFn: getContexts,
    enabled: isCoachAccount,
  });
  const coaching = contextsQuery.data?.coaching ?? [];

  if (!isCoachAccount || coaching.length === 0) {
    return null;
  }

  const runSwitch = async (fn: () => Promise<void>) => {
    setOpen(false);
    setSwitching(true);
    try {
      await fn();
    }
    finally {
      setSwitching(false);
    }
  };

  const rowStyle = (active: boolean): CSSProperties => ({
    padding: "8px 10px",
    borderRadius: 7,
    cursor: "pointer",
    fontSize: 13,
    fontWeight: active ? 700 : 500,
    color: "var(--ink)",
    background: active ? "var(--elev)" : "transparent",
  });

  return (
    <div style={{ position: "relative" }}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        disabled={switching}
        style={{
          width: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 8,
          padding: "8px 12px",
          borderRadius: 8,
          border: "1px solid var(--line)",
          background: "var(--card)",
          fontSize: 13,
          fontWeight: 600,
          color: "var(--ink2)",
          cursor: switching ? "default" : "pointer",
        }}
      >
        <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {activeAthleteId ? `Coaching · ${user?.name}` : "My training"}
        </span>
        <span style={{ fontSize: 10, flexShrink: 0 }}>▾</span>
      </button>

      {open && (
        <>
          <div onClick={() => setOpen(false)} style={{ position: "fixed", inset: 0, zIndex: 30 }} />
          <div
            style={{
              position: "absolute",
              bottom: "calc(100% + 6px)",
              left: 0,
              zIndex: 31,
              width: 220,
              maxHeight: 260,
              overflowY: "auto",
              background: "var(--card)",
              // var(--card) is translucent by design (every card in this app is, over the
              // gradient canvas) - fine for page content, but this dropdown sits directly
              // over other interactive sidebar controls (the theme toggle), so it needs a
              // blur or their content visibly (if faintly) shows through it.
              backdropFilter: "blur(14px)",
              WebkitBackdropFilter: "blur(14px)",
              border: "1px solid var(--line)",
              borderRadius: 10,
              boxShadow: "0 16px 40px rgba(0,0,0,0.3)",
              padding: 6,
            }}
          >
            <div onClick={() => runSwitch(switchToSelf)} style={rowStyle(activeAthleteId === null)}>
              My training
            </div>
            {coaching.map((c) => (
              <div key={c.user_id} onClick={() => runSwitch(() => switchToAthlete(c.user_id))} style={rowStyle(activeAthleteId === c.user_id)}>
                {c.name}
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
