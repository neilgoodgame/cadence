import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { ApiError } from "../api/types";

/** Chrome for the login-free public pages (currently shared races): lives outside
 * RequireAuth/AppShell, and handles the loading and not-shared states. */
export function PublicPage({
  noun,
  isLoading,
  error,
  children,
}: {
  /** e.g. "race" - used in the not-found message. */
  noun: string;
  isLoading: boolean;
  error: Error | null;
  /** Rendered once loaded - null while loading or on error. */
  children: ReactNode;
}) {
  let body: ReactNode;
  if (isLoading) {
    body = <div style={{ color: "var(--ink3)" }}>Loading…</div>;
  } else if (error || !children) {
    const notFound = error instanceof ApiError && error.status === 404;
    body = (
      <div style={{ color: "var(--ink2)", fontSize: 15 }}>
        {notFound ? `This ${noun} doesn't exist or isn't shared publicly.` : `Couldn't load this ${noun}.`}
      </div>
    );
  } else {
    body = children;
  }

  return (
    <div style={{ minHeight: "100vh", color: "var(--ink)" }}>
      <header
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "14px 32px",
          borderBottom: "1px solid var(--line)",
        }}
      >
        <span style={{ fontWeight: 800, fontSize: 18 }}>Cadence</span>
        <Link to="/" style={{ fontSize: 13, fontWeight: 600, color: "var(--ember)", textDecoration: "none" }}>
          Open Cadence
        </Link>
      </header>
      <main style={{ maxWidth: 1280, margin: "0 auto", padding: 32 }}>{body}</main>
    </div>
  );
}
