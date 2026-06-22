import { apiFetch } from "./client";
import type { DataList, Shoe } from "./types";

export function listShoes(): Promise<DataList<Shoe>> {
  return apiFetch<DataList<Shoe>>("/v1/gear/shoes");
}
