import { useEffect, useMemo, useRef, useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { listActivities, listTags } from "../api/activities";
import { listZones } from "../api/athletes";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/types";
import type { Sport } from "../api/types";
import { ActivityCard } from "./activities/ActivityCard";

const CQL_FIELDS: {
  label: string;
  aliases: string[];
  type: "numeric" | "text" | "special";
  unit?: string;
  values?: string;
  note?: string;
}[] = [
  { label: "Avg HR",      aliases: ["hr", "bpm", "heartrate"],        type: "numeric", unit: "bpm" },
  { label: "Max HR",      aliases: ["maxhr", "hrmax"],                 type: "numeric", unit: "bpm" },
  { label: "TSS",         aliases: ["tss", "load", "stress"],          type: "numeric" },
  { label: "Distance",    aliases: ["distance", "dist", "km"],         type: "numeric", unit: "km" },
  { label: "Duration",    aliases: ["duration", "time", "mins"],       type: "numeric", unit: "min" },
  { label: "Power",       aliases: ["power", "watts", "np"],           type: "numeric", unit: "W" },
  { label: "Pace",        aliases: ["pace"],                           type: "numeric", unit: "/km" },
  { label: "Sport",       aliases: ["sport", "type", "discipline"],    type: "text",    values: "run · bike · swim · multisport" },
  { label: "Environment", aliases: ["environment", "env"],             type: "text",    values: "indoor · outdoor" },
  { label: "Name",        aliases: ["name", "title"],                  type: "text" },
  { label: "Tag",         aliases: ["tag"],                            type: "special", note: "tag <name>  — no operator" },
];

const CQL_EXAMPLES = [
  { q: "hr > 150 and sport = run",            desc: "Hard runs" },
  { q: "tss > 200 orderby tss desc",          desc: "High-stress sessions, hardest first" },
  { q: "distance > 20 sport = run",           desc: "Long runs over 20 km" },
  { q: "power > 260 env = indoor",            desc: "Strong indoor rides" },
  { q: "name ~ marathon",                     desc: "Activities with "marathon" in the name" },
  { q: "tag race",                            desc: "All tagged as race" },
  { q: "duration > 120 tss < 100",           desc: "Long but low-stress sessions" },
];

function SearchHelpModal({ onClose }: { onClose: () => void }) {
  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed", inset: 0, zIndex: 1000,
        background: "rgba(0,0,0,0.45)",
        display: "flex", alignItems: "flex-start", justifyContent: "center",
        paddingTop: 80, paddingLeft: 16, paddingRight: 16,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: "var(--surface)",
          borderRadius: 14,
          border: "1px solid var(--line)",
          padding: "28px 32px",
          width: "100%",
          maxWidth: 640,
          maxHeight: "80vh",
          overflowY: "auto",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 20 }}>
          <h2 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>Search reference</h2>
          <button
            type="button"
            onClick={onClose}
            style={{ background: "none", border: "none", fontSize: 20, color: "var(--ink3)", cursor: "pointer", lineHeight: 1 }}
          >
            ×
          </button>
        </div>

        {/* Fields table */}
        <div style={{ marginBottom: 24 }}>
          <div style={{ fontSize: 11, fontWeight: 700, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.07em", marginBottom: 10 }}>
            Fields
          </div>
          <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
            <thead>
              <tr style={{ borderBottom: "1px solid var(--line)" }}>
                <th style={{ textAlign: "left", padding: "4px 8px 8px 0", color: "var(--ink3)", fontWeight: 600, fontSize: 11 }}>Field</th>
                <th style={{ textAlign: "left", padding: "4px 8px 8px 0", color: "var(--ink3)", fontWeight: 600, fontSize: 11 }}>Aliases</th>
                <th style={{ textAlign: "left", padding: "4px 8px 8px 0", color: "var(--ink3)", fontWeight: 600, fontSize: 11 }}>Unit / Values</th>
              </tr>
            </thead>
            <tbody>
              {CQL_FIELDS.map((f) => (
                <tr key={f.label} style={{ borderBottom: "1px solid var(--line)" }}>
                  <td style={{ padding: "7px 8px 7px 0", fontWeight: 700, whiteSpace: "nowrap" }}>{f.label}</td>
                  <td style={{ padding: "7px 8px 7px 0" }}>
                    <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>
                      {f.aliases.join("  ·  ")}
                    </span>
                  </td>
                  <td style={{ padding: "7px 0 7px 0", color: "var(--ink3)", fontSize: 12 }}>
                    {f.note ?? f.values ?? (f.unit ? f.unit : (f.type === "text" ? "text" : "number"))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* Operators */}
        <div style={{ marginBottom: 24 }}>
          <div style={{ fontSize: 11, fontWeight: 700, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.07em", marginBottom: 10 }}>
            Operators
          </div>
          <div style={{ display: "flex", gap: 20, flexWrap: "wrap" }}>
            {[
              { ops: [">", "<", ">=", "<=", "=", "!="], label: "Numeric" },
              { ops: ["=", "~"],                         label: "Text (= exact, ~ contains)" },
            ].map(({ ops, label }) => (
              <div key={label}>
                <div style={{ fontSize: 11, color: "var(--ink3)", marginBottom: 5 }}>{label}</div>
                <div style={{ display: "flex", gap: 6 }}>
                  {ops.map((op) => (
                    <code key={op} className="mono" style={{ fontSize: 13, background: "var(--elev)", border: "1px solid var(--line)", borderRadius: 4, padding: "2px 7px" }}>
                      {op}
                    </code>
                  ))}
                </div>
              </div>
            ))}
          </div>
          <div style={{ marginTop: 10, fontSize: 12, color: "var(--ink3)" }}>
            Combine clauses with <code className="mono" style={{ fontSize: 12 }}>AND</code> or <code className="mono" style={{ fontSize: 12 }}>OR</code>.
            Sort with <code className="mono" style={{ fontSize: 12 }}>orderby &lt;field&gt; asc|desc</code>.
          </div>
        </div>

        {/* Examples */}
        <div>
          <div style={{ fontSize: 11, fontWeight: 700, color: "var(--ink3)", textTransform: "uppercase", letterSpacing: "0.07em", marginBottom: 10 }}>
            Examples
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 7 }}>
            {CQL_EXAMPLES.map(({ q, desc }) => (
              <div key={q} style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
                <code className="mono" style={{ fontSize: 12, color: "var(--ember)", whiteSpace: "nowrap", flexShrink: 0 }}>{q}</code>
                <span style={{ fontSize: 12, color: "var(--ink3)" }}>{desc}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

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
  const [showHelp, setShowHelp] = useState(false);
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

      <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
        <input
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          placeholder="Search — e.g. avg hr > 140 and sport = run"
          style={{
            flex: 1,
            padding: "12px 14px",
            borderRadius: 10,
            border: "1px solid var(--line)",
            background: "var(--elev)",
            fontSize: 14,
            color: "var(--ink)",
          }}
        />
        <button
          type="button"
          onClick={() => setShowHelp(true)}
          title="Search field reference"
          style={{
            width: 36, height: 36,
            borderRadius: "50%",
            border: "1px solid var(--line)",
            background: "var(--elev)",
            color: "var(--ink3)",
            fontSize: 15,
            fontWeight: 700,
            cursor: "pointer",
            flexShrink: 0,
            display: "flex", alignItems: "center", justifyContent: "center",
          }}
        >
          ?
        </button>
      </div>
      {queryError && <div style={{ fontSize: 13, color: "var(--ember)" }}>{queryError}</div>}
      {showHelp && <SearchHelpModal onClose={() => setShowHelp(false)} />}

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
