import { apiFetch, apiFetchWithHeaders } from "./client";
import type { Upload, UploadBatch } from "./types";

export interface UploadHistoryPurge {
  uploads_deleted: number;
  files_deleted: number;
}

export interface UploadMetadata {
  weightBeforeKg?: number;
  weightAfterKg?: number;
  fluidsMl?: number;
  shoeId?: string;
}

function buildFormData(file: File, metadata: UploadMetadata): FormData {
  const form = new FormData();
  form.set("file", file);
  if (metadata.weightBeforeKg != null) form.set("weight_before_kg", String(metadata.weightBeforeKg));
  if (metadata.weightAfterKg != null) form.set("weight_after_kg", String(metadata.weightAfterKg));
  if (metadata.fluidsMl != null) form.set("fluids_ml", String(metadata.fluidsMl));
  if (metadata.shoeId) form.set("shoe_id", metadata.shoeId);
  return form;
}

export async function uploadActivity(
  file: File,
  metadata: UploadMetadata = {},
): Promise<{ data: Upload; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<Upload>("/v1/activities", { method: "POST", body: buildFormData(file, metadata) });
}

export function getUpload(id: string): Promise<{ data: Upload; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<Upload>(`/v1/uploads/${id}`);
}

export async function uploadActivityBatch(
  file: File,
  onDuplicate: "skip" | "replace" = "skip",
): Promise<{ data: UploadBatch; retryAfterSeconds: number | null }> {
  const form = new FormData();
  form.set("file", file);
  form.set("on_duplicate", onDuplicate);
  return apiFetchWithHeaders<UploadBatch>("/v1/activities/batch", { method: "POST", body: form });
}

export function getUploadBatch(id: string): Promise<{ data: UploadBatch; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<UploadBatch>(`/v1/uploads/batches/${id}`);
}

export function clearUploadHistory(): Promise<UploadHistoryPurge> {
  return apiFetch<UploadHistoryPurge>("/v1/uploads/history", { method: "DELETE" });
}
