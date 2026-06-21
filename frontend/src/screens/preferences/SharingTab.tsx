import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createShare, deleteShare, listShares, updateShare } from "../../api/shares";
import { ApiError } from "../../api/types";
import type { Share, ShareRole } from "../../api/types";

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
        <div style={{ fontWeight: 600, fontSize: 14 }}>{share.name}</div>
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
