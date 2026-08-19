import { useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { verifyEmail } from "../api/auth";
import { ApiError } from "../api/types";
import { useAuth } from "../auth/AuthContext";

type Status = "verifying" | "success" | "error";

/** Public route (see App.tsx) - reached by clicking the emailed link, which may not carry
 * this browser's session (a different device, or one where the access token has since
 * expired). Updates the signed-in user's cached profile in place when it does. */
export function VerifyEmailScreen() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get("token");
  const { user, setUser } = useAuth();
  const [status, setStatus] = useState<Status>(() => (token ? "verifying" : "error"));
  const [errorMessage, setErrorMessage] = useState<string | null>(() =>
    token ? null : "This verification link is missing its token.",
  );
  // StrictMode double-invokes effects in dev - the token is single-use, so a second call
  // would fail with a spurious "invalid or expired" even though the first one succeeded.
  const attempted = useRef(false);

  useEffect(() => {
    if (attempted.current || !token) return;
    attempted.current = true;

    verifyEmail(token)
      .then(() => {
        setStatus("success");
        if (user && !user.email_verified) {
          setUser({ ...user, email_verified: true });
        }
      })
      .catch((err) => {
        setStatus("error");
        setErrorMessage(err instanceof ApiError ? err.message : "Something went wrong. Try again.");
      });
    // Only run once, on mount - see the attempted ref above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div style={{ display: "flex", minHeight: "100vh", alignItems: "center", justifyContent: "center" }}>
      <div style={{ width: 392, textAlign: "center" }}>
        <div
          style={{
            width: 30, height: 30, borderRadius: 8, background: "var(--ember)", margin: "0 auto 24px",
          }}
        />

        {status === "verifying" && <p style={{ fontSize: 14, color: "var(--ink3)" }}>Verifying your email address…</p>}

        {status === "success" && (
          <>
            <h1 style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.02em", margin: "0 0 8px" }}>Email verified</h1>
            <p style={{ fontSize: 14, color: "var(--ink3)", margin: "0 0 24px" }}>
              Your address is confirmed - you're all set.
            </p>
            <Link
              to={user ? "/" : "/login"}
              style={{
                display: "inline-block", padding: "12px 20px", borderRadius: 10, background: "var(--ember)",
                color: "#fff", fontSize: 14, fontWeight: 700, textDecoration: "none",
              }}
            >
              {user ? "Continue to Cadence" : "Sign in"}
            </Link>
          </>
        )}

        {status === "error" && (
          <>
            <h1 style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.02em", margin: "0 0 8px" }}>
              Link invalid or expired
            </h1>
            <p style={{ fontSize: 14, color: "var(--ink3)", margin: "0 0 24px" }}>
              {errorMessage} Sign in and request a fresh link from your account.
            </p>
            <Link to="/login" style={{ fontSize: 14, fontWeight: 700, color: "var(--ember)" }}>
              Go to sign in
            </Link>
          </>
        )}
      </div>
    </div>
  );
}
