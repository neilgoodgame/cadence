import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createWorkout,
  createWorkoutFolder,
  deleteWorkout,
  getWorkout,
  listWorkoutFolders,
  listWorkouts,
  updateWorkout,
} from "../../api/workouts";
import type { Workout, WorkoutFolder, WorkoutSort, WorkoutSport } from "../../api/types";
import { sportColor, sportLabel } from "../../lib/sportColors";
import { useAuth } from "../../auth/AuthContext";
import { fmtDuration, withIds } from "./workoutTree";
import { AssignModal } from "./AssignModal";
import { ExportModal } from "./ExportModal";
import { buildZwo, download } from "./workoutExport";
import { parseFixtureJson, parseZwoFile } from "./workoutImport";

const SORTS: { value: WorkoutSort; label: string }[] = [
  { value: "recent", label: "Recently updated" },
  { value: "name", label: "Name A–Z" },
  { value: "duration", label: "Duration" },
  { value: "tss", label: "TSS" },
  { value: "used", label: "Most used" },
];

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

export function WorkoutLibraryScreen({ onEdit, onNew }: { onEdit: (id: string) => void; onNew: () => void }) {
  const queryClient = useQueryClient();
  const { user } = useAuth();

  const [folderId, setFolderId] = useState<string | null>(null);
  const [tag, setTag] = useState<string | null>(null);
  const [sport, setSport] = useState<WorkoutSport | "all">("all");
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState<WorkoutSort>("recent");
  const [view, setView] = useState<"grid" | "list">("grid");
  const [selected, setSelected] = useState<Record<string, boolean>>({});
  const [assignTargetId, setAssignTargetId] = useState<string | null>(null);
  const [exportTargetId, setExportTargetId] = useState<string | null>(null);
  const importInputRef = useRef<HTMLInputElement>(null);

  const foldersQuery = useQuery({ queryKey: ["workout-folders"], queryFn: listWorkoutFolders });
  // Unfiltered, purely to compute the always-visible tag chip list (matches the design:
  // tag chips don't shrink as other filters narrow the visible grid/list).
  const allWorkoutsQuery = useQuery({ queryKey: ["workouts", "all"], queryFn: () => listWorkouts() });
  const workoutsQuery = useQuery({
    queryKey: ["workouts", { folderId, tag, sport, search, sort }],
    queryFn: () =>
      listWorkouts({
        folder_id: folderId ?? undefined,
        tag: tag ?? undefined,
        sport: sport === "all" ? undefined : sport,
        search: search || undefined,
        sort,
      }),
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["workouts"] });
    queryClient.invalidateQueries({ queryKey: ["workout-folders"] });
  };

  const createFolderMutation = useMutation({
    mutationFn: (name: string) => createWorkoutFolder(name),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["workout-folders"] }),
  });

  const duplicateMutation = useMutation({
    mutationFn: async (id: string) => {
      const original = await getWorkout(id);
      return createWorkout({
        name: `${original.name} (copy)`,
        sport: original.sport,
        steps: original.steps,
        folder_id: original.folder_id,
        tags: original.tags,
      });
    },
    onSuccess: invalidate,
  });

  const deleteMutation = useMutation({ mutationFn: deleteWorkout, onSuccess: invalidate });

  const moveFolderMutation = useMutation({
    mutationFn: ({ id, targetFolderId }: { id: string; targetFolderId: string }) =>
      updateWorkout(id, { folder_id: targetFolderId }),
    onSuccess: invalidate,
  });

  const bulkDeleteMutation = useMutation({
    mutationFn: async (ids: string[]) => {
      await Promise.all(ids.map((id) => deleteWorkout(id)));
    },
    onSuccess: () => {
      invalidate();
      setSelected({});
    },
  });

  const bulkTagMutation = useMutation({
    mutationFn: async ({ ids, tagName }: { ids: string[]; tagName: string }) => {
      const workouts = workoutsQuery.data?.data ?? [];
      await Promise.all(
        ids.map((id) => {
          const workout = workouts.find((w) => w.id === id);
          const tags = workout && !workout.tags.includes(tagName) ? [...workout.tags, tagName] : workout?.tags;
          return updateWorkout(id, { tags });
        }),
      );
    },
    onSuccess: () => {
      invalidate();
      setSelected({});
    },
  });

  const importMutation = useMutation({
    mutationFn: async (files: File[]) => {
      const results: { filename: string; ok: boolean; error?: string }[] = [];
      for (const file of files) {
        try {
          const text = await file.text();
          const parsed = file.name.toLowerCase().endsWith(".json") ? parseFixtureJson(text) : parseZwoFile(text);
          await createWorkout({ name: parsed.name, sport: parsed.sport, steps: parsed.steps });
          results.push({ filename: file.name, ok: true });
        } catch (err) {
          results.push({ filename: file.name, ok: false, error: err instanceof Error ? err.message : "Unknown error" });
        }
      }
      return results;
    },
    onSuccess: (results) => {
      invalidate();
      const failed = results.filter((r) => !r.ok);
      if (failed.length > 0) {
        window.alert(
          `Imported ${results.length - failed.length} of ${results.length}.\n\nFailed:\n${failed.map((f) => `${f.filename}: ${f.error}`).join("\n")}`,
        );
      }
    },
  });

  const bulkExportMutation = useMutation({
    mutationFn: async (ids: string[]) => {
      for (const id of ids) {
        const detail = await getWorkout(id);
        const zwo = buildZwo(detail.name, detail.sport, withIds(detail.steps));
        download(`${detail.name.replace(/[^a-z0-9]+/gi, "-").toLowerCase() || "workout"}.zwo`, zwo);
      }
    },
    onSuccess: () => setSelected({}),
  });

  const folders = foldersQuery.data?.data ?? [];
  const allTags = useMemo(
    () => Array.from(new Set((allWorkoutsQuery.data?.data ?? []).flatMap((w) => w.tags))).sort(),
    [allWorkoutsQuery.data],
  );
  const workouts = workoutsQuery.data?.data ?? [];
  const selectedIds = Object.entries(selected)
    .filter(([, v]) => v)
    .map(([id]) => id);

  const totalCount = allWorkoutsQuery.data?.data.length ?? 0;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>Workout library</h1>
          <div style={{ fontSize: 13, color: "var(--ink3)", marginTop: 4, fontFamily: "monospace" }}>
            {workouts.length} of {totalCount} workouts
          </div>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <input
            ref={importInputRef}
            type="file"
            accept=".zwo,.xml,.json"
            multiple
            style={{ display: "none" }}
            onChange={(e) => {
              const files = Array.from(e.target.files ?? []);
              e.target.value = "";
              if (files.length > 0) importMutation.mutate(files);
            }}
          />
          <button
            onClick={() => importInputRef.current?.click()}
            disabled={importMutation.isPending}
            style={{ border: "1px solid var(--line)", borderRadius: 10, background: "var(--card)", color: "var(--ink2)", fontSize: 14, fontWeight: 700, padding: "10px 20px", cursor: "pointer" }}
          >
            {importMutation.isPending ? "Importing…" : "Import"}
          </button>
          <button
            onClick={onNew}
            style={{ border: "none", borderRadius: 10, background: "var(--ember)", color: "#fff", fontSize: 14, fontWeight: 700, padding: "10px 20px", cursor: "pointer" }}
          >
            + New workout
          </button>
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "210px 1fr", gap: 20, alignItems: "start" }}>
        <div style={{ display: "flex", flexDirection: "column", gap: 18, position: "sticky", top: 16 }}>
          <div>
            <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.05em", color: "var(--ink3)", textTransform: "uppercase", marginBottom: 8 }}>
              Folders
            </div>
            <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
              <FolderRow name="All workouts" count={totalCount} active={folderId === null} onClick={() => setFolderId(null)} />
              {folders.map((f: WorkoutFolder) => (
                <FolderRow key={f.id} name={f.name} count={f.count} active={folderId === f.id} onClick={() => setFolderId(f.id)} />
              ))}
            </div>
            <button
              onClick={() => {
                const name = window.prompt("New folder name:");
                if (name && name.trim()) createFolderMutation.mutate(name.trim());
              }}
              style={{ marginTop: 4, padding: "7px 10px", fontSize: 12, fontWeight: 600, color: "var(--ink3)", background: "none", border: "none", cursor: "pointer", textAlign: "left" }}
            >
              + New folder
            </button>
          </div>

          <div>
            <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.05em", color: "var(--ink3)", textTransform: "uppercase", marginBottom: 8 }}>
              Tags
            </div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
              {allTags.map((t) => (
                <div
                  key={t}
                  onClick={() => setTag(tag === t ? null : t)}
                  style={{
                    padding: "5px 11px",
                    borderRadius: 20,
                    fontSize: 11.5,
                    fontWeight: 600,
                    cursor: "pointer",
                    border: `1px solid ${tag === t ? "var(--ember)" : "var(--line)"}`,
                    color: tag === t ? "var(--ember)" : "var(--ink2)",
                    background: tag === t ? "rgba(236,74,38,0.08)" : "transparent",
                  }}
                >
                  {t}
                </div>
              ))}
            </div>
          </div>

          <div>
            <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.05em", color: "var(--ink3)", textTransform: "uppercase", marginBottom: 8 }}>
              Sport
            </div>
            <div style={{ display: "flex", gap: 3, background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
              <button onClick={() => setSport("all")} style={segBtn(sport === "all")}>All</button>
              <button onClick={() => setSport("bike")} style={segBtn(sport === "bike")}>Bike</button>
              <button onClick={() => setSport("run")} style={segBtn(sport === "run")}>Run</button>
            </div>
          </div>
        </div>

        <div style={{ minWidth: 0 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 14, flexWrap: "wrap" }}>
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search workouts…"
              style={{ flex: 1, minWidth: 220, padding: "9px 14px", borderRadius: 9, border: "1px solid var(--line)", background: "var(--card)", color: "var(--ink)", fontSize: 13.5 }}
            />
            <select
              value={sort}
              onChange={(e) => setSort(e.target.value as WorkoutSort)}
              style={{ padding: "9px 12px", borderRadius: 9, border: "1px solid var(--line)", background: "var(--card)", color: "var(--ink)", fontSize: 13, fontWeight: 600 }}
            >
              {SORTS.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
            <div style={{ display: "flex", gap: 3, background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
              <button onClick={() => setView("grid")} style={segBtn(view === "grid")}>Grid</button>
              <button onClick={() => setView("list")} style={segBtn(view === "list")}>List</button>
            </div>
          </div>

          {selectedIds.length > 0 && (
            <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "10px 16px", borderRadius: 10, background: "rgba(236,74,38,0.08)", border: "1px solid rgba(236,74,38,0.25)", marginBottom: 14 }}>
              <span style={{ fontSize: 13, fontWeight: 700, color: "var(--ember)" }}>{selectedIds.length} selected</span>
              <div style={{ width: 1, height: 18, background: "rgba(236,74,38,0.25)" }} />
              <button
                onClick={() => {
                  const tagName = window.prompt(`Add tag to ${selectedIds.length} workout(s):`);
                  if (tagName && tagName.trim()) bulkTagMutation.mutate({ ids: selectedIds, tagName: tagName.trim() });
                }}
                style={bulkActionStyle()}
              >
                Add tag
              </button>
              <button onClick={() => bulkExportMutation.mutate(selectedIds)} style={bulkActionStyle()}>
                Export
              </button>
              <button
                onClick={() => {
                  if (window.confirm(`Delete ${selectedIds.length} workout(s)? This can't be undone.`)) {
                    bulkDeleteMutation.mutate(selectedIds);
                  }
                }}
                style={bulkActionStyle("#c4332a")}
              >
                Delete
              </button>
              <button onClick={() => setSelected({})} style={{ ...bulkActionStyle(), marginLeft: "auto" }}>
                Clear
              </button>
            </div>
          )}

          {workouts.length === 0 ? (
            <div style={{ textAlign: "center", padding: "60px 20px", color: "var(--ink3)" }}>
              <div style={{ fontSize: 14, fontWeight: 600, color: "var(--ink2)" }}>No workouts match your filters</div>
              <div style={{ fontSize: 12.5, marginTop: 4 }}>Try clearing the search, folder, or tag filters.</div>
            </div>
          ) : view === "grid" ? (
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(270px, 1fr))", gap: 14 }}>
              {workouts.map((w) => (
                <WorkoutCard
                  key={w.id}
                  workout={w}
                  folders={folders}
                  selected={!!selected[w.id]}
                  onToggleSelect={() => setSelected((s) => ({ ...s, [w.id]: !s[w.id] }))}
                  onOpen={() => onEdit(w.id)}
                  onAssign={() => setAssignTargetId(w.id)}
                  onDuplicate={() => duplicateMutation.mutate(w.id)}
                  onExport={() => setExportTargetId(w.id)}
                  onMoveFolder={(targetFolderId) => moveFolderMutation.mutate({ id: w.id, targetFolderId })}
                  onDelete={() => {
                    if (window.confirm(`Delete "${w.name}"? This can't be undone.`)) deleteMutation.mutate(w.id);
                  }}
                />
              ))}
            </div>
          ) : (
            <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, overflow: "hidden" }}>
              <div style={{ display: "grid", gridTemplateColumns: "26px minmax(150px,1.4fr) 80px 90px 70px 1fr 110px 150px", gap: 10, padding: "10px 16px", borderBottom: "1px solid var(--line)", fontFamily: "monospace", fontSize: 10, textTransform: "uppercase", letterSpacing: "0.05em", color: "var(--ink3)", alignItems: "center" }}>
                <span></span>
                <span>Name</span>
                <span>Sport</span>
                <span>Duration</span>
                <span>TSS</span>
                <span>Tags</span>
                <span>Folder</span>
                <span style={{ textAlign: "right" }}>Actions</span>
              </div>
              {workouts.map((w) => (
                <WorkoutRow
                  key={w.id}
                  workout={w}
                  folders={folders}
                  selected={!!selected[w.id]}
                  onToggleSelect={() => setSelected((s) => ({ ...s, [w.id]: !s[w.id] }))}
                  onOpen={() => onEdit(w.id)}
                  onAssign={() => setAssignTargetId(w.id)}
                  onDuplicate={() => duplicateMutation.mutate(w.id)}
                  onExport={() => setExportTargetId(w.id)}
                  onMoveFolder={(targetFolderId) => moveFolderMutation.mutate({ id: w.id, targetFolderId })}
                  onDelete={() => {
                    if (window.confirm(`Delete "${w.name}"? This can't be undone.`)) deleteMutation.mutate(w.id);
                  }}
                />
              ))}
            </div>
          )}
        </div>
      </div>

      {assignTargetId && (
        <AssignModal
          workoutId={assignTargetId}
          workoutName={workouts.find((w) => w.id === assignTargetId)?.name ?? ""}
          selfId={user?.id ?? ""}
          isCoach={!!user?.is_coach}
          onClose={() => setAssignTargetId(null)}
        />
      )}
      {exportTargetId && <ExportTarget id={exportTargetId} onClose={() => setExportTargetId(null)} />}
    </div>
  );
}

function ExportTarget({ id, onClose }: { id: string; onClose: () => void }) {
  const { data } = useQuery({ queryKey: ["workout", id], queryFn: () => getWorkout(id) });
  if (!data) return null;
  return <ExportModal name={data.name} sport={data.sport} steps={withIds(data.steps)} onClose={onClose} />;
}

function FolderRow({ name, count, active, onClick }: { name: string; count: number; active: boolean; onClick: () => void }) {
  return (
    <div
      onClick={onClick}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 8,
        padding: "7px 10px",
        borderRadius: 8,
        cursor: "pointer",
        fontSize: 13,
        fontWeight: active ? 700 : 500,
        color: active ? "var(--ember)" : "var(--ink2)",
        background: active ? "rgba(236,74,38,0.08)" : "transparent",
      }}
    >
      <span style={{ flex: 1, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{name}</span>
      <span style={{ fontFamily: "monospace", fontSize: 11, color: "var(--ink3)" }}>{count}</span>
    </div>
  );
}

interface RowActions {
  onAssign: () => void;
  onDuplicate: () => void;
  onExport: () => void;
  onMoveFolder: (folderId: string) => void;
  onDelete: () => void;
}

function FolderPicker({ workout, folders, onMoveFolder }: { workout: Workout; folders: WorkoutFolder[]; onMoveFolder: (folderId: string) => void }) {
  return (
    <select
      value={workout.folder_id ?? ""}
      onClick={(e) => e.stopPropagation()}
      onChange={(e) => onMoveFolder(e.target.value)}
      style={{ fontSize: 11.5, fontWeight: 600, color: "var(--ink2)", background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 6, padding: "3px 6px", maxWidth: "100%" }}
      title="Move to folder"
    >
      <option value="">No folder</option>
      {folders.map((f) => (
        <option key={f.id} value={f.id}>
          {f.name}
        </option>
      ))}
    </select>
  );
}

function ActionButtons({ onAssign, onDuplicate, onExport, onDelete }: RowActions) {
  const btnStyle = { width: 26, height: 26, borderRadius: 7, display: "flex", alignItems: "center", justifyContent: "center", color: "var(--ink3)", cursor: "pointer", flexShrink: 0, background: "none", border: "none" };
  return (
    <>
      <button onClick={(e) => (e.stopPropagation(), onAssign())} style={btnStyle} title="Assign">
        ⚲
      </button>
      <button onClick={(e) => (e.stopPropagation(), onDuplicate())} style={btnStyle} title="Duplicate">
        ⧉
      </button>
      <button onClick={(e) => (e.stopPropagation(), onExport())} style={btnStyle} title="Export">
        ↓
      </button>
      <button onClick={(e) => (e.stopPropagation(), onDelete())} style={{ ...btnStyle, color: "#c4332a" }} title="Delete">
        ✕
      </button>
    </>
  );
}

function WorkoutCard({
  workout,
  folders,
  selected,
  onToggleSelect,
  onOpen,
  ...actions
}: { workout: Workout; folders: WorkoutFolder[]; selected: boolean; onToggleSelect: () => void; onOpen: () => void } & RowActions) {
  return (
    <div data-testid={`workout-card-${workout.id}`} style={{ background: "var(--card)", border: `1px solid ${selected ? "var(--ember)" : "var(--line)"}`, borderRadius: 14, padding: "14px 16px 16px" }}>
      <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 8 }}>
        <CheckBox checked={selected} onClick={onToggleSelect} />
        <div style={{ display: "inline-flex", padding: "3px 9px", borderRadius: 6, fontFamily: "monospace", fontSize: 10.5, fontWeight: 700, background: `${sportColor(workout.sport)}22`, color: sportColor(workout.sport) }}>
          {sportLabel(workout.sport)}
        </div>
      </div>
      <div onClick={onOpen} style={{ cursor: "pointer" }}>
        <div style={{ fontSize: 15, fontWeight: 700, color: "var(--ink)", margin: "10px 0 2px", letterSpacing: "-0.01em" }}>{workout.name}</div>
        <div style={{ fontFamily: "monospace", fontSize: 12, color: "var(--ink3)" }}>
          {fmtDuration(workout.duration)} · {workout.tss} TSS
        </div>
        <MiniChart preview={workout.chart_preview} color={sportColor(workout.sport)} />
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 5, marginTop: 10 }}>
        {workout.tags.map((t) => (
          <span key={t} style={{ fontSize: 10.5, fontWeight: 600, color: "var(--ink3)", background: "var(--elev)", padding: "3px 8px", borderRadius: 20 }}>
            {t}
          </span>
        ))}
        <FolderPicker workout={workout} folders={folders} onMoveFolder={actions.onMoveFolder} />
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 6, marginTop: 12, paddingTop: 10, borderTop: "1px solid var(--line)" }}>
        <ActionButtons {...actions} />
      </div>
    </div>
  );
}

function WorkoutRow({
  workout,
  folders,
  selected,
  onToggleSelect,
  onOpen,
  ...actions
}: { workout: Workout; folders: WorkoutFolder[]; selected: boolean; onToggleSelect: () => void; onOpen: () => void } & RowActions) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "26px minmax(150px,1.4fr) 80px 90px 70px 1fr 110px 150px", gap: 10, padding: "12px 16px", borderBottom: "1px solid var(--line)", alignItems: "center" }}>
      <CheckBox checked={selected} onClick={onToggleSelect} />
      <div onClick={onOpen} style={{ cursor: "pointer", minWidth: 0, fontSize: 13.5, fontWeight: 700, color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
        {workout.name}
      </div>
      <div style={{ display: "inline-flex", padding: "3px 9px", borderRadius: 6, fontFamily: "monospace", fontSize: 10.5, fontWeight: 700, background: `${sportColor(workout.sport)}22`, color: sportColor(workout.sport), justifySelf: "start" }}>
        {sportLabel(workout.sport)}
      </div>
      <span style={{ fontFamily: "monospace", fontSize: 12.5, color: "var(--ink2)" }}>{fmtDuration(workout.duration)}</span>
      <span style={{ fontFamily: "monospace", fontSize: 12.5, color: "var(--ink2)" }}>{workout.tss}</span>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 4, minWidth: 0 }}>
        {workout.tags.map((t) => (
          <span key={t} style={{ fontSize: 10.5, fontWeight: 600, color: "var(--ink3)", background: "var(--elev)", padding: "2px 7px", borderRadius: 20 }}>
            {t}
          </span>
        ))}
      </div>
      <FolderPicker workout={workout} folders={folders} onMoveFolder={actions.onMoveFolder} />
      <div style={{ display: "flex", alignItems: "center", gap: 5, justifySelf: "end" }}>
        <ActionButtons {...actions} />
      </div>
    </div>
  );
}

function CheckBox({ checked, onClick }: { checked: boolean; onClick: () => void }) {
  return (
    <div
      onClick={(e) => (e.stopPropagation(), onClick())}
      title="Select"
      style={{
        width: 20,
        height: 20,
        borderRadius: 6,
        border: `1.5px solid ${checked ? "var(--ember)" : "var(--line)"}`,
        background: checked ? "var(--ember)" : "transparent",
        color: "#fff",
        fontSize: 12,
        fontWeight: 700,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        cursor: "pointer",
        flexShrink: 0,
      }}
    >
      {checked ? "✓" : ""}
    </div>
  );
}

function MiniChart({ preview, color }: { preview: number[]; color: string }) {
  if (preview.length === 0) return null;
  return (
    <div style={{ height: 44, display: "flex", alignItems: "flex-end", gap: 1, background: "var(--elev)", borderRadius: 6, overflow: "hidden", marginTop: 10 }}>
      {preview.map((v, i) => (
        <div key={i} style={{ flex: 1, height: "100%", display: "flex", alignItems: "flex-end" }}>
          <div style={{ width: "100%", height: `${Math.min(100, (v / 150) * 100)}%`, background: color, opacity: 0.55 }} />
        </div>
      ))}
    </div>
  );
}

function bulkActionStyle(color = "var(--ink2)") {
  return { fontSize: 12.5, fontWeight: 600, color, cursor: "pointer", background: "none", border: "none" };
}
