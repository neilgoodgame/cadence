import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listActivities } from "../../api/activities";
import { LinkedActivitiesList, LinkedCountBadge, type LinkedActivityRowData } from "../../components/LinkedActivityRow";
import { formatDate, formatKeyMetric } from "../../lib/format";

/** The activities a bike or pair of shoes has been used for - expandable section on its gear
 * card, reusing the same LinkedActivityRow list the activity screen's matched-workout card
 * uses. Shows the 10 most recent; the gear <-> activity link itself is edited from the activity
 * screen's header (GearBadge), not here. */
export function GearActivitiesSection({ filter }: { filter: { bike_id: string } | { shoe_id: string } }) {
  const [open, setOpen] = useState(false);
  const filterKey = "bike_id" in filter ? filter.bike_id : filter.shoe_id;

  const { data } = useQuery({
    queryKey: ["activities", filter],
    queryFn: () => listActivities({ ...filter, sort: "-date", limit: 10 }),
    enabled: open,
  });

  const activities: LinkedActivityRowData[] =
    data?.data.map((a) => ({ id: a.id, date: formatDate(a.start_date), name: a.name, metric: formatKeyMetric(a), tss: a.tss })) ?? [];

  return (
    <div style={{ marginTop: 10 }}>
      <button
        onClick={(e) => {
          e.stopPropagation();
          setOpen((o) => !o);
        }}
        style={{ display: "flex", alignItems: "center", gap: 8, border: "none", background: "none", color: "var(--ink3)", fontSize: 12, fontWeight: 600, padding: 0, cursor: "pointer" }}
      >
        {open ? "Hide activities" : "Activities"}
        {data && <LinkedCountBadge count={data.data.length} />}
      </button>

      {open && (
        <div style={{ marginTop: 10 }} onClick={(e) => e.stopPropagation()}>
          {activities.length === 0 ? (
            <div style={{ fontSize: 12, color: "var(--ink3)" }}>No activities linked yet.</div>
          ) : (
            <LinkedActivitiesList key={filterKey} activities={activities} />
          )}
        </div>
      )}
    </div>
  );
}
