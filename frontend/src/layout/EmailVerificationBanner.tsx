import { useState } from "react";
import { resendVerification } from "../api/auth";
import { ApiError } from "../api/types";
import { useAuth } from "../auth/AuthContext";

type SendState = "idle" | "sending" | "sent";

/** Shown above the app until the signed-in athlete confirms their email (see
 * VerifyEmailScreen for the other half of this flow). Registration is a soft gate -
 * the account is fully usable while unverified - so this is informational, not a blocker. */
export function EmailVerificationBanner() {
  const { user } = useAuth();
  const [state, setState] = useState<SendState>("idle");
  const [message, setMessage] = useState<string | null>(null);

  if (!user || user.email_verified) {
    return null;
  }

  async function handleResend() {
    setState("sending");
    setMessage(null);
    try {
      await resendVerification();
      setState("sent");
    }
    catch (err) {
      setState("idle");
      setMessage(err instanceof ApiError ? err.message : "Couldn't send that - try again.");
    }
  }

  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        gap: 12,
        padding: "9px 32px",
        background: "var(--brand-bg)",
        color: "#fff",
        fontSize: 13,
      }}
    >
      <span>
        {state === "sent"
          ? "Verification email sent - check your inbox."
          : message ?? `Verify ${user.email} to secure your account.`}
      </span>
      {state !== "sent" && (
        <button
          onClick={handleResend}
          disabled={state === "sending"}
          style={{
            border: "1px solid rgba(255,255,255,0.4)",
            background: "none",
            borderRadius: 6,
            padding: "3px 10px",
            fontSize: 12,
            fontWeight: 700,
            color: "#fff",
            opacity: state === "sending" ? 0.6 : 1,
          }}
        >
          {state === "sending" ? "Sending…" : "Resend email"}
        </button>
      )}
    </div>
  );
}
