import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createComment, deleteComment, listComments } from "../../api/activities";
import { useAuth } from "../../auth/AuthContext";
import type { ActivityComment } from "../../api/types";

const ROLE_LABEL: Record<ActivityComment["author_role"], string> = {
  athlete: "Athlete",
  coach: "Coach",
  viewer: "Viewer",
};
const ROLE_COLOR: Record<ActivityComment["author_role"], string> = {
  athlete: "var(--ink3)",
  coach: "var(--ember)",
  viewer: "var(--ink3)",
};

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts[1]?.[0] ?? "")).toUpperCase();
}

function timeAgo(iso: string): string {
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return "just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

function Comment({ comment, canDelete, onDelete }: { comment: ActivityComment; canDelete: boolean; onDelete: () => void }) {
  return (
    <div style={{ display: "flex", gap: 12 }}>
      <div
        className="mono"
        style={{
          flexShrink: 0,
          width: 32,
          height: 32,
          borderRadius: "50%",
          background: "var(--elev)",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontSize: 12,
          fontWeight: 700,
          color: "var(--ink2)",
        }}
      >
        {initials(comment.author_name)}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span style={{ fontSize: 13.5, fontWeight: 700 }}>{comment.author_name}</span>
          <span
            className="mono"
            style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.04em", color: ROLE_COLOR[comment.author_role] }}
          >
            {ROLE_LABEL[comment.author_role].toUpperCase()}
          </span>
          <span className="mono" style={{ fontSize: 11, color: "var(--ink3)", marginLeft: "auto" }}>
            {timeAgo(comment.created)}
          </span>
          {canDelete && (
            <button
              onClick={onDelete}
              title="Delete comment"
              style={{ border: "none", background: "none", color: "var(--ink3)", cursor: "pointer", padding: 0, fontSize: 13, lineHeight: 1 }}
            >
              ✕
            </button>
          )}
        </div>
        <div style={{ fontSize: 13.5, color: "var(--ink2)", lineHeight: 1.5, marginTop: 4 }}>{comment.text}</div>
      </div>
    </div>
  );
}

function ReplyComposer({
  onCancel,
  onSubmit,
  isPending,
}: {
  onCancel: () => void;
  onSubmit: (text: string) => void;
  isPending: boolean;
}) {
  const [draft, setDraft] = useState("");

  function submit() {
    const text = draft.trim();
    if (!text || isPending) return;
    onSubmit(text);
    setDraft("");
  }

  return (
    <div style={{ display: "flex", alignItems: "flex-start", gap: 8, marginTop: 8 }}>
      <textarea
        autoFocus
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            submit();
          }
          if (e.key === "Escape") onCancel();
        }}
        placeholder="Write a reply…"
        style={{
          flex: 1,
          minWidth: 0,
          resize: "none",
          height: 36,
          border: "1px solid var(--line)",
          borderRadius: 8,
          padding: "8px 11px",
          background: "var(--elev)",
          color: "var(--ink)",
          fontFamily: "inherit",
          fontSize: 13,
          outline: "none",
        }}
      />
      <button
        onClick={submit}
        disabled={!draft.trim() || isPending}
        style={{
          flexShrink: 0,
          border: "none",
          borderRadius: 8,
          padding: "8px 14px",
          background: "var(--ember)",
          color: "#fff",
          fontSize: 12.5,
          fontWeight: 600,
          cursor: "pointer",
          opacity: !draft.trim() || isPending ? 0.5 : 1,
        }}
      >
        {isPending ? "Posting…" : "Reply"}
      </button>
      <button
        onClick={onCancel}
        style={{ flexShrink: 0, border: "none", background: "none", color: "var(--ink3)", fontSize: 12.5, cursor: "pointer", padding: "8px 4px" }}
      >
        Cancel
      </button>
    </div>
  );
}

function Thread({
  root,
  replies,
  selfId,
  onDelete,
  onReply,
  replyPending,
}: {
  root: ActivityComment;
  replies: ActivityComment[];
  selfId: string | null;
  onDelete: (commentId: string) => void;
  onReply: (text: string) => void;
  replyPending: boolean;
}) {
  const [replying, setReplying] = useState(false);

  return (
    <div>
      <Comment comment={root} canDelete={root.author_id === selfId} onDelete={() => onDelete(root.id)} />
      {replies.length > 0 && (
        <div style={{ display: "flex", flexDirection: "column", gap: 12, marginTop: 12, marginLeft: 44, paddingLeft: 12, borderLeft: "2px solid var(--line)" }}>
          {replies.map((reply) => (
            <Comment key={reply.id} comment={reply} canDelete={reply.author_id === selfId} onDelete={() => onDelete(reply.id)} />
          ))}
        </div>
      )}
      <div style={{ marginLeft: 44 }}>
        {replying ? (
          <ReplyComposer
            isPending={replyPending}
            onCancel={() => setReplying(false)}
            onSubmit={(text) => {
              onReply(text);
              setReplying(false);
            }}
          />
        ) : (
          <button
            onClick={() => setReplying(true)}
            style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12, fontWeight: 600, cursor: "pointer", padding: "6px 0 0" }}
          >
            Reply
          </button>
        )}
      </div>
    </div>
  );
}

export function CommentsSection({ activityId }: { activityId: string }) {
  const { selfId } = useAuth();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState("");

  const { data } = useQuery({ queryKey: ["activity-comments", activityId], queryFn: () => listComments(activityId) });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["activity-comments", activityId] });

  const postMutation = useMutation({
    mutationFn: (text: string) => createComment(activityId, text),
    onSuccess: () => {
      setDraft("");
      invalidate();
    },
  });

  const replyMutation = useMutation({
    mutationFn: ({ text, parentId }: { text: string; parentId: string }) => createComment(activityId, text, parentId),
    onSuccess: invalidate,
  });

  const deleteMutation = useMutation({
    mutationFn: (commentId: string) => deleteComment(activityId, commentId),
    onSuccess: invalidate,
  });

  const comments = data?.data ?? [];
  const roots = comments.filter((c) => !c.parent_id);
  const repliesByParent = new Map<string, ActivityComment[]>();
  for (const c of comments) {
    if (!c.parent_id) continue;
    const list = repliesByParent.get(c.parent_id) ?? [];
    list.push(c);
    repliesByParent.set(c.parent_id, list);
  }

  function submit() {
    const text = draft.trim();
    if (!text || postMutation.isPending) return;
    postMutation.mutate(text);
  }

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "20px 24px" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 9, marginBottom: 16 }}>
        <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: "-0.01em" }}>Comments</span>
        <span className="mono" style={{ fontSize: 11, color: "var(--ink3)" }}>
          {comments.length}
        </span>
      </div>

      {roots.length > 0 && (
        <div style={{ display: "flex", flexDirection: "column", gap: 20, marginBottom: 20 }}>
          {roots.map((root) => (
            <Thread
              key={root.id}
              root={root}
              replies={repliesByParent.get(root.id) ?? []}
              selfId={selfId}
              onDelete={(commentId) => deleteMutation.mutate(commentId)}
              onReply={(text) => replyMutation.mutate({ text, parentId: root.id })}
              replyPending={replyMutation.isPending}
            />
          ))}
        </div>
      )}

      <div style={{ borderTop: roots.length > 0 ? "1px solid var(--line)" : "none", paddingTop: roots.length > 0 ? 16 : 0 }}>
        <div style={{ display: "flex", alignItems: "flex-start", gap: 10 }}>
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                submit();
              }
            }}
            placeholder="Add a comment…"
            style={{
              flex: 1,
              minWidth: 0,
              resize: "none",
              height: 42,
              border: "1px solid var(--line)",
              borderRadius: 9,
              padding: "11px 13px",
              background: "var(--elev)",
              color: "var(--ink)",
              fontFamily: "inherit",
              fontSize: 13.5,
              outline: "none",
            }}
          />
          <button
            onClick={submit}
            disabled={!draft.trim() || postMutation.isPending}
            style={{
              flexShrink: 0,
              display: "flex",
              alignItems: "center",
              gap: 6,
              border: "none",
              borderRadius: 9,
              padding: "11px 16px",
              background: "var(--ember)",
              color: "#fff",
              fontSize: 13,
              fontWeight: 600,
              cursor: "pointer",
              opacity: !draft.trim() || postMutation.isPending ? 0.5 : 1,
            }}
          >
            {postMutation.isPending ? "Posting…" : "Post"}
          </button>
        </div>
      </div>
    </div>
  );
}
