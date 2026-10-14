import { apiFetch, apiFetchStream } from "./client";
import { toQueryString } from "./activities";
import type { Course, CourseSectionSettings, CourseSummary, CourseUpdate, DataList } from "./types";

export function listCourses(q?: string): Promise<DataList<CourseSummary>> {
  return apiFetch<DataList<CourseSummary>>(`/v1/courses${toQueryString({ q })}`);
}

export function uploadCourseGpx(gpx: File, name: string, locationName?: string): Promise<Course> {
  const form = new FormData();
  form.append("gpx", gpx);
  form.append("name", name);
  if (locationName) form.append("location_name", locationName);
  return apiFetch<Course>("/v1/courses", { method: "POST", body: form });
}

export function createCourseFromActivity(activityId: string, name: string): Promise<Course> {
  return apiFetch<Course>("/v1/courses/from-activity", { method: "POST", body: { activity_id: activityId, name } });
}

/** `preview` overrides the owner's section settings for this response only - nothing is saved. */
export function getCourse(id: string, preview?: Partial<CourseSectionSettings>): Promise<Course> {
  return apiFetch<Course>(`/v1/courses/${id}${toQueryString(preview ?? {})}`);
}

/** Rebuilds the stored elevation from the course's GPX or source activity (422 if both are gone). */
export function recomputeCourseElevation(id: string): Promise<Course> {
  return apiFetch<Course>(`/v1/courses/${id}/recompute-elevation`, { method: "POST" });
}

export function recomputeAthleteCourseElevation(athleteId: string): Promise<{ updated: number; skipped: number }> {
  return apiFetch<{ updated: number; skipped: number }>(`/v1/athletes/${athleteId}/recompute-elevation`, {
    method: "POST",
  });
}

export function updateCourse(id: string, data: CourseUpdate): Promise<Course> {
  return apiFetch<Course>(`/v1/courses/${id}`, { method: "PATCH", body: data });
}

export function deleteCourse(id: string): Promise<void> {
  return apiFetch<void>(`/v1/courses/${id}`, { method: "DELETE" });
}

/** The GPX is served behind normal Bearer-token auth, so a plain link/`<a href>` can't fetch
 * it directly - caller owns the resulting object URL and must revoke it when done (same
 * pattern as gear.ts's fetchShoePhotoUrl). */
export async function downloadCourseGpxUrl(id: string): Promise<string> {
  const response = await apiFetchStream(`/v1/courses/${id}/gpx`);
  const blob = await response.blob();
  return URL.createObjectURL(blob);
}
