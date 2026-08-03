import { apiFetchWithHeaders } from "./client";
import type { ImportJob } from "./types";

export function startImport(file: File): Promise<{ data: ImportJob; retryAfterSeconds: number | null }> {
  const form = new FormData();
  form.set("file", file);
  return apiFetchWithHeaders<ImportJob>("/v1/import", { method: "POST", body: form });
}

export function getImportJob(id: string): Promise<{ data: ImportJob; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<ImportJob>(`/v1/import/${id}`);
}
