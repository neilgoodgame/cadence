import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listTags, tagActivity, untagActivity } from "../../api/activities";
import { createRace, listRaces, updateRace } from "../../api/races";
import type { Activity } from "../../api/types";
import { formatDateTime } from "../../lib/format";
import { sportColor, sportLabel } from "../../lib/sportColors";

const DISTANCE_PRESETS: Record<string, { label: string; km: number }[]> = {
  run: [
    { label: "5K", km: 5 },
    { label: "10K", km: 10 },
    { label: "15K", km: 15 },
    { label: "Half", km: 21.1 },
    { label: "Marathon", km: 42.195 },
    { label: "50K", km: 50 },
    { label: "100K", km: 100 },
  ],
  bike: [
    { label: "40K", km: 40 },
    { label: "90K", km: 90 },
    { label: "180K", km: 180 },
  ],
  swim: [
    { label: "750m", km: 0.75 },
    { label: "1.5K", km: 1.5 },
    { label: "1.9K", km: 1.9 },
    { label: "3.8K", km: 3.8 },
  ],
  multisport: [
    { label: "Sprint", km: 25.75 },
    { label: "Olympic", km: 51.5 },
    { label: "70.3", km: 113 },
    { label: "140.6", km: 226.2 },
  ],
};

function closestPresetKm(sport: string, activityKm: number): number | null {
  const presets = DISTANCE_PRESETS[sport] ?? DISTANCE_PRESETS.run;
  if (!presets.length) return null;
  return presets.reduce((a, b) =>
    Math.abs(a.km - activityKm) <= Math.abs(b.km - activityKm) ? a : b
  ).km;
}

export function Header({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
  const [newTag, setNewTag] = useState("");
  const [markingRace, setMarkingRace] = useState(false);
  const [selectedRaceId, setSelectedRaceId] = useState("");
  const [raceName, setRaceName] = useState(activity.name);
  const [distanceKm, setDistanceKm] = useState("");
  const [chipTime, setChipTime] = useState("");
  const [resultsUrl, setResultsUrl] = useState("");

  const { data: racesData } = useQuery({ queryKey: ["races"], queryFn: listRaces });
  const linkedRace = racesData?.data.find((r) => r.activity_id === activity.id);
  const unlinkedRaces = racesData?.data.filter((r) => r.activity_id == null) ?? [];
  const sameDateRace = unlinkedRaces.find((r) => r.date === activity.start_date.slice(0, 10));

  const invalidateRaces = () => queryClient.invalidateQueries({ queryKey: ["races"] });

  const presets = DISTANCE_PRESETS[activity.sport] ?? DISTANCE_PRESETS.run;

  const [formError, setFormError] = useState<string | null>(null);

  const resetForm = () => {
    setMarkingRace(false);
    setSelectedRaceId("");
    setRaceName(activity.name);
    setDistanceKm("");
    setChipTime("");
    setResultsUrl("");
    setFormError(null);
  };

  const linkToExistingMutation = useMutation({
    mutationFn: (raceId: string) =>
      updateRace(raceId, {
        activity_id: activity.id,
        result_time: chipTime || null,
        results_url: resultsUrl || null,
      }),
    onSuccess: () => { invalidateRaces(); resetForm(); },
    onError: (e: unknown) => setFormError(e instanceof Error ? e.message : "Something went wrong"),
  });

  const createAndLinkMutation = useMutation({
    mutationFn: () =>
      createRace({
        name: raceName,
        date: activity.start_date.slice(0, 10),
        sport: activity.sport,
        activity_id: activity.id,
        distance_km: distanceKm ? parseFloat(distanceKm) : null,
        result_time: chipTime || null,
        results_url: resultsUrl || null,
      }),
    onSuccess: () => { invalidateRaces(); resetForm(); },
    onError: (e: unknown) => setFormError(e instanceof Error ? e.message : "Something went wrong"),
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
        <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 10 }}>
          <select
            value={selectedRaceId}
            onChange={(e) => setSelectedRaceId(e.target.value)}
            style={{ fontSize: 13, padding: "4px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)", width: 280 }}
          >
            <option value="">Select a race…</option>
            {unlinkedRaces.map((r) => (
              <option key={r.id} value={r.id}>{r.name} · {r.date}</option>
            ))}
            <option value="__new__">+ Create new race</option>
          </select>
          {selectedRaceId === "__new__" && (
            <>
              <input
                value={raceName}
                onChange={(e) => setRaceName(e.target.value)}
                placeholder="Race name"
                style={{ fontSize: 13, padding: "4px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)", width: 260 }}
              />
              <div style={{ display: "flex", gap: 6, flexWrap: "wrap", alignItems: "center" }}>
                {presets.map((p) => (
                  <button
                    key={p.km}
                    type="button"
                    onClick={() => setDistanceKm(String(p.km))}
                    style={{
                      fontSize: 12, fontWeight: 600, padding: "3px 10px", borderRadius: 20,
                      border: "1px solid var(--line)",
                      background: distanceKm === String(p.km) ? "var(--ember)" : "none",
                      color: distanceKm === String(p.km) ? "#fff" : "var(--ink2)",
                      cursor: "pointer",
                    }}
                  >
                    {p.label}
                  </button>
                ))}
                <input
                  value={distanceKm}
                  onChange={(e) => setDistanceKm(e.target.value)}
                  placeholder="km"
                  type="number"
                  step="0.001"
                  style={{ fontSize: 13, padding: "3px 8px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)", width: 72 }}
                />
              </div>
            </>
          )}
          {selectedRaceId && (
            <>
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
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
              {formError && (
                <div style={{ fontSize: 12, color: "#e0442e" }}>{formError}</div>
              )}
              <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                {selectedRaceId === "__new__" ? (
                  <button
                    type="button"
                    onClick={() => createAndLinkMutation.mutate()}
                    disabled={!raceName.trim() || createAndLinkMutation.isPending}
                    style={{ fontSize: 12, fontWeight: 700, padding: "5px 12px", borderRadius: 8, border: "none", background: "var(--ember)", color: "#fff", cursor: "pointer", opacity: !raceName.trim() || createAndLinkMutation.isPending ? 0.5 : 1 }}
                  >
                    {createAndLinkMutation.isPending ? "Saving…" : "Save & link"}
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => linkToExistingMutation.mutate(selectedRaceId)}
                    disabled={linkToExistingMutation.isPending}
                    style={{ fontSize: 12, fontWeight: 700, padding: "5px 12px", borderRadius: 8, border: "none", background: "var(--ember)", color: "#fff", cursor: "pointer", opacity: linkToExistingMutation.isPending ? 0.5 : 1 }}
                  >
                    {linkToExistingMutation.isPending ? "Linking…" : "Link"}
                  </button>
                )}
                <button type="button" onClick={resetForm} style={{ fontSize: 12, border: "none", background: "none", color: "var(--ink3)", cursor: "pointer" }}>
                  Cancel
                </button>
              </div>
            </>
          )}
          {!selectedRaceId && (
            <button onClick={resetForm} style={{ fontSize: 12, border: "none", background: "none", color: "var(--ink3)", cursor: "pointer", alignSelf: "flex-start" }}>
              Cancel
            </button>
          )}
        </div>
      ) : (
        <button
          onClick={() => {
            setMarkingRace(true);
            setSelectedRaceId(sameDateRace?.id ?? "");
            const closest = closestPresetKm(activity.sport, activity.distance_km);
            setDistanceKm(closest != null ? String(closest) : "");
          }}
          style={{ marginTop: 10, fontSize: 12, fontWeight: 600, border: "1px dashed var(--line)", background: "none", color: "var(--ink3)", padding: "4px 12px", borderRadius: 20, cursor: "pointer" }}
        >
          + Mark as race
        </button>
      )}
    </div>
  );
}
