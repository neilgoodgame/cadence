import { apiFetchStream, apiFetchWithHeaders } from "./client";
import type { ExportJob, Sport } from "./types";

export function startExport(sport?: Sport): Promise<{ data: ExportJob; retryAfterSeconds: number | null }> {
  const path = sport ? `/v1/export?sport=${sport}` : "/v1/export";
  return apiFetchWithHeaders<ExportJob>(path, { method: "POST" });
}

export function getExportJob(id: string): Promise<{ data: ExportJob; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<ExportJob>(`/v1/export/${id}`);
}

export async function downloadExport(id: string): Promise<Blob> {
  const response = await apiFetchStream(`/v1/export/${id}/download`);
  return response.blob();
}
