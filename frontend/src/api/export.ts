import { toQueryString } from "./activities";
import { apiFetchStream, apiFetchWithHeaders } from "./client";
import type { ExportJob, Sport } from "./types";

export function startExport(
  sport?: Sport,
  includeProfile?: boolean,
): Promise<{ data: ExportJob; retryAfterSeconds: number | null }> {
  const query = toQueryString({ sport, include_profile: includeProfile || undefined });
  return apiFetchWithHeaders<ExportJob>(`/v1/export${query}`, { method: "POST" });
}

export function getExportJob(id: string): Promise<{ data: ExportJob; retryAfterSeconds: number | null }> {
  return apiFetchWithHeaders<ExportJob>(`/v1/export/${id}`);
}

export async function downloadExport(id: string): Promise<Blob> {
  const response = await apiFetchStream(`/v1/export/${id}/download`);
  return response.blob();
}
