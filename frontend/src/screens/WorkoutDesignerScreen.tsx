import { useState } from "react";
import { WorkoutEditor } from "./workouts/WorkoutEditor";
import { WorkoutLibraryScreen } from "./workouts/WorkoutLibraryScreen";

export function WorkoutDesignerScreen() {
  const [editing, setEditing] = useState<string | "new" | null>(null);

  if (editing !== null) {
    return <WorkoutEditor workoutId={editing} onDone={() => setEditing(null)} />;
  }

  return <WorkoutLibraryScreen onEdit={(id) => setEditing(id)} onNew={() => setEditing("new")} />;
}
