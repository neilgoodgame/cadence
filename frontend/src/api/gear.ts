import { apiFetch, apiFetchStream } from "./client";
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
  ShoeImportEntry,
  ShoeImportResult,
  ShoePhoto,
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

export function importShoes(entries: ShoeImportEntry[]): Promise<ShoeImportResult> {
  return apiFetch<ShoeImportResult>("/v1/gear/shoes/import", { method: "POST", body: { entries } });
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

export function listShoePhotos(shoeId: string): Promise<DataList<ShoePhoto>> {
  return apiFetch<DataList<ShoePhoto>>(`/v1/gear/shoes/${shoeId}/photos`);
}

export interface ShoePhotoUploadInput {
  image: File;
  taken_on?: string;
  km?: number;
  notes?: string;
}

export function uploadShoePhoto(shoeId: string, input: ShoePhotoUploadInput): Promise<ShoePhoto> {
  const form = new FormData();
  form.append("image", input.image);
  if (input.taken_on) form.append("taken_on", input.taken_on);
  if (input.km != null) form.append("km", String(input.km));
  if (input.notes) form.append("notes", input.notes);
  return apiFetch<ShoePhoto>(`/v1/gear/shoes/${shoeId}/photos`, { method: "POST", body: form });
}

export function deleteShoePhoto(photoId: string): Promise<void> {
  return apiFetch<void>(`/v1/gear/shoe-photos/${photoId}`, { method: "DELETE" });
}

/** Shoe photos are served behind the normal Bearer-token auth like everything else in this
 * app, so a plain `<img src="...">` can't load one directly - fetch the bytes as a blob and
 * hand the caller an object URL (which it owns and must revoke when done, e.g. via
 * useEffect's cleanup). */
export async function fetchShoePhotoUrl(photoId: string): Promise<string> {
  const response = await apiFetchStream(`/v1/gear/shoe-photos/${photoId}/image`);
  const blob = await response.blob();
  return URL.createObjectURL(blob);
}
