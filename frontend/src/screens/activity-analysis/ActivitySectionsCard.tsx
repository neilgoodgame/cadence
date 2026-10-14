import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getActivitySections, recomputeActivityElevation } from "../../api/activities";
import type { Activity } from "../../api/types";
import { SectionsDetail } from "../../components/SectionsDetail";

const QUALITY_NOTICE: Record<"missing" | "sparse", string> = {
  missing: "This activity has no usable altitude recorded, so climbs and flats can't be shown.",
  sparse: "This activity's samples are too far apart to show climbs and flats reliably.",
};

/** The activity's elevation split into flat, climb and descent sections - the same calculation
 * and settings (Preferences › Courses) as a race course. Hidden for indoor activities without
 * usable altitude, where there's nothing to show. */
export function ActivitySectionsCard({ activity }: { activity: Activity }) {
  const queryClient = useQueryClient();
  const { data } = useQuery({
    queryKey: ["activity-sections", activity.id],
    queryFn: () => getActivitySections(activity.id),
  });
  const rebuild = useMutation({
    mutationFn: () => recomputeActivityElevation(activity.id),
    onSuccess: (updated) => queryClient.setQueryData(["activity-sections", activity.id], updated),
  });

  if (!data) return null;
  if (data.elevation_quality !== "ok" && activity.environment === "indoor") return null;

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: "16px 22px" }}>
      <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 8, marginBottom: 10, flexWrap: "wrap" }}>
        <span style={{ fontSize: 14, fontWeight: 700, color: "var(--ink)" }}>Elevation sections</span>
        <span style={{ fontSize: 11.5, color: "var(--ink3)" }}>Adjust in Preferences › Courses</span>
      </div>
      {data.elevation_quality === "ok" ? (
        <SectionsDetail profile={data.elevation_profile} sections={data.sections} />
      ) : (
        <div style={{ fontSize: 12.5, color: "var(--ink2)", background: "var(--elev)", borderRadius: 8, padding: "10px 12px" }}>
          {QUALITY_NOTICE[data.elevation_quality]}
          <div style={{ marginTop: 6 }}>
            <button
              onClick={() => rebuild.mutate()}
              disabled={rebuild.isPending}
              style={{ border: "none", background: "none", padding: 0, color: "var(--ember)", fontSize: 12.5, fontWeight: 600, cursor: "pointer" }}
            >
              {rebuild.isPending ? "Rebuilding…" : "Rebuild from the recording"}
            </button>
            {rebuild.isError && <span style={{ marginLeft: 8, color: "#e0442e" }}>{(rebuild.error as Error).message}</span>}
          </div>
        </div>
      )}
    </div>
  );
}
