const PALETTE = [
  "#3d7fd6",
  "#2fa66a",
  "#f0a02e",
  "#e0442e",
  "#ec4a26",
  "#9a6ad0",
  "#8b95a1",
  "#0d9488",
  "#f0823a",
  "#7e858e",
];

export function tagColor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
  return PALETTE[Math.abs(hash) % PALETTE.length];
}

export function tagRgba(name: string, alpha: number): string {
  const hex = tagColor(name);
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}
