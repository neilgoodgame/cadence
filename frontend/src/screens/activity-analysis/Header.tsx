import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { deleteActivity, getStreams, listTags, tagActivity, untagActivity, updateActivity } from "../../api/activities";
import { createRace, listRaces, updateRace } from "../../api/races";
import type { Activity } from "../../api/types";
import { formatDateTime } from "../../lib/format";
import { buildGpx, downloadGpx } from "../../lib/gpxExport";
import { sportColor, sportColorSoft, sportLabel } from "../../lib/sportColors";
import { SportIcon } from "../../lib/sportIcons";
import { tagColor, tagRgba } from "../../lib/tagColors";

function HeaderActions({ activity }: { activity: Activity }) {
  const [copied, setCopied] = useState(false);
  const exportMutation = useMutation({
    mutationFn: async () => {
      const streams = await getStreams(activity.id, ["time", "latlng", "altitude", "heartrate"], "high");
      const gpx = buildGpx(activity.name, activity.start_date, streams);
      downloadGpx(`${activity.name.replace(/[^\w-]+/g, "_")}.gpx`, gpx);
    },
  });

  async function share() {
    await navigator.clipboard.writeText(window.location.href);
    setCopied(true);
    setTimeout(() => setCopied(false), 1800);
  }

  return (
    <div style={{ display: "flex", gap: 8, marginLeft: "auto", flexShrink: 0 }}>
      <button
        onClick={() => exportMutation.mutate()}
        disabled={!activity.has_gps || exportMutation.isPending}
        title={activity.has_gps ? "Download as GPX" : "No GPS track to export"}
        style={{
          fontSize: 13,
          fontWeight: 600,
          padding: "8px 14px",
          borderRadius: 8,
          border: "1px solid var(--line)",
          background: "none",
          color: "var(--ink2)",
          cursor: activity.has_gps ? "pointer" : "not-allowed",
          opacity: activity.has_gps ? 1 : 0.5,
        }}
      >
        {exportMutation.isPending ? "Exporting…" : "Export"}
      </button>
      <button
        onClick={share}
        style={{
          fontSize: 13,
          fontWeight: 600,
          padding: "8px 14px",
          borderRadius: 8,
          border: "none",
          background: "var(--ember)",
          color: "#fff",
          cursor: "pointer",
        }}
      >
        {copied ? "Link copied" : "Share"}
      </button>
    </div>
  );
}

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

const TAG_GROUPS: { name: string; tags: string[] }[] = [
  { name: "INTENSITY", tags: ["Endurance", "Tempo", "Sweet Spot", "Threshold", "VO2 Max", "Recovery"] },
  { name: "SESSION TYPE", tags: ["Long Ride", "Long Run", "Race", "Key session", "Group ride", "Brick", "Commute", "Indoor", "Travel"] },
  { name: "HOW IT FELT", tags: ["Felt strong", "Felt flat", "Hot day", "Sick"] },
];

function closestPresetKm(sport: string, activityKm: number): number | null {
  const presets = DISTANCE_PRESETS[sport] ?? DISTANCE_PRESETS.run;
  if (!presets.length) return null;
  return presets.reduce((a, b) =>
    Math.abs(a.km - activityKm) <= Math.abs(b.km - activityKm) ? a : b
  ).km;
}

export function Header({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [newTag, setNewTag] = useState("");
  const [tagMenuOpen, setTagMenuOpen] = useState(false);
  const [tagError, setTagError] = useState<string | null>(null);
  const tagMenuRef = useRef<HTMLDivElement>(null);
  const [renaming, setRenaming] = useState(false);
  const [nameInput, setNameInput] = useState(activity.name);
  const nameInputRef = useRef<HTMLInputElement>(null);
  const [markingRace, setMarkingRace] = useState(false);

  useEffect(() => {
    if (!tagMenuOpen) return;
    function onClick(e: MouseEvent) {
      if (tagMenuRef.current && !tagMenuRef.current.contains(e.target as Node)) {
        setTagMenuOpen(false);
      }
    }
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, [tagMenuOpen]);

  const renameMutation = useMutation({
    mutationFn: (name: string) => updateActivity(activity.id, { name }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity", activity.id] });
      setRenaming(false);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteActivity(activity.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activities"] });
      navigate("/activities");
    },
  });

  function commitRename() {
    const trimmed = nameInput.trim();
    if (trimmed && trimmed !== activity.name) {
      renameMutation.mutate(trimmed);
    } else {
      setNameInput(activity.name);
      setRenaming(false);
    }
  }
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
    onSuccess: () => {
      setTagError(null);
      invalidate();
    },
    // e.g. the "Auto-matched" marker tag - the backend refuses to remove it and previously
    // failed silently here, leaving the click looking like it did nothing.
    onError: (e: unknown) => setTagError(e instanceof Error ? e.message : "Couldn't remove that tag."),
  });

  const tagDraft = newTag.trim();
  const appliedTagsLower = activity.tags.map((t) => t.toLowerCase());
  const presetTagsLower = new Set(TAG_GROUPS.flatMap((g) => g.tags.map((t) => t.toLowerCase())));
  // The athlete's own tag catalog (from GET /v1/tags) takes priority over the presets below:
  // the backend matches tag names case-insensitively and keeps whatever casing was stored
  // first, so surfacing the athlete's existing tags (with their real casing) here is what
  // stops "fartlek" from silently becoming "FARTLEK" when it collides with an old tag.
  const customTagNames = (allTags?.data ?? [])
    .map((t) => t.name)
    .filter((name) => !presetTagsLower.has(name.toLowerCase()) && !activity.tags.includes(name));
  const filteredTagGroups = [{ name: "YOUR TAGS", tags: customTagNames }, ...TAG_GROUPS]
    .map((g) => ({
      name: g.name,
      items: g.tags
        .filter((t) => !activity.tags.includes(t))
        .filter((t) => !tagDraft || t.toLowerCase().includes(tagDraft.toLowerCase())),
    }))
    .filter((g) => g.items.length > 0);
  const knownTagsLower = new Set([
    ...presetTagsLower,
    ...(allTags?.data.map((t) => t.name.toLowerCase()) ?? []),
  ]);
  const showCreateTag =
    tagDraft.length > 0 && !appliedTagsLower.includes(tagDraft.toLowerCase()) && !knownTagsLower.has(tagDraft.toLowerCase());

  return (
    <div style={{ display: "flex", alignItems: "flex-start", gap: 16 }}>
      <div
        style={{
          width: 46,
          height: 46,
          borderRadius: 11,
          background: sportColorSoft(activity.sport),
          border: "1px solid var(--line)",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          flexShrink: 0,
          color: sportColor(activity.sport),
        }}
      >
        <SportIcon sport={activity.sport} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
      {renaming ? (
        <div style={{ display: "flex", alignItems: "center", gap: 8, margin: "0 0 6px" }}>
          <input
            ref={nameInputRef}
            value={nameInput}
            onChange={(e) => setNameInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") commitRename();
              if (e.key === "Escape") { setNameInput(activity.name); setRenaming(false); }
            }}
            autoFocus
            style={{
              fontSize: 24,
              fontWeight: 800,
              letterSpacing: "-0.02em",
              border: "none",
              borderBottom: "2px solid var(--ember)",
              background: "none",
              color: "var(--ink)",
              outline: "none",
              width: "100%",
              maxWidth: 520,
              padding: "2px 0",
            }}
          />
          <button
            onClick={commitRename}
            disabled={renameMutation.isPending}
            style={{ fontSize: 12, fontWeight: 700, padding: "4px 12px", borderRadius: 8, border: "none", background: "var(--ember)", color: "#fff", cursor: "pointer", flexShrink: 0 }}
          >
            {renameMutation.isPending ? "…" : "Save"}
          </button>
          <button
            onClick={() => { setNameInput(activity.name); setRenaming(false); }}
            style={{ fontSize: 12, border: "none", background: "none", color: "var(--ink3)", cursor: "pointer", flexShrink: 0 }}
          >
            Cancel
          </button>
        </div>
      ) : (
        <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", margin: "0 0 6px" }}>
          <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>{activity.name}</h1>
          <button
            onClick={() => { setNameInput(activity.name); setRenaming(true); }}
            title="Rename activity"
            style={{ fontSize: 14, border: "none", background: "none", color: "var(--ink3)", cursor: "pointer", padding: "2px 4px", borderRadius: 4, lineHeight: 1, flexShrink: 0 }}
          >
            ✎
          </button>
          <button
            onClick={() => {
              if (window.confirm(`Delete "${activity.name}"? This can't be undone.`)) deleteMutation.mutate();
            }}
            disabled={deleteMutation.isPending}
            title="Delete activity"
            style={{ fontSize: 14, border: "none", background: "none", color: "#c4332a", cursor: "pointer", padding: "2px 4px", borderRadius: 4, lineHeight: 1, flexShrink: 0, opacity: deleteMutation.isPending ? 0.6 : 1 }}
          >
            {deleteMutation.isPending ? "…" : "✕"}
          </button>
          <span style={{ width: 1, height: 18, background: "var(--line)", margin: "0 1px" }} />
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
                  fontWeight: 600,
                  color: "var(--ink2)",
                  background: tagRgba(tag, 0.1),
                  border: `1px solid ${tagRgba(tag, 0.3)}`,
                  padding: "4px 7px 4px 10px",
                  borderRadius: 20,
                }}
              >
                <span style={{ width: 6, height: 6, borderRadius: "50%", background: tagColor(tag), flexShrink: 0 }} />
                {tag}
                <button
                  onClick={() => tagId && removeTag.mutate(tagId)}
                  disabled={!tagId}
                  style={{ border: "none", background: "none", color: "var(--ink3)", cursor: "pointer", padding: 0, fontSize: 14, lineHeight: 1, fontWeight: 700 }}
                >
                  ×
                </button>
              </span>
            );
          })}
          <div style={{ position: "relative" }} ref={tagMenuRef}>
            <button
              onClick={() => setTagMenuOpen((o) => !o)}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 4,
                fontSize: 12,
                fontWeight: 600,
                padding: "4px 11px",
                borderRadius: 20,
                cursor: "pointer",
                color: tagMenuOpen ? "var(--ember)" : "var(--ink3)",
                border: `1px dashed ${tagMenuOpen ? "var(--ember)" : "var(--line)"}`,
                background: tagMenuOpen ? "var(--ember-soft)" : "transparent",
              }}
            >
              <span style={{ fontSize: 14, lineHeight: 1 }}>+</span>Tag
            </button>
            {tagMenuOpen && (
              <div
                style={{
                  position: "absolute",
                  top: 32,
                  left: 0,
                  zIndex: 30,
                  width: 270,
                  background: "var(--card)",
                  border: "1px solid var(--line)",
                  borderRadius: 12,
                  boxShadow: "0 14px 36px rgba(20,17,15,0.18)",
                  padding: 13,
                }}
              >
                <input
                  value={newTag}
                  onChange={(e) => setNewTag(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && newTag.trim()) {
                      addTag.mutate(newTag.trim());
                    }
                  }}
                  placeholder="Create or search tags…"
                  autoFocus
                  style={{
                    width: "100%",
                    padding: "8px 10px",
                    borderRadius: 8,
                    border: "1px solid var(--line)",
                    background: "var(--elev)",
                    fontSize: 13,
                    color: "var(--ink)",
                    outline: "none",
                    marginBottom: 12,
                    boxSizing: "border-box",
                  }}
                />
                {showCreateTag && (
                  <div
                    onClick={() => addTag.mutate(tagDraft)}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 7,
                      padding: "8px 10px",
                      borderRadius: 8,
                      background: "var(--ember-soft)",
                      border: "1px solid var(--ember)",
                      cursor: "pointer",
                      marginBottom: 12,
                    }}
                  >
                    <span style={{ fontSize: 14, color: "var(--ember)", lineHeight: 1 }}>+</span>
                    <span style={{ fontSize: 12, color: "var(--ink2)" }}>Create</span>
                    <span style={{ fontSize: 12, fontWeight: 700, color: "var(--ember)" }}>{tagDraft}</span>
                  </div>
                )}
                {filteredTagGroups.map((g) => (
                  <div key={g.name} style={{ marginBottom: 11 }}>
                    <div style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: 9, letterSpacing: "0.08em", color: "var(--ink3)", marginBottom: 7 }}>{g.name}</div>
                    <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
                      {g.items.map((t) => (
                        <div
                          key={t}
                          onClick={() => addTag.mutate(t)}
                          style={{
                            display: "inline-flex",
                            alignItems: "center",
                            gap: 6,
                            fontSize: 12,
                            fontWeight: 500,
                            padding: "5px 10px",
                            borderRadius: 20,
                            cursor: "pointer",
                            background: "var(--canvas)",
                            border: "1px solid var(--line)",
                            color: "var(--ink2)",
                          }}
                        >
                          <span style={{ width: 6, height: 6, borderRadius: "50%", background: tagColor(t), flexShrink: 0 }} />
                          {t}
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
          {tagError && (
            <span style={{ flexBasis: "100%", fontSize: 12, color: "#c4332a" }}>{tagError}</span>
          )}
        </div>
      )}

      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 12 }}>
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
        <HeaderActions activity={activity} />
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
    </div>
  );
}
