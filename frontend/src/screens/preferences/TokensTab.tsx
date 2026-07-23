import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createAccessToken, listAccessTokens, revokeAccessToken, rotateAccessToken } from "../../api/tokens";
import type { AccessTokenWithSecret } from "../../api/types";

const ALL_SCOPES = ["activities:read", "activities:write", "workouts:write", "gear:write"];

function RevealedSecret({ token, onDismiss }: { token: AccessTokenWithSecret; onDismiss: () => void }) {
  return (
    <div style={{ border: "1px solid #2fa66a", borderRadius: 10, padding: 16, background: "var(--elev)" }}>
      <div style={{ fontWeight: 700, fontSize: 14, color: "#2fa66a", marginBottom: 8 }}>
        Token created — copy it now, you won't see it again
      </div>
      <div
        className="mono"
        style={{ padding: "10px 12px", borderRadius: 8, background: "var(--card)", border: "1px solid var(--line)", fontSize: 13, wordBreak: "break-all" }}
      >
        {token.secret}
      </div>
      <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
        <button onClick={() => navigator.clipboard.writeText(token.secret)} style={{ fontSize: 12, fontWeight: 600, padding: "6px 12px", borderRadius: 6, border: "1px solid var(--line)", background: "var(--card)", color: "var(--ink)" }}>
          Copy
        </button>
        <button onClick={onDismiss} style={{ fontSize: 12, fontWeight: 600, padding: "6px 12px", borderRadius: 6, border: "none", background: "none", color: "var(--ink3)" }}>
          Done
        </button>
      </div>
    </div>
  );
}

export function TokensTab() {
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: ["tokens"], queryFn: listAccessTokens });
  const [name, setName] = useState("");
  const [scopes, setScopes] = useState<string[]>([]);
  const [revealed, setRevealed] = useState<AccessTokenWithSecret | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["tokens"] });

  const canGenerate = !!name.trim() && scopes.length > 0;

  const createMutation = useMutation({
    mutationFn: () => createAccessToken(name, scopes, null),
    onSuccess: (token) => {
      setRevealed(token);
      setName("");
      setScopes([]);
      setCreateError(null);
      invalidate();
    },
    onError: (err) => {
      setCreateError(err instanceof Error ? err.message : "Could not create token.");
    },
  });
  const rotateMutation = useMutation({
    mutationFn: (id: string) => rotateAccessToken(id),
    onSuccess: (token) => {
      setRevealed(token);
      invalidate();
    },
  });
  const revokeMutation = useMutation({
    mutationFn: (id: string) => revokeAccessToken(id),
    onSuccess: invalidate,
  });

  function toggleScope(scope: string) {
    setScopes((prev) => (prev.includes(scope) ? prev.filter((s) => s !== scope) : [...prev, scope]));
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 24, maxWidth: 520 }}>
      <p style={{ fontSize: 13, color: "var(--ink3)", margin: 0 }}>
        Personal access tokens let you script against the API as yourself. Scope each one to only what it needs.
      </p>

      {revealed && <RevealedSecret token={revealed} onDismiss={() => setRevealed(null)} />}

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Generate token</h3>
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Name, e.g. Home server sync"
            style={{ padding: "8px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)" }}
          />
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 6 }}>
            {ALL_SCOPES.map((scope) => (
              <label key={scope} style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 13 }}>
                <input type="checkbox" checked={scopes.includes(scope)} onChange={() => toggleScope(scope)} />
                {scope}
              </label>
            ))}
          </div>
          {createError && (
            <div style={{ fontSize: 13, color: "#e0442e" }}>{createError}</div>
          )}
          <button
            onClick={() => createMutation.mutate()}
            disabled={!canGenerate || createMutation.isPending}
            style={{
              alignSelf: "flex-start",
              padding: "8px 16px",
              borderRadius: 8,
              border: "none",
              background: "var(--ember)",
              color: "#fff",
              fontSize: 13,
              fontWeight: 700,
              opacity: canGenerate && !createMutation.isPending ? 1 : 0.4,
              cursor: canGenerate && !createMutation.isPending ? "pointer" : "not-allowed",
            }}
          >
            {createMutation.isPending ? "Generating…" : "Generate"}
          </button>
        </div>
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Active tokens</h3>
        {(data?.data.length ?? 0) === 0 ? (
          <div style={{ fontSize: 13, color: "var(--ink3)" }}>No tokens yet.</div>
        ) : (
          data!.data.map((token) => (
            <div key={token.id} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "10px 0", borderTop: "1px solid var(--line)" }}>
              <div>
                <div style={{ fontWeight: 600, fontSize: 14 }}>{token.name}</div>
                <div className="mono" style={{ fontSize: 12, color: "var(--ink3)" }}>
                  {token.prefix}… · {token.scopes.join(", ")}
                </div>
              </div>
              <div style={{ display: "flex", gap: 10 }}>
                <button onClick={() => rotateMutation.mutate(token.id)} style={{ fontSize: 12, fontWeight: 600, border: "none", background: "none", color: "var(--ink2)" }}>
                  Rotate
                </button>
                <button onClick={() => revokeMutation.mutate(token.id)} style={{ fontSize: 12, fontWeight: 600, border: "none", background: "none", color: "#e0442e" }}>
                  Revoke
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
