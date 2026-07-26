import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createWorkout, getWorkout, updateWorkout, type WorkoutInput } from "../../api/workouts";
import { getContexts } from "../../api/auth";
import { scheduleWorkout } from "../../api/scheduling";
import { useAuth } from "../../auth/AuthContext";
import { Card } from "../../components/Card";
import type { WorkoutSport } from "../../api/types";
import { WorkoutChart } from "./WorkoutChart";
import { WorkoutStructureList, type StructureActions } from "./WorkoutStructureList";
import { StepDrawer } from "./StepDrawer";
import { TemplatesPanel } from "./TemplatesPanel";
import { ExportModal } from "./ExportModal";
import {
  defaultGroup,
  defaultLeaf,
  duplicateStep,
  findStep,
  fmtDuration,
  mapSteps,
  moveStep,
  removeStep,
  stepCount,
  stripIds,
  totalDuration,
  totalTss,
  withIds,
  type Step,
} from "./workoutTree";

const SPORTS: WorkoutSport[] = ["bike", "run"];

export function WorkoutEditor({ workoutId, onDone }: { workoutId: string | "new"; onDone: () => void }) {
  const isNew = workoutId === "new";
  const { data: existing } = useQuery({
    queryKey: ["workout", workoutId],
    queryFn: () => getWorkout(workoutId),
    enabled: !isNew,
  });

  if (!isNew && !existing) {
    return <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading…</div>;
  }

  return <WorkoutEditorForm workoutId={workoutId} initial={existing ?? null} onDone={onDone} />;
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
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
  const [publishOpen, setPublishOpen] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  const effectiveIsNew = currentId === null;
  const totalDur = totalDuration(steps);
  const tss = totalTss(steps);
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
      setSavedAt(Date.now());
    },
  });

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
            <WorkoutChart steps={steps} selectedId={selectedId} onSelect={setSelectedId} compact={layout === "list"} />
          </div>

          <div>
            <div style={{ fontSize: 15, fontWeight: 700, color: "var(--ink)", marginBottom: 12 }}>Structure</div>
            <WorkoutStructureList steps={steps} actions={actions} />
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
          />
        </div>
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 20 }}>
        <button
          onClick={() => saveMutation.mutate()}
          disabled={!name.trim() || saveMutation.isPending}
          style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", cursor: "pointer" }}
        >
          {savedAt ? "Saved ✓" : "Save draft"}
        </button>
        <button
          onClick={() => setPublishOpen(true)}
          disabled={effectiveIsNew}
          title={effectiveIsNew ? "Save the workout first" : undefined}
          style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, fontWeight: 600, padding: "8px 16px", cursor: "pointer" }}
        >
          Publish & assign
        </button>
        <button onClick={onDone} style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, padding: "8px 16px", cursor: "pointer" }}>
          Back to list
        </button>
      </div>

      {templatesOpen && <TemplatesPanel sport={sport} onInsert={(newSteps) => setSteps([...steps, ...newSteps])} onClose={() => setTemplatesOpen(false)} />}
      {exportOpen && <ExportModal name={name} sport={sport} steps={steps} onClose={() => setExportOpen(false)} />}
      {publishOpen && currentId && (
        <PublishModal
          workoutId={currentId}
          workoutName={name}
          selfId={user?.id ?? ""}
          isCoach={!!user?.is_coach}
          onClose={() => setPublishOpen(false)}
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

function PublishModal({
  workoutId,
  workoutName,
  selfId,
  isCoach,
  onClose,
}: {
  workoutId: string;
  workoutName: string;
  selfId: string;
  isCoach: boolean;
  onClose: () => void;
}) {
  const { data: contexts } = useQuery({ queryKey: ["contexts"], queryFn: getContexts, enabled: isCoach });
  const [selected, setSelected] = useState<Record<string, boolean>>({ [selfId]: true });
  const [date, setDate] = useState(todayIso());

  const athletes = [{ id: selfId, name: "Me" }, ...(contexts?.coaching ?? []).map((a) => ({ id: a.user_id, name: a.name }))];

  const publishMutation = useMutation({
    mutationFn: async () => {
      const ids = Object.entries(selected)
        .filter(([, v]) => v)
        .map(([id]) => id);
      await Promise.all(ids.map((athleteId) => scheduleWorkout({ workout_id: workoutId, athlete_id: athleteId, date })));
    },
    onSuccess: onClose,
  });

  const assignedCount = Object.values(selected).filter(Boolean).length;

  return (
    <div onClick={onClose} style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100, padding: 32 }}>
      <div onClick={(e) => e.stopPropagation()} style={{ width: 420, maxWidth: "100%", background: "var(--card)", borderRadius: 18, display: "flex", flexDirection: "column", overflow: "hidden" }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "18px 22px", borderBottom: "1px solid var(--line)" }}>
          <div style={{ fontSize: 16, fontWeight: 800, color: "var(--ink)" }}>Publish & assign</div>
          <button onClick={onClose} style={{ width: 30, height: 30, borderRadius: 8, border: "1px solid var(--line)", background: "none", color: "var(--ink3)", cursor: "pointer", fontSize: 18 }}>
            ×
          </button>
        </div>
        <div style={{ padding: "18px 22px", display: "flex", flexDirection: "column", gap: 14 }}>
          <div style={{ fontSize: 13, color: "var(--ink2)" }}>{workoutName}</div>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={{ fontSize: 11, fontWeight: 600, color: "var(--ink3)" }}>ASSIGN TO</label>
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              {athletes.map((a) => (
                <div
                  key={a.id}
                  onClick={() => setSelected((s) => ({ ...s, [a.id]: !s[a.id] }))}
                  style={{
                    padding: "7px 13px",
                    borderRadius: 20,
                    border: `1px solid ${selected[a.id] ? "var(--ember)" : "var(--line)"}`,
                    background: selected[a.id] ? "rgba(236,74,38,0.08)" : "transparent",
                    color: selected[a.id] ? "var(--ember)" : "var(--ink2)",
                    fontSize: 12.5,
                    fontWeight: 600,
                    cursor: "pointer",
                  }}
                >
                  {a.name}
                </div>
              ))}
            </div>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={{ fontSize: 11, fontWeight: 600, color: "var(--ink3)" }}>DATE</label>
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              style={{ padding: "9px 11px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--elev)", fontSize: 13, fontFamily: "monospace", fontWeight: 600, color: "var(--ink)" }}
            />
          </div>
        </div>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "flex-end", gap: 9, padding: "14px 22px", borderTop: "1px solid var(--line)", background: "var(--elev)" }}>
          <button onClick={onClose} style={{ padding: "9px 16px", borderRadius: 9, border: "1px solid var(--line)", background: "var(--card)", fontSize: 13, fontWeight: 600, color: "var(--ink2)", cursor: "pointer" }}>
            Cancel
          </button>
          <button
            onClick={() => publishMutation.mutate()}
            disabled={assignedCount === 0 || !date || publishMutation.isPending}
            style={{ padding: "9px 18px", borderRadius: 9, border: "none", background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, cursor: "pointer" }}
          >
            {assignedCount ? `Assign to ${assignedCount}` : "Publish"}
          </button>
        </div>
      </div>
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
