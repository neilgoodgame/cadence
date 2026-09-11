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
  // Editing an *existing* workout now routes to /workouts/:id (WorkoutDetailScreen) instead of
  // going through this component - only "creating a new one" has nowhere read-only to show, so
  // it stays here as local state.
  const [creating, setCreating] = useState(() => readInferredDraft(location.state) !== null);
  const [initialDraft, setInitialDraft] = useState<InferredWorkout | null>(() => readInferredDraft(location.state));

  // Clears the draft from history (a pure navigation side effect, not local state) so a later
  // refresh on this same URL doesn't silently reopen the same draft.
  useEffect(() => {
    if (readInferredDraft(location.state)) {
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.state, location.pathname, navigate]);

  if (creating) {
    return (
      <WorkoutEditor
        workoutId="new"
        initialDraft={initialDraft}
        onDone={() => {
          setCreating(false);
          setInitialDraft(null);
        }}
      />
    );
  }

  return <WorkoutLibraryScreen onEdit={(id) => navigate(`/workouts/${id}`)} onNew={() => setCreating(true)} />;
}
