import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createWorkout, getWorkout, updateWorkout, type WorkoutInput } from "../../api/workouts";
import { Card } from "../../components/Card";
import type { WorkoutSport, WorkoutStep } from "../../api/types";
import { StepRow } from "./StepRow";

const SPORTS: WorkoutSport[] = ["bike", "run"];

function emptyStep(): WorkoutStep {
  return { kind: "block", end_type: "time", duration: 300, distance: null, target_pct: 100, repeat: 1 };
}

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

function WorkoutEditorForm({
  workoutId,
  initial,
  onDone,
}: {
  workoutId: string | "new";
  initial: { name: string; sport: WorkoutSport; steps: WorkoutStep[] } | null;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const isNew = workoutId === "new";

  const [name, setName] = useState(initial?.name ?? "");
  const [sport, setSport] = useState<WorkoutSport>(initial?.sport ?? "bike");
  const [steps, setSteps] = useState<WorkoutStep[]>(initial?.steps ?? [emptyStep()]);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["workouts"] });
  const saveMutation = useMutation({
    mutationFn: () => {
      const input: WorkoutInput = { name, sport, steps };
      return isNew ? createWorkout(input) : updateWorkout(workoutId, input);
    },
    onSuccess: () => {
      invalidate();
      onDone();
    },
  });

  function updateStep(index: number, step: WorkoutStep) {
    setSteps(steps.map((s, i) => (i === index ? step : s)));
  }
  function removeStep(index: number) {
    setSteps(steps.filter((_, i) => i !== index));
  }
  function moveStep(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= steps.length) return;
    const next = [...steps];
    [next[index], next[target]] = [next[target], next[index]];
    setSteps(next);
  }

  return (
    <Card>
      <div style={{ display: "flex", gap: 12, marginBottom: 16, alignItems: "flex-end" }}>
        <label style={{ flex: 1 }}>
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
      </div>

      <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 4 }}>Steps</div>
      <div style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 8 }}>
        Duration and TSS are computed on save from these steps - there's no live preview.
      </div>
      {steps.map((step, i) => (
        <StepRow
          key={i}
          step={step}
          onChange={(s) => updateStep(i, s)}
          onRemove={() => removeStep(i)}
          onMoveUp={() => moveStep(i, -1)}
          onMoveDown={() => moveStep(i, 1)}
        />
      ))}
      <button
        onClick={() => setSteps([...steps, emptyStep()])}
        style={{ border: "none", background: "none", color: "var(--ember)", fontSize: 12, fontWeight: 600, marginTop: 10, padding: 0 }}
      >
        + Add step
      </button>

      <div style={{ display: "flex", gap: 8, marginTop: 20 }}>
        <button
          onClick={() => saveMutation.mutate()}
          disabled={!name.trim() || steps.length === 0 || saveMutation.isPending}
          style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px" }}
        >
          Save
        </button>
        <button onClick={onDone} style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, padding: "8px 16px" }}>
          Cancel
        </button>
      </div>
    </Card>
  );
}
