import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { updateAthlete } from "../../api/athletes";
import { getCourse, listCourses, recomputeAthleteCourseElevation } from "../../api/courses";
import { useAuth } from "../../auth/AuthContext";
import type { CourseSectionSettings, SectionAlgorithm } from "../../api/types";
import { CourseSectionsProfile } from "../../components/CourseSectionsProfile";
import { DEFAULT_SECTION_SETTINGS, SETTING_FIELDS, settingsError, type SettingField } from "../../lib/courseSections";

const ALGORITHMS: { value: SectionAlgorithm; label: string; help: string }[] = [
  { value: "douglas_peucker", label: "Douglas–Peucker", help: "Splits at the worst-fitting point until every section fits." },
  { value: "bottom_up", label: "Bottom-up merge", help: "Joins the best-fitting neighbours until no join fits." },
];

/** Same look as the other Preferences tabs' fields (ProfileTab's inputStyle). */
const inputStyle: React.CSSProperties = {
  padding: "7px 10px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  color: "var(--ink)",
  fontSize: 13,
};

/** The settings after the user stops typing for a moment - so the preview doesn't refetch on
 * every keystroke. */
function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

function NumberField({
  field,
  value,
  onChange,
}: {
  field: SettingField;
  value: number;
  onChange: (v: number) => void;
}) {
  return (
    <label style={{ display: "grid", gridTemplateColumns: "1fr 120px", gap: 12, alignItems: "center" }}>
      <span>
        <span style={{ fontSize: 13, fontWeight: 600 }}>{field.label}</span>
        <span style={{ display: "block", fontSize: 12, color: "var(--ink3)" }}>{field.help}</span>
      </span>
      <span style={{ display: "flex", alignItems: "center", gap: 6 }}>
        <input
          type="number"
          min={field.min}
          max={field.max}
          step={field.step}
          value={Number.isNaN(value) ? "" : value}
          onChange={(e) => onChange(e.target.value === "" ? Number.NaN : Number(e.target.value))}
          className="mono"
          style={{ ...inputStyle, width: 80 }}
        />
        <span style={{ fontSize: 12, color: "var(--ink3)" }}>{field.unit}</span>
      </span>
    </label>
  );
}

/** Preferences › Courses - how every course is split into flat, climb and descent sections.
 * Sections are calculated on each course read, so saving here applies the next time any
 * course is viewed; the preview shows the unsaved settings on one course first. */
export function CoursesTab() {
  const { user, setUser } = useAuth();
  const [form, setForm] = useState<CourseSectionSettings>(user?.course_section_settings ?? DEFAULT_SECTION_SETTINGS);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [previewId, setPreviewId] = useState("");
  const error = settingsError(form);
  const previewSettings = useDebounced(form, 400);
  const previewError = settingsError(previewSettings);

  const courses = useQuery({ queryKey: ["courses"], queryFn: () => listCourses() });
  const preview = useQuery({
    queryKey: ["course", previewId, "preview", previewSettings],
    queryFn: () => getCourse(previewId, previewSettings),
    enabled: previewId !== "" && previewError === null,
    placeholderData: (previous) => previous,
  });

  const save = useMutation({
    mutationFn: () => updateAthlete(user!.id, { course_section_settings: form }),
    onSuccess: (updated) => setUser(updated),
  });
  const rebuild = useMutation({ mutationFn: () => recomputeAthleteCourseElevation(user!.id) });

  if (!user) {
    return null;
  }

  const set = (patch: Partial<CourseSectionSettings>) => {
    setForm({ ...form, ...patch });
    save.reset();
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 24, maxWidth: 620 }}>
      <div style={{ fontSize: 13, color: "var(--ink2)" }}>
        How a course's elevation is split into flat, climb and descent sections on its race page. Changes apply the next
        time a course is viewed.
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 8px" }}>Method</h3>
        <div style={{ display: "flex", gap: 6 }}>
          {ALGORITHMS.map((a) => (
            <button
              key={a.value}
              type="button"
              title={a.help}
              onClick={() => set({ algorithm: a.value })}
              style={{
                flex: 1,
                padding: "7px 0",
                borderRadius: 8,
                border: `1px solid ${form.algorithm === a.value ? "var(--ember)" : "var(--line)"}`,
                background: form.algorithm === a.value ? "var(--ember)22" : "transparent",
                color: form.algorithm === a.value ? "var(--ember)" : "var(--ink2)",
                fontSize: 13,
                fontWeight: 700,
                cursor: "pointer",
              }}
            >
              {a.label}
            </button>
          ))}
        </div>
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
        {SETTING_FIELDS.filter((f) => !f.advanced).map((f) => (
          <NumberField key={f.key} field={f} value={form[f.key]} onChange={(v) => set({ [f.key]: v })} />
        ))}
        <button
          type="button"
          onClick={() => setShowAdvanced(!showAdvanced)}
          style={{ alignSelf: "flex-start", border: "none", background: "none", padding: 0, color: "var(--ink2)", fontSize: 12.5, fontWeight: 600, cursor: "pointer" }}
        >
          {showAdvanced ? "▾ Advanced" : "▸ Advanced"}
        </button>
        {showAdvanced &&
          SETTING_FIELDS.filter((f) => f.advanced).map((f) => (
            <NumberField key={f.key} field={f} value={form[f.key]} onChange={(v) => set({ [f.key]: v })} />
          ))}
      </div>

      <div>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 4px" }}>Preview</h3>
        <div style={{ fontSize: 12, color: "var(--ink3)", marginBottom: 8 }}>
          See these settings on one of your courses before saving.
        </div>
        <select
          value={previewId}
          onChange={(e) => setPreviewId(e.target.value)}
          style={{ ...inputStyle, maxWidth: 320 }}
        >
          <option value="">Choose a course…</option>
          {courses.data?.data.map((c) => (
            <option key={c.id} value={c.id}>{c.name}</option>
          ))}
        </select>
        {previewId !== "" && preview.data && (
          <div style={{ marginTop: 12, opacity: preview.isFetching ? 0.6 : 1 }}>
            {preview.data.elevation_quality === "ok" ? (
              <CourseSectionsProfile profile={preview.data.elevation_profile} sections={preview.data.sections} />
            ) : (
              <div style={{ fontSize: 12.5, color: "var(--ink3)" }}>This course has no usable elevation to split into sections.</div>
            )}
          </div>
        )}
      </div>

      <div>
        {error && <div style={{ fontSize: 13, color: "#e0442e", marginBottom: 8 }}>{error}</div>}
        <button
          onClick={() => save.mutate()}
          disabled={save.isPending || error !== null}
          style={{ padding: "8px 16px", borderRadius: 8, border: "none", background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, opacity: error ? 0.5 : 1 }}
        >
          {save.isPending ? "Saving…" : "Save changes"}
        </button>
        <button
          type="button"
          onClick={() => set(DEFAULT_SECTION_SETTINGS)}
          style={{ marginLeft: 10, padding: "8px 14px", borderRadius: 8, border: "1px solid var(--line)", background: "none", color: "var(--ink2)", fontSize: 13, fontWeight: 600 }}
        >
          Reset to defaults
        </button>
        {save.isSuccess && <span style={{ marginLeft: 10, fontSize: 13, color: "#2fa66a" }}>Saved.</span>}
        {save.isError && <span style={{ marginLeft: 10, fontSize: 13, color: "#e0442e" }}>{(save.error as Error).message}</span>}
      </div>

      <div style={{ borderTop: "1px solid var(--line)", paddingTop: 16 }}>
        <h3 style={{ fontSize: 14, fontWeight: 700, margin: "0 0 4px" }}>Rebuild course elevation</h3>
        <div style={{ fontSize: 12, color: "var(--ink3)", marginBottom: 8 }}>
          Re-reads every course's elevation from its GPX file or source activity. Only needed if a course's source data
          changed - the settings above never need it.
        </div>
        <button
          onClick={() => rebuild.mutate()}
          disabled={rebuild.isPending}
          style={{ padding: "6px 12px", borderRadius: 8, border: "1px solid var(--line)", background: "none", color: "var(--ink2)", fontSize: 12.5, fontWeight: 600 }}
        >
          {rebuild.isPending ? "Rebuilding…" : "Rebuild all courses"}
        </button>
        {rebuild.data && (
          <span style={{ marginLeft: 10, fontSize: 13, color: "var(--ink2)" }}>
            Rebuilt {rebuild.data.updated} course{rebuild.data.updated === 1 ? "" : "s"}
            {rebuild.data.skipped > 0 && ` - ${rebuild.data.skipped} skipped because their source data is gone`}.
          </span>
        )}
        {rebuild.isError && <span style={{ marginLeft: 10, fontSize: 13, color: "#e0442e" }}>{(rebuild.error as Error).message}</span>}
      </div>
    </div>
  );
}
