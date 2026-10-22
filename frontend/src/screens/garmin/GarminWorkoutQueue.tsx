import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { discardGarminWorkoutOp, listGarminWorkoutOps } from "../../api/garmin";
import type { GarminWorkoutOp } from "../../api/types";
import { formatDateTime } from "../../lib/format";
import { refreshGarminQueries } from "./garminWorkouts";

const KIND_LABEL: Record<GarminWorkoutOp["kind"], string> = {
  upsert_workout: "Push workout",
  schedule: "Add to calendar",
  reschedule: "Move on calendar",
  unschedule: "Remove from calendar",
};

function describe(op: GarminWorkoutOp): string {
  const what = op.name ? `${KIND_LABEL[op.kind]} · ${op.name}` : KIND_LABEL[op.kind];
  return op.date ? `${what} · ${op.date}` : what;
}

const STATUS_COLOR: Record<GarminWorkoutOp["status"], string> = {
  pending: "var(--ink3)",
  done: "#2fa66a",
  failed: "#e0442e",
};

/** Workouts are pushed to Garmin Connect by `garmin-sync push-workouts` on the athlete's own machine
 * (the Personal API key has no workout routes, and Cadence never holds Garmin credentials): this
 * shows what is queued for it and what it has done. */
export function GarminWorkoutQueue() {
  const queryClient = useQueryClient();
  const { data } = useQuery({
    queryKey: ["garmin-workout-ops"],
    queryFn: () => listGarminWorkoutOps("all"),
    refetchInterval: (query) => (query.state.data?.data.some((o) => o.status === "pending") ? 10_000 : false),
  });
  const discard = useMutation({
    mutationFn: discardGarminWorkoutOp,
    onSuccess: () => refreshGarminQueries(queryClient),
  });
  const ops = data?.data ?? [];
  const pending = ops.filter((o) => o.status === "pending").length;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12, borderTop: "1px solid var(--line)", paddingTop: 20 }}>
      <div style={{ fontSize: 15, fontWeight: 700 }}>Workouts on Garmin Connect</div>
      <p style={{ fontSize: 13, color: "var(--ink3)", margin: 0 }}>
        Your Personal API key can't create workouts, so pushes are queued here and applied by{" "}
        <code>garmin-sync push-workouts</code> on your computer, using your own Garmin login — Cadence never sees it. Run it
        whenever there is something pending
        {pending > 0 ? ` (${pending} waiting now)` : ""}:
      </p>
      <pre style={{ fontSize: 12, margin: 0, padding: 10, borderRadius: 8, background: "var(--elev)", overflowX: "auto" }}>
        {`export GARMIN_SYNC_URL=${import.meta.env.VITE_API_BASE_URL}\nexport GARMIN_SYNC_TOKEN=<a Cadence API token>\ngarmin-sync push-workouts`}
      </pre>
      {ops.length === 0 ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>Nothing queued yet. Tick "Push to Garmin Connect" when you save or schedule a workout.</div>
      ) : (
        <div>
          {ops.slice(0, 20).map((op) => (
            <div key={op.id} style={{ display: "flex", justifyContent: "space-between", gap: 12, padding: "8px 0", borderTop: "1px solid var(--line)", fontSize: 13 }}>
              <div>
                <div>{describe(op)}</div>
                <div style={{ color: op.status === "failed" ? "#e0442e" : "var(--ink3)", fontSize: 12 }}>
                  {op.error ?? formatDateTime(op.completed_at ?? op.created)}
                </div>
              </div>
              <div style={{ display: "flex", gap: 10, alignItems: "center", whiteSpace: "nowrap" }}>
                <span style={{ fontWeight: 600, color: STATUS_COLOR[op.status] }}>{op.status}</span>
                {op.status !== "done" && (
                  <button
                    onClick={() => discard.mutate(op.id)}
                    disabled={discard.isPending}
                    style={{ border: "none", background: "none", fontSize: 12, color: "var(--ink3)" }}
                  >
                    Discard
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
