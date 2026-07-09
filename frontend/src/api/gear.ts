import { apiFetch } from "./client";
import type {
  Bike,
  BikeDetail,
  BikeKind,
  Component,
  DataList,
  ServiceAction,
  ServiceRecord,
  Shoe,
  ShoeCatalogEntry,
} from "./types";

export function listBikes(): Promise<DataList<Bike>> {
  return apiFetch<DataList<Bike>>("/v1/gear/bikes");
}

export function getBike(id: string): Promise<BikeDetail> {
  return apiFetch<BikeDetail>(`/v1/gear/bikes/${id}`);
}

export interface BikeInput {
  name: string;
  kind?: BikeKind;
  groupset?: string;
  distance_km?: number;
}

export function createBike(input: BikeInput): Promise<Bike> {
  return apiFetch<Bike>("/v1/gear/bikes", { method: "POST", body: input });
}

export function updateBike(id: string, input: Partial<BikeInput>): Promise<Bike> {
  return apiFetch<Bike>(`/v1/gear/bikes/${id}`, { method: "PATCH", body: input });
}

export function deleteBike(id: string): Promise<void> {
  return apiFetch<void>(`/v1/gear/bikes/${id}`, { method: "DELETE" });
}

export interface ComponentInput {
  name: string;
  limit_km: number;
  km?: number;
  model?: string;
}

export function createComponent(bikeId: string, input: ComponentInput): Promise<Component> {
  return apiFetch<Component>(`/v1/gear/bikes/${bikeId}/components`, { method: "POST", body: input });
}

export function updateComponent(id: string, input: Partial<ComponentInput>): Promise<Component> {
  return apiFetch<Component>(`/v1/gear/components/${id}`, { method: "PATCH", body: input });
}

export function deleteComponent(id: string): Promise<void> {
  return apiFetch<void>(`/v1/gear/components/${id}`, { method: "DELETE" });
}

export interface ServiceRecordInput {
  action?: ServiceAction;
  reset?: boolean;
  note?: string;
  date?: string;
}

export function serviceComponent(componentId: string, input: ServiceRecordInput): Promise<ServiceRecord> {
  return apiFetch<ServiceRecord>(`/v1/gear/components/${componentId}/service`, { method: "POST", body: input });
}

export function listShoes(): Promise<DataList<Shoe>> {
  return apiFetch<DataList<Shoe>>("/v1/gear/shoes");
}

export interface ShoeInput {
  shoe_model_version_id: string;
  colourway: string;
  name?: string;
  limit_km?: number;
  image?: string | null;
}

export function createShoe(input: ShoeInput): Promise<Shoe> {
  return apiFetch<Shoe>("/v1/gear/shoes", { method: "POST", body: input });
}

export interface ShoeUpdateInput {
  name?: string;
  limit_km?: number;
  km?: number;
  image?: string | null;
  retired?: boolean;
}

export function updateShoe(id: string, input: ShoeUpdateInput): Promise<Shoe> {
  return apiFetch<Shoe>(`/v1/gear/shoes/${id}`, { method: "PATCH", body: input });
}

export function deleteShoe(id: string): Promise<void> {
  return apiFetch<void>(`/v1/gear/shoes/${id}`, { method: "DELETE" });
}

export function searchShoeCatalog(q: string): Promise<DataList<ShoeCatalogEntry>> {
  return apiFetch<DataList<ShoeCatalogEntry>>(`/v1/gear/shoe-catalog?q=${encodeURIComponent(q)}`);
}

export interface ShoeCatalogCreateInput {
  manufacturer: string;
  model: string;
  version?: string;
}

export function createShoeCatalogEntry(input: ShoeCatalogCreateInput): Promise<ShoeCatalogEntry> {
  return apiFetch<ShoeCatalogEntry>("/v1/gear/shoe-catalog", { method: "POST", body: input });
}
