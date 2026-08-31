import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { InferredWorkout } from "../../api/activities";
import { createWorkout, getWorkout, updateWorkout, type WorkoutInput } from "../../api/workouts";
import { useAuth } from "../../auth/AuthContext";
import { Card } from "../../components/Card";
import { ApiError, type WorkoutSport } from "../../api/types";
import { WorkoutChart } from "./WorkoutChart";
import { WorkoutStructureList, type StructureActions } from "./WorkoutStructureList";
import { StepDrawer } from "./StepDrawer";
import { TemplatesPanel } from "./TemplatesPanel";
import { ExportModal } from "./ExportModal";
import { parseThresholdPaceSecPerKm } from "./workoutExport";
import { AssignModal } from "./AssignModal";
import {
  defaultGroup,
  defaultLeaf,
  duplicateStep,
  findStep,
  fmtDuration,
  mapSteps,
  moveStep,
  normalizePowerUnits,
  removeStep,
  stepCount,
  stripIds,
  totalDuration,
  totalTss,
  withIds,
  type Step,
} from "./workoutTree";

const SPORTS: WorkoutSport[] = ["bike", "run"];

export function WorkoutEditor({
  workoutId,
  initialDraft,
  onDone,
}: {
  workoutId: string | "new";
  /** Pre-populates a "new" editor (e.g. a workout inferred from an activity's laps) instead of the blank default. Ignored when editing an existing workout. */
  initialDraft?: InferredWorkout | null;
  onDone: () => void;
}) {
  const isNew = workoutId === "new";
  const { data: existing } = useQuery({
    queryKey: ["workout", workoutId],
    queryFn: () => getWorkout(workoutId),
    enabled: !isNew,
  });

  if (!isNew && !existing) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading…</div>;
  }

  return <WorkoutEditorForm workoutId={workoutId} initial={isNew ? (initialDraft ?? null) : (existing ?? null)} onDone={onDone} />;
}

function WorkoutEditorForm({
  workoutId,
  initial,
  onDone,
}: {
  workoutId: string | "new";
  initial: { name: string; sport: WorkoutSport; steps: WorkoutInput["steps"] } | null;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const isNewRoute = workoutId === "new";

  const [currentId, setCurrentId] = useState<string | null>(isNewRoute ? null : (workoutId as string));
  const [name, setName] = useState(initial?.name ?? "New workout");
  const [sport, setSport] = useState<WorkoutSport>(initial?.sport ?? "bike");
  const [steps, setSteps] = useState<Step[]>(() => (initial ? withIds(initial.steps) : [defaultLeaf("warmup"), defaultLeaf("cool")]));
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [layout, setLayout] = useState<"list" | "chart">("list");
  const [templatesOpen, setTemplatesOpen] = useState(false);
  const [startFromOpen, setStartFromOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const [assignOpen, setAssignOpen] = useState(false);

  const effectiveIsNew = currentId === null;
  const powerReferenceWatts = sport === "bike" ? (user?.ftp ?? null) : (user?.critical_run_power ?? null);
  const thresholdPaceSecPerKm = parseThresholdPaceSecPerKm(user?.threshold_pace);
  const normalizedSteps = useMemo(() => normalizePowerUnits(steps, powerReferenceWatts), [steps, powerReferenceWatts]);
  const totalDur = totalDuration(normalizedSteps, sport, thresholdPaceSecPerKm);
  const tss = totalTss(normalizedSteps, sport, thresholdPaceSecPerKm);
  const count = stepCount(steps);
  const selected = findStep(steps, selectedId);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["workouts"] });
  const saveMutation = useMutation({
    mutationFn: () => {
      const input: WorkoutInput = { name, sport, steps: stripIds(steps) };
      return effectiveIsNew ? createWorkout(input) : updateWorkout(currentId!, input);
    },
    onSuccess: (workout) => {
      invalidate();
      setCurrentId(workout.id);
      onDone();
    },
  });
  const saveError = saveMutation.isError
    ? saveMutation.error instanceof ApiError
      ? saveMutation.error.message
      : "Couldn't save the workout. Try again."
    : null;

  const actions: StructureActions = {
    selectedId,
    onSelect: setSelectedId,
    onMoveUp: (id) => setSteps(moveStep(steps, id, -1)),
    onMoveDown: (id) => setSteps(moveStep(steps, id, 1)),
    onDuplicate: (id) => setSteps(duplicateStep(steps, id)),
    onRemove: (id) => {
      setSteps(removeStep(steps, id));
      if (selectedId === id) setSelectedId(null);
    },
    onRepeatChange: (id, repeat) => setSteps(mapSteps(steps, id, (s) => ({ ...s, repeat }))),
    onAddChild: (groupId) =>
      setSteps(
        mapSteps(steps, groupId, (s) =>
          s.kind === "repeat" ? { ...s, children: [...s.children, defaultLeaf("block", sport)] } : s,
        ),
      ),
    onAddNestedGroup: (groupId) =>
      setSteps(
        mapSteps(steps, groupId, (s) => (s.kind === "repeat" ? { ...s, children: [...s.children, defaultGroup(sport)] } : s)),
      ),
  };

  return (
    <Card>
      <div style={{ display: "flex", gap: 12, marginBottom: 16, alignItems: "flex-end", flexWrap: "wrap" }}>
        <label style={{ flex: 1, minWidth: 200 }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Name</div>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            style={{ width: "100%", padding: "8px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)" }}
          />
        </label>
        <label>
          <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 6 }}>Sport</div>
          <select
            value={sport}
            onChange={(e) => setSport(e.target.value as WorkoutSport)}
            style={{ padding: "8px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", color: "var(--ink)" }}
          >
            {SPORTS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          <button onClick={() => setStartFromOpen((v) => !v)} style={secondaryBtnStyle}>
            Start from ▾
          </button>
          <button onClick={() => setTemplatesOpen(true)} style={secondaryBtnStyle}>
            Templates
          </button>
          <button onClick={() => setExportOpen(true)} style={secondaryBtnStyle}>
            Export
          </button>
        </div>
      </div>

      {startFromOpen && (
        <div style={{ display: "flex", gap: 8, marginBottom: 16, padding: 10, borderRadius: 10, border: "1px solid var(--line)", background: "var(--elev)" }}>
          <button
            onClick={() => {
              setSteps([defaultLeaf("warmup"), defaultLeaf("cool")]);
              setName("New workout");
              setSelectedId(null);
              setStartFromOpen(false);
            }}
            style={{ ...secondaryBtnStyle, background: "var(--card)" }}
          >
            New blank workout
          </button>
          {initial && (
            <button
              onClick={() => {
                setSteps(withIds(initial.steps));
                setName(`${initial.name} copy`);
                setStartFromOpen(false);
              }}
              style={{ ...secondaryBtnStyle, background: "var(--card)" }}
            >
              Duplicate “{initial.name}”
            </button>
          )}
        </div>
      )}

      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 16, flexWrap: "wrap", marginBottom: 16 }}>
        <div style={{ display: "flex", gap: 22, flexWrap: "wrap" }}>
          <SummaryStat label="DURATION" value={fmtDuration(totalDur)} />
          <SummaryStat label="EST. TSS" value={String(tss)} />
          <SummaryStat label="STEPS" value={String(count)} />
        </div>
        <div style={{ display: "flex", gap: 3, background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 9, padding: 3 }}>
          <button onClick={() => setLayout("list")} style={segBtnStyle(layout === "list")}>
            List view
          </button>
          <button onClick={() => setLayout("chart")} style={segBtnStyle(layout === "chart")}>
            Chart-first
          </button>
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 320px", gap: 16, alignItems: "start" }}>
        <div style={{ display: "flex", flexDirection: "column", gap: 16, minWidth: 0 }}>
          <div style={{ background: "var(--elev)", borderRadius: 14, padding: layout === "chart" ? "20px 22px 16px" : "14px 18px" }}>
            <WorkoutChart
              steps={normalizedSteps}
              sport={sport}
              thresholdPaceSecPerKm={thresholdPaceSecPerKm}
              selectedId={selectedId}
              onSelect={setSelectedId}
              compact={layout === "list"}
            />
          </div>

          <div>
            <div style={{ fontSize: 15, fontWeight: 700, color: "var(--ink)", marginBottom: 12 }}>Structure</div>
            <WorkoutStructureList
              steps={steps}
              actions={actions}
              powerReferenceWatts={powerReferenceWatts}
              sport={sport}
              thresholdPaceSecPerKm={thresholdPaceSecPerKm}
            />
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 14, paddingTop: 14, borderTop: "1px solid var(--line)" }}>
              <button onClick={() => setSteps([...steps, defaultLeaf("warmup", sport)])} style={dashedBtnStyle()}>
                + Warm-up
              </button>
              <button onClick={() => setSteps([...steps, defaultLeaf("block", sport)])} style={dashedBtnStyle()}>
                + Work block
              </button>
              <button onClick={() => setSteps([...steps, defaultLeaf("rec", sport)])} style={dashedBtnStyle()}>
                + Recovery
              </button>
              <button onClick={() => setSteps([...steps, defaultLeaf("cool", sport)])} style={dashedBtnStyle()}>
                + Cooldown
              </button>
              <button onClick={() => setSteps([...steps, defaultGroup(sport)])} style={dashedBtnStyle("var(--ember)")}>
                + Repeat group
              </button>
            </div>
          </div>
        </div>

        <div style={{ background: "var(--elev)", borderRadius: 14, padding: "20px 22px", position: "sticky", top: 16 }}>
          <StepDrawer
            step={selected}
            onChange={(updated) => setSteps(mapSteps(steps, updated.id, () => updated))}
            onRemove={(id) => {
              setSteps(removeStep(steps, id));
              setSelectedId(null);
            }}
            onClose={() => setSelectedId(null)}
            powerReferenceWatts={powerReferenceWatts}
          />
        </div>
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 20 }}>
        <button
          onClick={() => saveMutation.mutate()}
          disabled={!name.trim() || saveMutation.isPending}
          style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", cursor: "pointer" }}
        >
          Save
        </button>
        <button
          onClick={() => setAssignOpen(true)}
          disabled={effectiveIsNew}
          title={effectiveIsNew ? "Save the workout first" : undefined}
          style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, fontWeight: 600, padding: "8px 16px", cursor: "pointer" }}
        >
          Publish & assign
        </button>
        <button onClick={onDone} style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, padding: "8px 16px", cursor: "pointer" }}>
          Back to list
        </button>
        {saveError && <span style={{ alignSelf: "center", fontSize: 13, color: "#e0442e" }}>{saveError}</span>}
      </div>

      {templatesOpen && <TemplatesPanel sport={sport} onInsert={(newSteps) => setSteps([...steps, ...newSteps])} onClose={() => setTemplatesOpen(false)} />}
      {exportOpen && <ExportModal name={name} sport={sport} steps={steps} onClose={() => setExportOpen(false)} />}
      {assignOpen && currentId && (
        <AssignModal
          workoutId={currentId}
          workoutName={name}
          selfId={user?.id ?? ""}
          isCoach={!!user?.is_coach}
          onClose={() => setAssignOpen(false)}
        />
      )}
    </Card>
  );
}

function SummaryStat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div style={{ fontFamily: "monospace", fontSize: 9, letterSpacing: "0.08em", color: "var(--ink3)" }}>{label}</div>
      <div style={{ fontSize: 16, fontWeight: 700, color: "var(--ink)" }}>{value}</div>
    </div>
  );
}

const secondaryBtnStyle = {
  display: "flex",
  alignItems: "center",
  gap: 6,
  padding: "7px 13px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--card)",
  fontSize: 12.5,
  fontWeight: 600,
  color: "var(--ink2)",
  cursor: "pointer",
} as const;

function segBtnStyle(active: boolean) {
  return {
    flex: 1,
    textAlign: "center" as const,
    padding: "5px 11px",
    fontSize: 12,
    fontWeight: 600,
    borderRadius: 7,
    cursor: "pointer",
    border: "none",
    color: active ? "var(--ink)" : "var(--ink3)",
    background: active ? "var(--card)" : "transparent",
  };
}

function dashedBtnStyle(color = "var(--ink2)") {
  return {
    padding: "7px 13px",
    borderRadius: 8,
    border: `1px dashed ${color === "var(--ink2)" ? "var(--line)" : color}`,
    background: "none",
    fontSize: 12.5,
    fontWeight: 600,
    color,
    cursor: "pointer",
  };
}
