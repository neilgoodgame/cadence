import { apiFetch } from "./client";

export interface HealthStatus {
  status: string;
  build: string;
}

/** No auth needed - same as the backend's own health check. */
export function getHealth(): Promise<HealthStatus> {
  return apiFetch<HealthStatus>("/healthz", { anonymous: true });
}
