import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import type { InferredWorkout } from "../api/activities";
import { WorkoutEditor } from "./workouts/WorkoutEditor";
import { WorkoutLibraryScreen } from "./workouts/WorkoutLibraryScreen";

function readInferredDraft(state: unknown): InferredWorkout | null {
  return (state as { inferredDraft?: InferredWorkout } | null)?.inferredDraft ?? null;
}

export function WorkoutDesignerScreen() {
  const location = useLocation();
  const navigate = useNavigate();

  // Arriving here from the activity Laps tab's "Create workout from laps" action carries the
  // inferred draft in router state - read once via lazy init (this component only mounts fresh
  // when navigated to from elsewhere, which is the only way that action reaches this screen).
  const [editing, setEditing] = useState<string | "new" | null>(() => (readInferredDraft(location.state) ? "new" : null));
  const [initialDraft, setInitialDraft] = useState<InferredWorkout | null>(() => readInferredDraft(location.state));

  // Clears the draft from history (a pure navigation side effect, not local state) so a later
  // refresh on this same URL doesn't silently reopen the same draft.
  useEffect(() => {
    if (readInferredDraft(location.state)) {
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.state, location.pathname, navigate]);

  if (editing !== null) {
    return (
      <WorkoutEditor
        workoutId={editing}
        initialDraft={editing === "new" ? initialDraft : null}
        onDone={() => {
          setEditing(null);
          setInitialDraft(null);
        }}
      />
    );
  }

  return <WorkoutLibraryScreen onEdit={(id) => setEditing(id)} onNew={() => setEditing("new")} />;
}
