import { apiFetch, apiFetchStream } from "./client";
import { toQueryString } from "./activities";
import type { Course, CourseSummary, CourseUpdate, DataList } from "./types";

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

export function getCourse(id: string): Promise<Course> {
  return apiFetch<Course>(`/v1/courses/${id}`);
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
