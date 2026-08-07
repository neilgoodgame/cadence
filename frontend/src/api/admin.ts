import { apiFetch } from "./client";
import type {
  AdminRelationship,
  AdminShoeCatalogEntry,
  AdminUser,
  AdminUserUpdate,
  CatalogAuditLogEntry,
  DataList,
} from "./types";

export function listAdminShoeCatalog(q?: string): Promise<DataList<AdminShoeCatalogEntry>> {
  const qs = q ? `?q=${encodeURIComponent(q)}` : "";
  return apiFetch<DataList<AdminShoeCatalogEntry>>(`/v1/admin/shoe-catalog${qs}`);
}

export interface AdminShoeCatalogCreateInput {
  manufacturer: string;
  model: string;
  version: string;
}

export function createOrAppendShoeCatalogEntry(input: AdminShoeCatalogCreateInput): Promise<AdminShoeCatalogEntry> {
  return apiFetch<AdminShoeCatalogEntry>("/v1/admin/shoe-catalog", { method: "POST", body: input });
}

export function addShoeCatalogVersion(shoeModelId: string, version: string): Promise<AdminShoeCatalogEntry> {
  return apiFetch<AdminShoeCatalogEntry>(`/v1/admin/shoe-catalog/${shoeModelId}/versions`, {
    method: "POST",
    body: { version },
  });
}

export function deleteShoeCatalogModel(shoeModelId: string): Promise<void> {
  return apiFetch<void>(`/v1/admin/shoe-catalog/${shoeModelId}`, { method: "DELETE" });
}

export function listAdminUsers(q?: string): Promise<DataList<AdminUser>> {
  const qs = q ? `?q=${encodeURIComponent(q)}` : "";
  return apiFetch<DataList<AdminUser>>(`/v1/admin/users${qs}`);
}

export function updateAdminUser(id: string, input: AdminUserUpdate): Promise<AdminUser> {
  return apiFetch<AdminUser>(`/v1/admin/users/${id}`, { method: "PATCH", body: input });
}

export function listAdminRelationships(): Promise<DataList<AdminRelationship>> {
  return apiFetch<DataList<AdminRelationship>>("/v1/admin/relationships");
}

export function revokeAdminRelationship(id: string): Promise<void> {
  return apiFetch<void>(`/v1/admin/relationships/${id}`, { method: "DELETE" });
}

export function listCatalogAuditLog(): Promise<DataList<CatalogAuditLogEntry>> {
  return apiFetch<DataList<CatalogAuditLogEntry>>("/v1/admin/audit-log");
}
