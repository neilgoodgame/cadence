import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listTags, tagActivity, untagActivity } from "../../api/activities";
import type { Activity } from "../../api/types";
import { formatDateTime } from "../../lib/format";
import { sportColor, sportLabel } from "../../lib/sportColors";

export function Header({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
  const [newTag, setNewTag] = useState("");

  // Activity.tags is just names; removing one needs the tag's id, which only GET /v1/tags
  // (the athlete's full tag catalog) carries.
  const { data: allTags } = useQuery({ queryKey: ["tags"], queryFn: listTags });
  const tagIdByName = new Map(allTags?.data.map((t) => [t.name, t.id]));

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["activity", activity.id] });
  const addTag = useMutation({
    mutationFn: (name: string) => tagActivity(activity.id, name),
    onSuccess: () => {
      setNewTag("");
      invalidate();
      queryClient.invalidateQueries({ queryKey: ["tags"] });
    },
  });
  const removeTag = useMutation({
    mutationFn: (tagId: string) => untagActivity(activity.id, tagId),
    onSuccess: invalidate,
  });

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 6 }}>
        <span
          style={{
            fontSize: 12,
            fontWeight: 600,
            padding: "3px 10px",
            borderRadius: 20,
            background: sportColor(activity.sport),
            color: "#fff",
          }}
        >
          {sportLabel(activity.sport)}
        </span>
        <span style={{ fontSize: 13, color: "var(--ink3)" }}>
          {formatDateTime(activity.start_date)} · {activity.source}
          {activity.device && ` · ${activity.device}`}
        </span>
      </div>

      <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: "0 0 12px" }}>{activity.name}</h1>

      <div style={{ display: "flex", gap: 6, flexWrap: "wrap", alignItems: "center" }}>
        {activity.tags.map((tag) => {
          const tagId = tagIdByName.get(tag);
          return (
            <span
              key={tag}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 6,
                fontSize: 12,
                color: "var(--ink2)",
                background: "var(--elev)",
                padding: "4px 10px",
                borderRadius: 20,
              }}
            >
              {tag}
              <button
                onClick={() => tagId && removeTag.mutate(tagId)}
                disabled={!tagId}
                style={{ border: "none", background: "none", color: "var(--ink3)", cursor: "pointer", padding: 0, fontSize: 12 }}
              >
                ×
              </button>
            </span>
          );
        })}
        <input
          value={newTag}
          onChange={(e) => setNewTag(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && newTag.trim()) {
              addTag.mutate(newTag.trim());
            }
          }}
          placeholder="+ Tag"
          style={{
            fontSize: 12,
            border: "1px dashed var(--line)",
            borderRadius: 20,
            padding: "4px 10px",
            background: "none",
            color: "var(--ink)",
            width: 80,
          }}
        />
      </div>
    </div>
  );
}
