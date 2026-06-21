import { useState, type FormEvent } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/types";
import { ThemeToggle } from "../layout/ThemeToggle";

const inputStyle: React.CSSProperties = {
  width: "100%",
  padding: "12px 14px",
  borderRadius: 10,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 14,
  fontFamily: "Hanken Grotesk, system-ui, sans-serif",
  color: "var(--ink)",
};

export function LoginScreen() {
  const { user, isLoading, login, register } = useAuth();
  const navigate = useNavigate();
  const [isSignUp, setIsSignUp] = useState(false);
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  if (!isLoading && user) {
    return <Navigate to="/" replace />;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      if (isSignUp) {
        await register(name, email, password);
      }
      else {
        await login(email, password);
      }
      navigate("/", { replace: true });
    }
    catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong. Try again.");
    }
    finally {
      setIsSubmitting(false);
    }
  }

  return (
    <div style={{ display: "flex", minHeight: "100vh" }}>
      <div
        style={{
          flexBasis: "46%",
          maxWidth: 680,
          background: "var(--brand-bg)",
          color: "#fff",
          padding: 48,
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          backgroundImage: `linear-gradient(var(--grid-line) 1px, transparent 1px), linear-gradient(90deg, var(--grid-line) 1px, transparent 1px)`,
          backgroundSize: "32px 32px",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 32 }}>
          <div style={{ width: 30, height: 30, borderRadius: 8, background: "var(--ember)" }} />
          <span style={{ fontSize: 17, fontWeight: 700, letterSpacing: "-0.01em" }}>Cadence</span>
        </div>
        <div
          className="mono"
          style={{ fontSize: 11, letterSpacing: "0.18em", color: "var(--ember)", fontWeight: 600, marginBottom: 14 }}
        >
          TRAINING INTELLIGENCE
        </div>
        <h1 style={{ fontSize: 38, fontWeight: 800, letterSpacing: "-0.025em", lineHeight: 1.1, margin: "0 0 16px" }}>
          Every watt, split and heartbeat in one place.
        </h1>
        <p style={{ fontSize: 15, lineHeight: 1.55, opacity: 0.7, margin: 0 }}>
          Upload activities from any device, see the analysis that matters, and plan what comes next.
        </p>
      </div>

      <div style={{ flex: 1, position: "relative", display: "flex", alignItems: "center", justifyContent: "center" }}>
        <div style={{ position: "absolute", top: 24, right: 28 }}>
          <ThemeToggle />
        </div>

        <div style={{ width: 392 }}>
          <div style={{ display: "flex", borderBottom: "1px solid var(--line)", marginBottom: 24 }}>
            {(["Sign in", "Create account"] as const).map((label, index) => {
              const tabIsSignUp = index === 1;
              const active = isSignUp === tabIsSignUp;
              return (
                <button
                  key={label}
                  onClick={() => setIsSignUp(tabIsSignUp)}
                  style={{
                    background: "none",
                    border: "none",
                    borderBottom: active ? "2px solid var(--ember)" : "2px solid transparent",
                    marginBottom: -1,
                    marginRight: 24,
                    padding: "0 0 12px",
                    fontSize: 15,
                    fontWeight: 700,
                    letterSpacing: "-0.01em",
                    color: active ? "var(--ink)" : "var(--ink3)",
                  }}
                >
                  {label}
                </button>
              );
            })}
          </div>

          <h2 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: "0 0 6px" }}>
            {isSignUp ? "Create your account" : "Welcome back"}
          </h2>
          <p style={{ fontSize: 14, color: "var(--ink3)", margin: "0 0 24px" }}>
            {isSignUp
              ? "Start turning raw activity files into training insight."
              : "Sign in to pick up where your last session left off."}
          </p>

          <form onSubmit={handleSubmit} style={{ display: "flex", flexDirection: "column", gap: 14 }}>
            {isSignUp && (
              <label>
                <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Full name</div>
                <input
                  style={inputStyle}
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  placeholder="Neil Goodgame"
                  required
                />
              </label>
            )}
            <label>
              <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Email</div>
              <input
                style={inputStyle}
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="you@cadence.cc"
                required
              />
            </label>
            <label>
              <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Password</div>
              <input
                style={inputStyle}
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder="••••••••••"
                minLength={isSignUp ? 10 : undefined}
                required
              />
            </label>

            {error && <div style={{ fontSize: 13, color: "var(--ember)" }}>{error}</div>}

            <button
              type="submit"
              disabled={isSubmitting}
              style={{
                marginTop: 4,
                padding: 13,
                borderRadius: 10,
                border: "none",
                background: "var(--ember)",
                color: "#fff",
                fontSize: 14,
                fontWeight: 700,
                opacity: isSubmitting ? 0.7 : 1,
              }}
            >
              {isSignUp ? "Create account" : "Sign in"}
            </button>
          </form>

          <p style={{ fontSize: 13, color: "var(--ink3)", textAlign: "center", margin: "22px 0 0" }}>
            {isSignUp ? "Already training with us? " : "New to Cadence? "}
            <span
              onClick={() => setIsSignUp(!isSignUp)}
              style={{ color: "var(--ember)", fontWeight: 600, cursor: "pointer" }}
            >
              {isSignUp ? "Sign in" : "Create an account"}
            </span>
          </p>
        </div>
      </div>
    </div>
  );
}
