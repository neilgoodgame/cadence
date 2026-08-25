import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createShare, createVirtualCoach, deleteShare, listShares, updateShare } from "../../api/shares";
import { ApiError } from "../../api/types";
import type { Share, ShareRole, VirtualCoachCreated } from "../../api/types";
import { RevealedSecret } from "./TokensTab";

const VIRTUAL_COACH_SCOPES = ["activities:read", "activities:write", "workouts:write", "calendar:write"];

function ShareRow({ share }: { share: Share }) {
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["shares"] });
  const roleMutation = useMutation({
    mutationFn: (role: ShareRole) => updateShare(share.id, role),
    onSuccess: invalidate,
  });
  const revokeMutation = useMutation({
    mutationFn: () => deleteShare(share.id),
    onSuccess: invalidate,
  });

  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "10px 0", borderTop: "1px solid var(--line)" }}>
      <div>
        <div style={{ fontWeight: 600, fontSize: 14, display: "flex", alignItems: "center", gap: 6 }}>
          {share.name}
          {share.is_virtual && (
            <span
              style={{ fontSize: 10, fontWeight: 700, color: "var(--ink3)", border: "1px solid var(--line)", borderRadius: 4, padding: "1px 5px" }}
            >
              AI
            </span>
          )}
        </div>
        <div style={{ fontSize: 12, color: "var(--ink3)" }}>
          {share.handle ?? ""} · {share.status === "pending" ? "Pending" : `Since ${share.since}`}
        </div>
      </div>
      <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
        {(["viewer", "coach"] as ShareRole[]).map((role) => (
          <button
            key={role}
            onClick={() => roleMutation.mutate(role)}
            style={{
              border: "1px solid var(--line)",
              borderRadius: 6,
              padding: "4px 10px",
              fontSize: 12,
              fontWeight: 600,
              background: share.role === role ? "var(--elev)" : "transparent",
              color: share.role === role ? "var(--ink)" : "var(--ink3)",
            }}
          >
            {role === "viewer" ? "Viewer" : "Coach"}
          </button>
        ))}
        <button onClick={() => revokeMutation.mutate()} style={{ border: "none", background: "none", color: "#e0442e", fontSize: 12, fontWeight: 600 }}>
          Revoke
        </button>
      </div>
    </div>
  );
}

export function SharingTab() {
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: ["shares"], queryFn: listShares });
  const [invitee, setInvitee] = useState("");
  const [inviteRole, setInviteRole] = useState<ShareRole>("viewer");
  const [error, setError] = useState<string | null>(null);

  const inviteMutation = useMutation({
    mutationFn: () => createShare(invitee, inviteRole),
    onSuccess: () => {
      setInvitee("");
      setError(null);
      queryClient.invalidateQueries({ queryKey: ["shares"] });
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : "Could not send the invite."),
  });

  const [virtualCoachName, setVirtualCoachName] = useState("");
  const [virtualCoachError, setVirtualCoachError] = useState<string | null>(null);
  const [revealedVirtualCoach, setRevealedVirtualCoach] = useState<VirtualCoachCreated | null>(null);

  const virtualCoachMutation = useMutation({
    mutationFn: () => createVirtualCoach(virtualCoachName, VIRTUAL_COACH_SCOPES),
    onSuccess: (created) => {
      setRevealedVirtualCoach(created);
      setVirtualCoachName("");
      setVirtualCoachError(null);
      queryClient.invalidateQueries({ queryKey: ["shares"] });
    },
    onError: (err) => setVirtualCoachError(err instanceof ApiError ? err.message : "Could not create the virtual coach."),
  });

  const shares = data?.data ?? [];
  const coaches = shares.filter((s) => s.role === "coach");

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 28, maxWidth: 520 }}>
      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>My coaches</h3>
        {coaches.length === 0 ? (
          <div style={{ fontSize: 13, color: "var(--ink3)" }}>No one has coach access yet.</div>
        ) : (
          coaches.map((share) => <ShareRow key={share.id} share={share} />)
        )}
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Invite someone</h3>
        <div style={{ display: "flex", gap: 8 }}>
          <input
            value={invitee}
            onChange={(e) => setInvitee(e.target.value)}
            placeholder="Email or @handle"
            style={{ flex: 1, padding: "8px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)" }}
          />
          <select
            value={inviteRole}
            onChange={(e) => setInviteRole(e.target.value as ShareRole)}
            style={{ borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)" }}
          >
            <option value="viewer">Viewer</option>
            <option value="coach">Coach</option>
          </select>
          <button
            onClick={() => inviteMutation.mutate()}
            disabled={!invitee.trim() || inviteMutation.isPending}
            style={{ padding: "8px 16px", borderRadius: 8, border: "none", background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700 }}
          >
            Invite
          </button>
        </div>
        {error && <div style={{ fontSize: 13, color: "#e0442e", marginTop: 8 }}>{error}</div>}
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>Add an AI coach</h3>
        <p style={{ fontSize: 13, color: "var(--ink3)", margin: "0 0 12px" }}>
          Creates a coach account for an AI assistant like Claude.ai to connect to over MCP, separate from your own
          login. It can view your training data, schedule or create workouts, and comment on activities, the same as
          a human coach.
        </p>
        {revealedVirtualCoach ? (
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            <div style={{ border: "1px solid #2fa66a", borderRadius: 10, padding: 16, background: "var(--elev)" }}>
              <div style={{ fontWeight: 700, fontSize: 14, color: "#2fa66a", marginBottom: 8 }}>
                {revealedVirtualCoach.share.name} created — copy this now, you won't see it again
              </div>
              <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 4 }}>
                Login — use this if an MCP client's setup asks you to sign in (e.g. Claude.ai's OAuth step)
              </div>
              <div
                className="mono"
                style={{ padding: "10px 12px", borderRadius: 8, background: "var(--card)", border: "1px solid var(--line)", fontSize: 13, wordBreak: "break-all" }}
              >
                {revealedVirtualCoach.email}
                <br />
                {revealedVirtualCoach.password}
              </div>
              <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
                <button
                  onClick={() => navigator.clipboard.writeText(`${revealedVirtualCoach.email}\n${revealedVirtualCoach.password}`)}
                  style={{ fontSize: 12, fontWeight: 600, padding: "6px 12px", borderRadius: 6, border: "1px solid var(--line)", background: "var(--card)", color: "var(--ink)" }}
                >
                  Copy
                </button>
              </div>
            </div>
            <div>
              <RevealedSecret token={revealedVirtualCoach.token} onDismiss={() => setRevealedVirtualCoach(null)} />
              <p style={{ fontSize: 12, color: "var(--ink3)", margin: "6px 0 0" }}>
                Use this instead if the client takes a bearer token/API key directly, with no sign-in step.
              </p>
            </div>
          </div>
        ) : (
          <div style={{ display: "flex", gap: 8 }}>
            <input
              value={virtualCoachName}
              onChange={(e) => setVirtualCoachName(e.target.value)}
              placeholder="Name, e.g. Claude.ai"
              style={{ flex: 1, padding: "8px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)" }}
            />
            <button
              onClick={() => virtualCoachMutation.mutate()}
              disabled={!virtualCoachName.trim() || virtualCoachMutation.isPending}
              style={{ padding: "8px 16px", borderRadius: 8, border: "none", background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700 }}
            >
              {virtualCoachMutation.isPending ? "Creating…" : "Create"}
            </button>
          </div>
        )}
        {virtualCoachError && <div style={{ fontSize: 13, color: "#e0442e", marginTop: 8 }}>{virtualCoachError}</div>}
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 12px" }}>People with access</h3>
        {shares.length === 0 ? (
          <div style={{ fontSize: 13, color: "var(--ink3)" }}>You haven't shared your training with anyone yet.</div>
        ) : (
          shares.map((share) => <ShareRow key={share.id} share={share} />)
        )}
      </div>
    </div>
  );
}
