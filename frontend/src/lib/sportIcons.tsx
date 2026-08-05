import type { ReactNode } from "react";
import type { Sport } from "../api/types";

const PATHS: Record<Sport, ReactNode> = {
  bike: (
    <>
      <circle cx="5.5" cy="17.5" r="3.5" />
      <circle cx="18.5" cy="17.5" r="3.5" />
      <path d="M5.5 17.5 L10 8 H14 M12 8 L18.5 17.5 M9 8 H15" />
    </>
  ),
  run: <path d="M6 18l4-4-4-4M12 18l4-4-4-4" />,
  swim: (
    <>
      <path d="M4 9c1.5-1.5 3-1.5 4.5 0s3 1.5 4.5 0 3-1.5 4.5 0 3 1.5 4.5 0" />
      <path d="M4 13.5c1.5-1.5 3-1.5 4.5 0s3 1.5 4.5 0 3-1.5 4.5 0 3 1.5 4.5 0" />
    </>
  ),
  walk: (
    <>
      <ellipse cx="9" cy="8" rx="2" ry="3" transform="rotate(-15 9 8)" />
      <ellipse cx="15" cy="15" rx="2" ry="3" transform="rotate(15 15 15)" />
    </>
  ),
  row: (
    <>
      <path d="M5 19L17 7" />
      <ellipse cx="18.5" cy="5.5" rx="2.2" ry="1.1" transform="rotate(45 18.5 5.5)" />
    </>
  ),
  multisport: (
    <>
      <circle cx="9" cy="9" r="3.2" />
      <circle cx="15" cy="9" r="3.2" />
      <circle cx="12" cy="14.5" r="3.2" />
    </>
  ),
  transition: (
    <>
      <path d="M6 9a6 6 0 0 1 10.5-3.5M18 5v3.5h-3.5" />
      <path d="M18 15a6 6 0 0 1-10.5 3.5M6 19v-3.5h3.5" />
    </>
  ),
};

export function SportIcon({ sport, size = 22 }: { sport: Sport; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {PATHS[sport]}
    </svg>
  );
}
