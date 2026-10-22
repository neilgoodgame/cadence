import type { UploadMetadata } from "../../api/uploads";

/** The optional per-session details collected when importing a single file, kept as the raw
 * input strings so a field can be half-typed. */
export interface SessionDetailsDraft {
  weightBefore: string;
  weightAfter: string;
  fluids: string;
  shoeId: string;
}

export const emptySessionDetails: SessionDetailsDraft = { weightBefore: "", weightAfter: "", fluids: "", shoeId: "" };

export function toUploadMetadata(d: SessionDetailsDraft): UploadMetadata {
  return {
    weightBeforeKg: d.weightBefore ? Number(d.weightBefore) : undefined,
    weightAfterKg: d.weightAfter ? Number(d.weightAfter) : undefined,
    fluidsMl: d.fluids ? Number(d.fluids) : undefined,
    shoeId: d.shoeId || undefined,
  };
}
