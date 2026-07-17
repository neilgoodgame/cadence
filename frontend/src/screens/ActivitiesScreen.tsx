import { useEffect, useMemo, useRef, useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { listActivities, listTags } from "../api/activities";
import { listZones } from "../api/athletes";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/types";
import type { Sport } from "../api/types";
import { ActivityCard } from "./activities/ActivityCard";

const SPORT_FILTERS: { value: Sport | "all"; label: string }[] = [
  { value: "all", label: "All" },
  { value: "bike", label: "Ride" },
  { value: "run", label: "Run" },
  { value: "swim", label: "Swim" },
  { value: "multisport", label: "Multisport" },
];

function chipButton(active: boolean): React.CSSProperties {
  return {
    border: "1px solid var(--line)",
    borderRadius: 8,
    padding: "6px 12px",
    fontSize: 13,
    fontWeight: 600,
    background: active ? "var(--elev)" : "transparent",
    color: active ? "var(--ink)" : "var(--ink3)",
  };
}

export function ActivitiesScreen() {
  const { user } = useAuth();
  const [searchInput, setSearchInput] = useState("");
  const [sport, setSport] = useState<Sport | "all">("all");
  const [selectedTag, setSelectedTag] = useState<string | null>(null);
  const [matchedOnly, setMatchedOnly] = useState(false);
  const sentinelRef = useRef<HTMLDivElement>(null);

  const q = useMemo(() => {
    const clauses = [];
    if (searchInput.trim()) clauses.push(searchInput.trim());
    // CQL's tag clause is a special grammar rule (parser.py) that takes exactly the bare
    // word right after "tag" as the value - no operator at all, not even "=". A query like
    // "tag ~ x" or "tag = x" doesn't error, it just silently fails to match anything, which
    // is exactly the bug this comment is here to stop someone from reintroducing.
    if (selectedTag) clauses.push(`tag ${selectedTag}`);
    return clauses.join(" AND ") || undefined;
  }, [searchInput, selectedTag]);

  const activitiesQuery = useInfiniteQuery({
    queryKey: ["activities", "list", q, sport],
    queryFn: ({ pageParam }: { pageParam: string | undefined }) =>
      listActivities({ q, sport: sport === "all" ? undefined : sport, cursor: pageParam, limit: 30 }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => (lastPage.has_more ? lastPage.next_cursor ?? undefined : undefined),
  });

  const tagsQuery = useQuery({ queryKey: ["tags"], queryFn: listTags });
  const zonesQuery = useQuery({
    queryKey: ["zones", user?.id],
    queryFn: () => listZones(user!.id),
    enabled: !!user,
  });
  const hrZones = zonesQuery.data?.data.find((z) => z.type === "heart_rate");

  const allActivities = useMemo(() => {
    const pages = activitiesQuery.data?.pages ?? [];
    const flat = pages.flatMap((p) => p.data);
    return matchedOnly ? flat.filter((a) => a.workout_id != null) : flat;
  }, [activitiesQuery.data, matchedOnly]);

  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries[0].isIntersecting && activitiesQuery.hasNextPage && !activitiesQuery.isFetchingNextPage) {
        activitiesQuery.fetchNextPage();
      }
    });
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [activitiesQuery]);

  const queryError = activitiesQuery.error instanceof ApiError ? activitiesQuery.error.message : null;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <h1 style={{ fontSize: 26, fontWeight: 800, letterSpacing: "-0.02em", margin: 0 }}>Activities</h1>

      <input
        value={searchInput}
        onChange={(e) => setSearchInput(e.target.value)}
        placeholder="Search activities — e.g. avg hr > 140 and sport = run"
        style={{
          width: "100%",
          padding: "12px 14px",
          borderRadius: 10,
          border: "1px solid var(--line)",
          background: "var(--elev)",
          fontSize: 14,
          color: "var(--ink)",
        }}
      />
      {queryError && <div style={{ fontSize: 13, color: "var(--ember)" }}>{queryError}</div>}

      <div style={{ display: "flex", justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
        <div style={{ display: "flex", gap: 4 }}>
          {SPORT_FILTERS.map((f) => (
            <button key={f.value} style={chipButton(sport === f.value)} onClick={() => setSport(f.value)}>
              {f.label}
            </button>
          ))}
        </div>
        <button style={chipButton(matchedOnly)} onClick={() => setMatchedOnly(!matchedOnly)}>
          Matched only
        </button>
      </div>

      {(tagsQuery.data?.data.length ?? 0) > 0 && (
        <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
          {tagsQuery.data!.data.map((tag) => (
            <button
              key={tag.id}
              style={chipButton(selectedTag === tag.name)}
              onClick={() => setSelectedTag(selectedTag === tag.name ? null : tag.name)}
            >
              {tag.name}
            </button>
          ))}
          {selectedTag && (
            <button style={chipButton(false)} onClick={() => setSelectedTag(null)}>
              Clear ×
            </button>
          )}
        </div>
      )}

      <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        {allActivities.map((activity) => (
          <ActivityCard key={activity.id} activity={activity} hrZones={hrZones} />
        ))}
        {allActivities.length === 0 && !activitiesQuery.isLoading && (
          <div style={{ color: "var(--ink3)", fontSize: 13 }}>No activities match.</div>
        )}
      </div>

      <div ref={sentinelRef} />
      {activitiesQuery.isFetchingNextPage && <div style={{ color: "var(--ink3)", fontSize: 13 }}>Loading more…</div>}
    </div>
  );
}
