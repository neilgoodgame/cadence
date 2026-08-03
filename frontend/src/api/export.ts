import { apiFetchStream, apiFetchWithHeaders } from "./client";
import type { ExportJob } from "./types";

export function startExport(): Promise<{ data: ExportJob; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<ExportJob>("/v1/export", { method: "POST" });
}

export function getExportJob(id: string): Promise<{ data: ExportJob; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<ExportJob>(`/v1/export/${id}`);
}

export async function downloadExport(id: string): Promise<Blob> {
  const response = await apiFetchStream(`/v1/export/${id}/download`);
  return response.blob();
}
