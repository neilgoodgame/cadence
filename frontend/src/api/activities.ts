import { apiFetch } from "./client";
import type {
  Activity,
  ActivityComment,
  ActivityUpdate,
  DataList,
  DurationCurve,
  Lap,
  List,
  StreamsResponse,
  Tag,
  WorkoutSport,
  WorkoutStep,
} from "./types";

export interface ListActivitiesParams {
  q?: string;
  sort?: string;
  sport?: string;
  environment?: string;
  after?: string;
  before?: string;
  limit?: number;
  cursor?: string;
  [key: string]: string | number | undefined;
}

function toQueryString(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) {
      search.set(key, String(value));
    }
  }
  const query = search.toString();
  return query ? `?${query}` : "";
}

export function listActivities(params: ListActivitiesParams = {}): Promise<List<Activity>> {
  return apiFetch<List<Activity>>(`/v1/activities${toQueryString(params)}`);
}

export function getActivity(id: string): Promise<Activity> {
  return apiFetch<Activity>(`/v1/activities/${id}`);
}

export function updateActivity(id: string, patch: ActivityUpdate): Promise<Activity> {
  return apiFetch<Activity>(`/v1/activities/${id}`, { method: "PATCH", body: patch });
}

export function deleteActivity(id: string): Promise<void> {
  return apiFetch<void>(`/v1/activities/${id}`, { method: "DELETE" });
}

export function recomputeActivityTss(id: string): Promise<Activity> {
  return apiFetch<Activity>(`/v1/activities/${id}/recompute-tss`, { method: "POST" });
}

export function deleteAllActivities(): Promise<void> {
  return apiFetch<void>("/v1/activities", { method: "DELETE" });
}

export function getLaps(id: string): Promise<DataList<Lap>> {
  return apiFetch<DataList<Lap>>(`/v1/activities/${id}/laps`);
}

export interface InferredWorkout {
  name: string;
  sport: WorkoutSport;
  steps: WorkoutStep[];
}

export function inferWorkout(id: string, autoDetectRepeats: boolean): Promise<InferredWorkout> {
  return apiFetch<InferredWorkout>(`/v1/activities/${id}/infer-workout${toQueryString({ auto_detect_repeats: autoDetectRepeats })}`);
}

export function getStreams(
  id: string,
  fields?: string[],
  resolution: "high" | "medium" | "low" = "high",
): Promise<StreamsResponse> {
  const params = toQueryString({ fields: fields?.join(","), resolution });
  return apiFetch<StreamsResponse>(`/v1/activities/${id}/streams${params}`);
}

export function getCurves(id: string, metric: "power" | "heartrate" = "power"): Promise<DurationCurve> {
  return apiFetch<DurationCurve>(`/v1/activities/${id}/curves${toQueryString({ metric })}`);
}

export function listTags(): Promise<DataList<Tag>> {
  return apiFetch<DataList<Tag>>("/v1/tags");
}

export function tagActivity(activityId: string, name: string): Promise<{ activity_id: string; tag: Tag }> {
  return apiFetch(`/v1/activities/${activityId}/tags`, { method: "POST", body: { name } });
}

export function untagActivity(activityId: string, tagId: string): Promise<void> {
  return apiFetch<void>(`/v1/activities/${activityId}/tags/${tagId}`, { method: "DELETE" });
}

export function listComments(activityId: string): Promise<DataList<ActivityComment>> {
  return apiFetch<DataList<ActivityComment>>(`/v1/activities/${activityId}/comments`);
}

export function createComment(activityId: string, text: string): Promise<ActivityComment> {
  return apiFetch<ActivityComment>(`/v1/activities/${activityId}/comments`, { method: "POST", body: { text } });
}

export function deleteComment(activityId: string, commentId: string): Promise<void> {
  return apiFetch<void>(`/v1/activities/${activityId}/comments/${commentId}`, { method: "DELETE" });
}
