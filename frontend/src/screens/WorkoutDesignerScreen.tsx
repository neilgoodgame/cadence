import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createWorkout, deleteWorkout, getWorkout, listWorkouts } from "../api/workouts";
import { Card } from "../components/Card";
import { formatDuration } from "../lib/format";
import { sportColor, sportLabel } from "../lib/sportColors";
import { WorkoutEditor } from "./workouts/WorkoutEditor";

export function WorkoutDesignerScreen() {
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: ["workouts"], queryFn: listWorkouts });
  const [editing, setEditing] = useState<string | "new" | null>(null);

  const deleteMutation = useMutation({
    mutationFn: deleteWorkout,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["workouts"] }),
  });

  const duplicateMutation = useMutation({
    mutationFn: async (id: string) => {
      const original = await getWorkout(id);
      return createWorkout({ name: `${original.name} (copy)`, sport: original.sport, steps: original.steps });
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["workouts"] }),
  });

  if (editing !== null) {
    return <WorkoutEditor workoutId={editing} onDone={() => setEditing(null)} />;
  }

  const workouts = data?.data ?? [];

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>Workouts</h1>
        <button
          onClick={() => setEditing("new")}
          style={{ border: "none", borderRadius: 10, background: "var(--ember)", color: "#fff", fontSize: 14, fontWeight: 700, padding: "10px 20px" }}
        >
          + New workout
        </button>
      </div>

      {workouts.length === 0 ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>No workouts designed yet.</div>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          {workouts.map((workout) => (
            <Card key={workout.id} style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <div>
                <div style={{ fontSize: 15, fontWeight: 700 }}>{workout.name}</div>
                <div style={{ display: "flex", gap: 10, marginTop: 4, alignItems: "center" }}>
                  <span
                    style={{
                      fontSize: 11,
                      fontWeight: 600,
                      color: sportColor(workout.sport),
                      background: `${sportColor(workout.sport)}22`,
                      borderRadius: 20,
                      padding: "2px 8px",
                    }}
                  >
                    {sportLabel(workout.sport)}
                  </span>
                  <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>
                    {formatDuration(workout.duration)} · {workout.tss} TSS
                  </span>
                </div>
              </div>
              <div style={{ display: "flex", gap: 14 }}>
                <button onClick={() => setEditing(workout.id)} style={{ border: "none", background: "none", color: "var(--ember)", fontSize: 13, fontWeight: 600 }}>
                  Edit
                </button>
                <button
                  onClick={() => duplicateMutation.mutate(workout.id)}
                  style={{ border: "none", background: "none", color: "var(--ink2)", fontSize: 13, fontWeight: 600 }}
                >
                  Duplicate
                </button>
                <button
                  onClick={() => deleteMutation.mutate(workout.id)}
                  style={{ border: "none", background: "none", color: "#e0442e", fontSize: 13, fontWeight: 600 }}
                >
                  Delete
                </button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
