import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listTags, tagActivity, untagActivity } from "../../api/activities";
import { createRace, listRaces, updateRace } from "../../api/races";
import type { Activity } from "../../api/types";
import { formatDateTime } from "../../lib/format";
import { sportColor, sportLabel } from "../../lib/sportColors";

export function Header({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
  const [newTag, setNewTag] = useState("");
  const [markingRace, setMarkingRace] = useState(false);
  const [raceName, setRaceName] = useState(activity.name);
  const [chipTime, setChipTime] = useState("");
  const [resultsUrl, setResultsUrl] = useState("");

  const { data: racesData } = useQuery({ queryKey: ["races"], queryFn: listRaces });
  const linkedRace = racesData?.data.find((r) => r.activity_id === activity.id);
  const matchingRace = racesData?.data.find(
    (r) => r.activity_id == null && r.date === activity.start_date.slice(0, 10)
  );

  const invalidateRaces = () => queryClient.invalidateQueries({ queryKey: ["races"] });

  const linkToExistingMutation = useMutation({
    mutationFn: (raceId: string) => updateRace(raceId, { activity_id: activity.id }),
    onSuccess: invalidateRaces,
  });

  const createAndLinkMutation = useMutation({
    mutationFn: () =>
      createRace({
        name: raceName,
        date: activity.start_date.slice(0, 10),
        sport: activity.sport,
        activity_id: activity.id,
        result_time: chipTime || null,
        results_url: resultsUrl || null,
      }),
    onSuccess: () => {
      invalidateRaces();
      setMarkingRace(false);
    },
  });

  const unlinkMutation = useMutation({
    mutationFn: (raceId: string) => updateRace(raceId, { activity_id: null }),
    onSuccess: invalidateRaces,
  });

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
      {linkedRace ? (
        <div style={{ marginTop: 10 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span style={{ fontSize: 12, fontWeight: 700, color: "var(--ember)", padding: "3px 10px", borderRadius: 20, border: "1px solid var(--ember)" }}>
              🏁 {linkedRace.name}
            </span>
            {linkedRace.result_time && (
              <span style={{ fontSize: 12, color: "var(--ink2)" }}>{linkedRace.result_time}</span>
            )}
            {linkedRace.results_url && (
              <a
                href={linkedRace.results_url}
                target="_blank"
                rel="noopener noreferrer"
                style={{ fontSize: 12, color: "var(--ember)", textDecoration: "none" }}
              >
                Results ↗
              </a>
            )}
            <button
              onClick={() => unlinkMutation.mutate(linkedRace.id)}
              style={{ fontSize: 12, border: "none", background: "none", color: "var(--ink3)", cursor: "pointer" }}
            >
              Unlink
            </button>
          </div>
        </div>
      ) : markingRace ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 6, marginTop: 10 }}>
          <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
            <input
              value={raceName}
              onChange={(e) => setRaceName(e.target.value)}
              placeholder="Race name"
              style={{ fontSize: 13, padding: "4px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)", width: 200 }}
            />
            <input
              value={chipTime}
              onChange={(e) => setChipTime(e.target.value)}
              placeholder="Chip time (H:MM:SS)"
              style={{ fontSize: 13, padding: "4px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)", width: 160 }}
            />
            <input
              type="url"
              value={resultsUrl}
              onChange={(e) => setResultsUrl(e.target.value)}
              placeholder="Results URL (optional)"
              style={{ fontSize: 13, padding: "4px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)", width: 200 }}
            />
          </div>
          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <button
              onClick={() => createAndLinkMutation.mutate()}
              disabled={!raceName || createAndLinkMutation.isPending}
              style={{ fontSize: 12, fontWeight: 700, padding: "5px 12px", borderRadius: 8, border: "none", background: "var(--ember)", color: "#fff", cursor: "pointer" }}
            >
              {createAndLinkMutation.isPending ? "Saving…" : "Save"}
            </button>
            {matchingRace && (
              <button
                onClick={() => linkToExistingMutation.mutate(matchingRace.id)}
                style={{ fontSize: 12, fontWeight: 600, padding: "5px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "none", color: "var(--ink2)", cursor: "pointer" }}
              >
                Link to "{matchingRace.name}"
              </button>
            )}
            <button onClick={() => setMarkingRace(false)} style={{ fontSize: 12, border: "none", background: "none", color: "var(--ink3)", cursor: "pointer" }}>
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <button
          onClick={() => setMarkingRace(true)}
          style={{ marginTop: 10, fontSize: 12, fontWeight: 600, border: "1px dashed var(--line)", background: "none", color: "var(--ink3)", padding: "4px 12px", borderRadius: 20, cursor: "pointer" }}
        >
          + Mark as race
        </button>
      )}
    </div>
  );
}
