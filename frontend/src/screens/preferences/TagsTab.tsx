import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createTag, deleteTag, listTags, renameTag } from "../../api/activities";
import { ApiError } from "../../api/types";
import type { Tag } from "../../api/types";
import { tagColor, tagRgba } from "../../lib/tagColors";

function DeleteTagDialog({ tag, onClose }: { tag: Tag; onClose: () => void }) {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () => deleteTag(tag.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tags"] });
      onClose();
    },
  });

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ background: "var(--card)", borderRadius: 14, padding: 24, width: 420, display: "flex", flexDirection: "column", gap: 16 }}
      >
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Delete "{tag.name}"?</h2>
        <p style={{ fontSize: 13, color: "var(--ink2)", margin: 0, lineHeight: 1.5 }}>
          {tag.count === 1
            ? "This will remove the tag from the 1 activity that carries it."
            : `This will remove the tag from all ${tag.count} activities that carry it.`}{" "}
          This cannot be undone.
        </p>
        {mutation.isError && (
          <div style={{ fontSize: 13, color: "#e0442e" }}>
            {mutation.error instanceof ApiError ? mutation.error.message : "Something went wrong - the tag was not deleted."}
          </div>
        )}
        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8 }}>
          <button
            onClick={onClose}
            style={{ border: "1px solid var(--line)", borderRadius: 8, background: "transparent", color: "var(--ink2)", fontSize: 13, fontWeight: 600, padding: "8px 16px" }}
          >
            Cancel
          </button>
          <button
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
            style={{ border: "none", borderRadius: 8, background: "#e0442e", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px" }}
          >
            {mutation.isPending ? "Deleting…" : "Delete tag"}
          </button>
        </div>
      </div>
    </div>
  );
}

function TagRow({ tag, allTags }: { tag: Tag; allTags: Tag[] }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(tag.name);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [mergeNotice, setMergeNotice] = useState<string | null>(null);

  const renameMutation = useMutation({
    mutationFn: (name: string) => renameTag(tag.id, name),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ["tags"] });
      setEditing(false);
      setMergeNotice(result.id !== tag.id ? `Merged into the existing "${result.name}" tag.` : null);
    },
  });

  const color = tagColor(tag.name);
  const trimmed = draft.trim();
  const collidesWithAnother = allTags.some((t) => t.id !== tag.id && t.name.toLowerCase() === trimmed.toLowerCase());

  function startEditing() {
    setDraft(tag.name);
    setEditing(true);
    setMergeNotice(null);
    renameMutation.reset();
  }

  function save() {
    if (!trimmed || trimmed === tag.name) {
      setEditing(false);
      return;
    }
    renameMutation.mutate(trimmed);
  }

  return (
    <div style={{ padding: "12px 0", borderTop: "1px solid var(--line)" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <span style={{ width: 10, height: 10, borderRadius: "50%", background: color, flexShrink: 0 }} />

        {editing ? (
          <input
            autoFocus
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") save();
              if (e.key === "Escape") setEditing(false);
            }}
            style={{
              flex: 1,
              padding: "6px 10px",
              borderRadius: 6,
              border: "1px solid var(--line)",
              background: "var(--elev)",
              color: "var(--ink)",
              fontSize: 14,
              fontWeight: 600,
            }}
          />
        ) : (
          <div style={{ flex: 1, minWidth: 0 }}>
            <span style={{ fontSize: 14, fontWeight: 600 }}>{tag.name}</span>
            {tag.origin === "auto" && (
              <span style={{ marginLeft: 8, fontSize: 11, fontWeight: 600, color: "var(--ink3)" }}>auto</span>
            )}
          </div>
        )}

        <span
          style={{
            fontFamily: "'JetBrains Mono', monospace",
            fontSize: 11,
            fontWeight: 600,
            padding: "2px 7px",
            borderRadius: 20,
            background: tagRgba(tag.name, 0.12),
            color: "var(--ink2)",
            flexShrink: 0,
          }}
        >
          {tag.count}
        </span>

        {editing ? (
          <div style={{ display: "flex", gap: 6, flexShrink: 0 }}>
            <button
              onClick={save}
              disabled={!trimmed || renameMutation.isPending}
              style={{ border: "none", borderRadius: 6, background: "var(--ember)", color: "#fff", fontSize: 12, fontWeight: 700, padding: "6px 12px" }}
            >
              {renameMutation.isPending ? "Saving…" : "Save"}
            </button>
            <button
              onClick={() => setEditing(false)}
              style={{ border: "1px solid var(--line)", borderRadius: 6, background: "transparent", color: "var(--ink2)", fontSize: 12, fontWeight: 600, padding: "6px 12px" }}
            >
              Cancel
            </button>
          </div>
        ) : (
          <div style={{ display: "flex", gap: 14, flexShrink: 0 }}>
            <button onClick={startEditing} style={{ border: "none", background: "none", color: "var(--ink2)", fontSize: 12, fontWeight: 600 }}>
              Rename
            </button>
            <button onClick={() => setConfirmingDelete(true)} style={{ border: "none", background: "none", color: "#e0442e", fontSize: 12, fontWeight: 600 }}>
              Delete
            </button>
          </div>
        )}
      </div>

      {editing && collidesWithAnother && (
        <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 6, marginLeft: 22 }}>
          An existing tag already has this name - saving will merge "{tag.name}" into it.
        </div>
      )}
      {renameMutation.isError && (
        <div style={{ fontSize: 12, color: "#e0442e", marginTop: 6, marginLeft: 22 }}>
          {renameMutation.error instanceof ApiError ? renameMutation.error.message : "Could not rename the tag."}
        </div>
      )}
      {!editing && mergeNotice && (
        <div style={{ fontSize: 12, color: "#2fa66a", marginTop: 6, marginLeft: 22 }}>{mergeNotice}</div>
      )}

      {confirmingDelete && <DeleteTagDialog tag={tag} onClose={() => setConfirmingDelete(false)} />}
    </div>
  );
}

function NewTagForm({ existingTags }: { existingTags: Tag[] }) {
  const queryClient = useQueryClient();
  const [name, setName] = useState("");

  const mutation = useMutation({
    mutationFn: (name: string) => createTag(name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tags"] });
      setName("");
    },
  });

  const trimmed = name.trim();
  const collides = trimmed !== "" && existingTags.some((t) => t.name.toLowerCase() === trimmed.toLowerCase());

  function submit() {
    if (!trimmed || collides) return;
    mutation.mutate(trimmed);
  }

  return (
    <div>
      <div style={{ display: "flex", gap: 8 }}>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && submit()}
          placeholder="New tag name"
          style={{
            flex: 1,
            padding: "8px 12px",
            borderRadius: 8,
            border: "1px solid var(--line)",
            background: "var(--elev)",
            color: "var(--ink)",
            fontSize: 13,
          }}
        />
        <button
          onClick={submit}
          disabled={!trimmed || collides || mutation.isPending}
          style={{
            padding: "8px 16px",
            borderRadius: 8,
            border: "none",
            background: "var(--ember)",
            color: "#fff",
            fontSize: 13,
            fontWeight: 700,
            opacity: trimmed && !collides && !mutation.isPending ? 1 : 0.4,
            cursor: trimmed && !collides && !mutation.isPending ? "pointer" : "not-allowed",
          }}
        >
          {mutation.isPending ? "Creating…" : "Create"}
        </button>
      </div>
      {collides && (
        <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 6 }}>A tag with this name already exists.</div>
      )}
      {mutation.isError && (
        <div style={{ fontSize: 12, color: "#e0442e", marginTop: 6 }}>
          {mutation.error instanceof ApiError ? mutation.error.message : "Could not create the tag."}
        </div>
      )}
    </div>
  );
}

export function TagsTab() {
  const { data } = useQuery({ queryKey: ["tags"], queryFn: listTags });
  const tags = data?.data ?? [];

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16, maxWidth: 520 }}>
      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 4px" }}>Tags</h3>
        <p style={{ fontSize: 13, color: "var(--ink3)", margin: 0, lineHeight: 1.5 }}>
          Rename a tag to merge it into another tag of that name - every activity carrying either
          one ends up with just the merged tag. Deleting a tag removes it from every activity that
          carries it.
        </p>
      </div>

      <NewTagForm existingTags={tags} />

      {tags.length === 0 ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>No tags yet.</div>
      ) : (
        <div>
          {tags.map((tag) => (
            <TagRow key={tag.id} tag={tag} allTags={tags} />
          ))}
        </div>
      )}
    </div>
  );
}
