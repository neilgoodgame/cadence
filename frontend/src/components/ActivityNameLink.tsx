import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getActivity } from "../api/activities";

/** An activity's name, fetched by id and linked to its detail page - reused wherever a row only
 * carries an activity id (a best effort, a threshold history entry) and needs to show what
 * activity it came from. Cached under the same ["activity", id] query key the activity detail
 * screen itself uses, so navigating there never re-fetches. */
export function ActivityNameLink({ id, style }: { id: string; style?: React.CSSProperties }) {
  const { data } = useQuery({
    queryKey: ["activity", id],
    queryFn: () => getActivity(id),
    staleTime: 5 * 60 * 1000,
  });
  return (
    <Link
      to={`/activities/${id}`}
      style={{ color: "inherit", textDecoration: "none", ...style }}
      onMouseEnter={(e) => ((e.currentTarget as HTMLAnchorElement).style.textDecoration = "underline")}
      onMouseLeave={(e) => ((e.currentTarget as HTMLAnchorElement).style.textDecoration = "none")}
    >
      {data?.name ?? "—"}
    </Link>
  );
}
