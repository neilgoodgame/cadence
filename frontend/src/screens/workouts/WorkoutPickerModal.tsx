import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listWorkouts } from "../../api/workouts";
import type { Workout, WorkoutSport } from "../../api/types";
import { sportColor, sportLabel } from "../../lib/sportColors";
import { tagColor, tagRgba } from "../../lib/tagColors";
import { fmtDuration } from "./workoutTree";
import { MiniChart } from "./WorkoutLibraryScreen";

const segBtn = (active: boolean) => ({
  flex: 1,
  textAlign: "center" as const,
  padding: "5px 11px",
  fontSize: 12,
  fontWeight: 600,
  borderRadius: 7,
  cursor: "pointer",
  whiteSpace: "nowrap" as const,
  color: active ? "var(--ink)" : "var(--ink3)",
  background: active ? "var(--card)" : "transparent",
  border: "none",
});

export function WorkoutPickerModal({
  onSelect,
  onClose,
}: {
  onSelect: (workout: Workout) => void;
  onClose: () => void;
}) {
  const [search, setSearch] = useState("");
  const [tag, setTag] = useState<string | null>(null);
  const [sport, setSport] = useState<WorkoutSport | "all">("all");

  // Unfiltered, purely to compute the always-visible tag chip list - matches
  // WorkoutLibraryScreen's own approach so the chips don't shrink as other filters narrow results.
  const allWorkoutsQuery = useQuery({ queryKey: ["workouts", "all"], queryFn: () => listWorkouts() });
  const workoutsQuery = useQuery({
    queryKey: ["workouts", { tag, sport, search }],
    queryFn: () =>
      listWorkouts({
        tag: tag ?? undefined,
        sport: sport === "all" ? undefined : sport,
        search: search || undefined,
      }),
  });

  const allTags = Array.from(new Set((allWorkoutsQuery.data?.data ?? []).flatMap((w) => w.tags))).sort();
  const workouts = workoutsQuery.data?.data ?? [];

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.5)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 200, padding: 24 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ background: "var(--card)", borderRadius: 16, width: 720, maxWidth: "100%", maxHeight: "85vh", display: "flex", flexDirection: "column", overflow: "hidden" }}
      >
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "16px 20px", borderBottom: "1px solid var(--line)" }}>
          <div style={{ fontSize: 16, fontWeight: 800, color: "var(--ink)" }}>Choose a workout</div>
          <button onClick={onClose} style={{ width: 30, height: 30, borderRadius: 8, border: "1px solid var(--line)", background: "none", color: "var(--ink3)", cursor: "pointer", fontSize: 18 }}>
            ×
          </button>
        </div>

        <div style={{ display: "flex", gap: 10, padding: "14px 20px", borderBottom: "1px solid var(--line)", flexWrap: "wrap", alignItems: "center" }}>
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search workouts…"
            style={{ flex: "1 1 180px", padding: "8px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)", fontSize: 13 }}
          />
          <div style={{ display: "flex", gap: 3, background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
            <button onClick={() => setSport("all")} style={segBtn(sport === "all")}>All</button>
            <button onClick={() => setSport("bike")} style={segBtn(sport === "bike")}>Bike</button>
            <button onClick={() => setSport("run")} style={segBtn(sport === "run")}>Run</button>
          </div>
        </div>

        {allTags.length > 0 && (
          <div style={{ display: "flex", flexWrap: "wrap", gap: 6, padding: "12px 20px", borderBottom: "1px solid var(--line)" }}>
            {allTags.map((t) => {
              const active = tag === t;
              const color = tagColor(t);
              return (
                <span
                  key={t}
                  onClick={() => setTag(active ? null : t)}
                  style={{
                    display: "inline-flex",
                    alignItems: "center",
                    gap: 6,
                    fontSize: 11.5,
                    fontWeight: 600,
                    cursor: "pointer",
                    padding: active ? "5px 11px" : "5px 11px 5px 7px",
                    borderRadius: 20,
                    color: active ? "#fff" : "var(--ink2)",
                    background: active ? color : tagRgba(t, 0.1),
                    border: `1px solid ${active ? color : tagRgba(t, 0.3)}`,
                  }}
                >
                  {!active && <span style={{ width: 6, height: 6, borderRadius: "50%", background: color, flexShrink: 0 }} />}
                  {t}
                </span>
              );
            })}
          </div>
        )}

        <div style={{ padding: 20, overflowY: "auto", display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))", gap: 12 }}>
          {workouts.length === 0 && (
            <div style={{ gridColumn: "1 / -1", textAlign: "center", padding: "30px 0", color: "var(--ink3)", fontSize: 13 }}>
              No workouts match these filters.
            </div>
          )}
          {workouts.map((w) => (
            <div
              key={w.id}
              onClick={() => onSelect(w)}
              style={{ cursor: "pointer", background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 12, padding: "12px 14px" }}
            >
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8 }}>
                <div style={{ display: "inline-flex", padding: "3px 9px", borderRadius: 6, fontFamily: "monospace", fontSize: 10.5, fontWeight: 700, background: `${sportColor(w.sport)}22`, color: sportColor(w.sport) }}>
                  {sportLabel(w.sport)}
                </div>
                <div style={{ fontFamily: "monospace", fontSize: 11.5, color: "var(--ink3)", whiteSpace: "nowrap" }}>
                  {fmtDuration(w.duration)} · {w.tss} TSS
                </div>
              </div>
              <div style={{ fontSize: 14, fontWeight: 700, color: "var(--ink)", margin: "8px 0 2px" }}>{w.name}</div>
              <MiniChart preview={w.chart_preview} />
              {w.tags.length > 0 && (
                <div style={{ display: "flex", flexWrap: "wrap", gap: 5, marginTop: 8 }}>
                  {w.tags.map((t) => (
                    <span
                      key={t}
                      style={{
                        display: "inline-flex",
                        alignItems: "center",
                        gap: 5,
                        fontSize: 10,
                        fontWeight: 600,
                        color: "var(--ink2)",
                        background: tagRgba(t, 0.1),
                        border: `1px solid ${tagRgba(t, 0.3)}`,
                        padding: "2px 7px 2px 5px",
                        borderRadius: 20,
                      }}
                    >
                      <span style={{ width: 5, height: 5, borderRadius: "50%", background: tagColor(t), flexShrink: 0 }} />
                      {t}
                    </span>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
