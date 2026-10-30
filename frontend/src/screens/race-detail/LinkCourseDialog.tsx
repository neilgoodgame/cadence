import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getActivity, listActivities } from "../../api/activities";
import { createCourseFromActivity, listCourses, updateCourse, uploadCourseGpx } from "../../api/courses";
import { updateRace } from "../../api/races";
import { SegmentedControl } from "../../components/SegmentedControl";
import type { Course } from "../../api/types";

type Tab = "upload" | "activity" | "existing";

const fieldStyle: React.CSSProperties = {
  width: "100%",
  padding: "8px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  color: "var(--ink)",
  fontSize: 13,
  boxSizing: "border-box",
};

function UploadTab({ raceDistanceKm, onLinked }: { raceDistanceKm: number | null; onLinked: (id: string) => void }) {
  const [preview, setPreview] = useState<Course | null>(null);
  const [name, setName] = useState("");
  const [locationName, setLocationName] = useState("");

  const uploadMutation = useMutation({
    mutationFn: (file: File) => uploadCourseGpx(file, file.name.replace(/\.gpx$/i, "")),
    onSuccess: (course) => {
      setPreview(course);
      setName(course.name);
      setLocationName(course.location_name);
    },
  });

  const saveMutation = useMutation({
    mutationFn: () => updateCourse(preview!.id, { name, location_name: locationName }),
  });

  if (!preview) {
    return (
      <div
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => {
          e.preventDefault();
          const file = e.dataTransfer.files[0];
          if (file) uploadMutation.mutate(file);
        }}
        style={{ border: "2px dashed var(--line)", borderRadius: 10, padding: 32, textAlign: "center" }}
      >
        <div style={{ fontSize: 13, color: "var(--ink3)", marginBottom: 10 }}>
          {uploadMutation.isPending ? "Uploading…" : "Drop a GPX file here, or"}
        </div>
        {!uploadMutation.isPending && (
          <label style={{ display: "inline-block", padding: "6px 14px", borderRadius: 8, border: "1px solid var(--line)", fontSize: 12.5, fontWeight: 600, cursor: "pointer", color: "var(--ink2)" }}>
            Choose file
            <input
              type="file"
              accept=".gpx"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) uploadMutation.mutate(file);
              }}
              style={{ display: "none" }}
            />
          </label>
        )}
        {uploadMutation.isError && <div style={{ fontSize: 12, color: "#e0442e", marginTop: 10 }}>Couldn't parse that GPX file.</div>}
      </div>
    );
  }

  const warn = raceDistanceKm != null && preview.distance_km != null && Math.abs(preview.distance_km - raceDistanceKm) / raceDistanceKm > 0.01;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      <label>
        <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 4 }}>Name</div>
        <input value={name} onChange={(e) => setName(e.target.value)} style={fieldStyle} />
      </label>
      <label>
        <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink2)", marginBottom: 4 }}>Location</div>
        <input value={locationName} onChange={(e) => setLocationName(e.target.value)} style={fieldStyle} />
      </label>
      <div style={{ fontSize: 12.5, color: "var(--ink3)" }}>
        {preview.distance_km?.toFixed(1)} km · +{preview.elevation_gain_m?.toFixed(0)} m / −{preview.elevation_loss_m?.toFixed(0)} m
      </div>
      {warn && (
        <div style={{ fontSize: 12, color: "#f0a02e" }}>
          This course's distance differs from the race's by more than 1%.
        </div>
      )}
      <button
        onClick={async () => {
          await saveMutation.mutateAsync();
          onLinked(preview.id);
        }}
        disabled={saveMutation.isPending}
        style={{ alignSelf: "flex-start", border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", cursor: "pointer" }}
      >
        Use this course
      </button>
    </div>
  );
}

function ActivityTab({ raceActivityId, onLinked }: { raceActivityId: string | null; onLinked: (id: string) => void }) {
  const [selected, setSelected] = useState<string | null>(raceActivityId);
  const [name, setName] = useState("");
  // `sort` is a CQL field name ("date"), not the model column ("start_date") - an unknown sort
  // field makes both backends 400 the whole request.
  const { data, isLoading, isError } = useQuery({ queryKey: ["activities", "with-gps"], queryFn: () => listActivities({ limit: 50, sort: "-date" }) });
  // The race's own activity may be older than the 50 most recent, so fetch it separately and pin
  // it to the top of the list (it's pre-selected above).
  const { data: raceActivity } = useQuery({
    queryKey: ["activity", raceActivityId],
    queryFn: () => getActivity(raceActivityId!),
    enabled: raceActivityId != null,
  });
  const recent = (data?.data ?? []).filter((a) => a.has_gps && a.id !== raceActivityId);
  const activities = raceActivity?.has_gps ? [raceActivity, ...recent] : recent;
  // Ignore a selection that isn't listed (e.g. the pre-selected race activity has no GPS).
  const chosen = activities.some((a) => a.id === selected) ? selected : null;

  const mutation = useMutation({
    mutationFn: () => createCourseFromActivity(chosen!, name || "New course"),
    onSuccess: (course) => onLinked(course.id),
  });

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      <div style={{ maxHeight: 280, overflowY: "auto", display: "flex", flexDirection: "column", gap: 4 }}>
        {activities.map((a) => (
          <label key={a.id} style={{ display: "flex", alignItems: "center", gap: 8, padding: "6px 8px", borderRadius: 8, cursor: "pointer", background: chosen === a.id ? "var(--canvas-solid)" : "transparent" }}>
            <input type="radio" checked={chosen === a.id} onChange={() => setSelected(a.id)} />
            <span style={{ flex: 1, fontSize: 13 }}>{a.name}</span>
            <span style={{ fontSize: 11, color: "var(--ink3)" }}>{a.start_date.slice(0, 10)}</span>
            {a.id === raceActivityId && (
              <span style={{ fontSize: 10, fontWeight: 700, color: "var(--ember)" }}>Race activity</span>
            )}
          </label>
        ))}
        {isLoading && <div style={{ fontSize: 13, color: "var(--ink3)" }}>Loading activities…</div>}
        {isError && <div style={{ fontSize: 13, color: "#e0442e" }}>Couldn't load activities.</div>}
        {!isLoading && !isError && activities.length === 0 && <div style={{ fontSize: 13, color: "var(--ink3)" }}>No activities with GPS found.</div>}
      </div>
      <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Course name" style={fieldStyle} />
      <button
        onClick={() => mutation.mutate()}
        disabled={!chosen || !name || mutation.isPending}
        style={{ alignSelf: "flex-start", border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", cursor: "pointer" }}
      >
        {mutation.isPending ? "Creating…" : "Create course"}
      </button>
    </div>
  );
}

function ExistingTab({ onLinked }: { onLinked: (id: string) => void }) {
  const [q, setQ] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const { data } = useQuery({ queryKey: ["courses", q], queryFn: () => listCourses(q || undefined) });

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search courses…" style={fieldStyle} />
      <div style={{ maxHeight: 280, overflowY: "auto", display: "flex", flexDirection: "column", gap: 4 }}>
        {(data?.data ?? []).map((c) => (
          <label key={c.id} style={{ display: "flex", alignItems: "center", gap: 8, padding: "6px 8px", borderRadius: 8, cursor: "pointer", background: selected === c.id ? "var(--canvas-solid)" : "transparent" }}>
            <input type="radio" checked={selected === c.id} onChange={() => setSelected(c.id)} />
            <span style={{ flex: 1, fontSize: 13 }}>{c.name}</span>
            <span style={{ fontSize: 11, color: "var(--ink3)" }}>{c.race_count} races</span>
          </label>
        ))}
        {(data?.data ?? []).length === 0 && <div style={{ fontSize: 13, color: "var(--ink3)" }}>No courses found.</div>}
      </div>
      <button
        onClick={() => selected && onLinked(selected)}
        disabled={!selected}
        style={{ alignSelf: "flex-start", border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px", cursor: "pointer" }}
      >
        Link course
      </button>
    </div>
  );
}

/** README §4.2.4 "Link course dialog" - Upload GPX | From activity | Existing course. */
export function LinkCourseDialog({
  raceId,
  raceActivityId,
  raceDistanceKm,
  onClose,
}: {
  raceId: string;
  raceActivityId: string | null;
  raceDistanceKm: number | null;
  onClose: () => void;
}) {
  const [tab, setTab] = useState<Tab>("upload");
  const queryClient = useQueryClient();

  const linkMutation = useMutation({
    mutationFn: (courseId: string) => updateRace(raceId, { course_id: courseId }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["race", raceId] });
      onClose();
    },
  });

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ background: "var(--card)", borderRadius: 14, padding: 24, width: 480, maxHeight: "80vh", overflowY: "auto", display: "flex", flexDirection: "column", gap: 16 }}
      >
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Link a course</h2>
        <SegmentedControl
          fill={false}
          style={{ alignSelf: "flex-start" }}
          options={[
            { key: "upload", value: "upload" as const, label: "Upload GPX" },
            { key: "activity", value: "activity" as const, label: "From activity" },
            { key: "existing", value: "existing" as const, label: "Existing course" },
          ]}
          value={tab}
          onChange={setTab}
        />

        {tab === "upload" && <UploadTab raceDistanceKm={raceDistanceKm} onLinked={(id) => linkMutation.mutate(id)} />}
        {tab === "activity" && <ActivityTab raceActivityId={raceActivityId} onLinked={(id) => linkMutation.mutate(id)} />}
        {tab === "existing" && <ExistingTab onLinked={(id) => linkMutation.mutate(id)} />}

        <button onClick={onClose} style={{ alignSelf: "flex-end", border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, fontSize: 13, padding: "8px 16px", cursor: "pointer" }}>
          Cancel
        </button>
      </div>
    </div>
  );
}
