import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateShoe } from "../../api/gear";
import { Card } from "../../components/Card";
import type { Shoe } from "../../api/types";
import { WEAR_STATUS_COLOR, wearStatus } from "../../lib/gear";

const RING_RADIUS = 30;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

export function ShoeCard({ shoe }: { shoe: Shoe }) {
  const queryClient = useQueryClient();
  const retireMutation = useMutation({
    mutationFn: () => updateShoe(shoe.id, { retired: true }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["shoes"] }),
  });

  const status = wearStatus(shoe.km, shoe.limit_km);
  const fraction = shoe.limit_km > 0 ? Math.min(shoe.km / shoe.limit_km, 1) : 0;
  const dashOffset = RING_CIRCUMFERENCE * (1 - fraction);

  return (
    <Card style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 10, textAlign: "center" }}>
      <svg width={76} height={76} viewBox="0 0 76 76">
        <circle cx={38} cy={38} r={RING_RADIUS} fill="none" stroke="var(--line)" strokeWidth={6} />
        <circle
          cx={38}
          cy={38}
          r={RING_RADIUS}
          fill="none"
          stroke={WEAR_STATUS_COLOR[status]}
          strokeWidth={6}
          strokeDasharray={RING_CIRCUMFERENCE}
          strokeDashoffset={dashOffset}
          strokeLinecap="round"
          transform="rotate(-90 38 38)"
        />
        <text x={38} y={38} textAnchor="middle" dominantBaseline="middle" fontSize={14} fontWeight={700} fill="var(--ink)">
          {Math.round(fraction * 100)}%
        </text>
      </svg>

      <div style={{ fontSize: 14, fontWeight: 700 }}>{shoe.name}</div>
      {shoe.role && <div style={{ fontSize: 12, color: "var(--ink3)" }}>{shoe.role}</div>}
      <div className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>
        {shoe.km} / {shoe.limit_km} km
      </div>
      <span
        style={{
          fontSize: 11,
          fontWeight: 600,
          padding: "2px 8px",
          borderRadius: 20,
          color: WEAR_STATUS_COLOR[status],
          background: `${WEAR_STATUS_COLOR[status]}22`,
        }}
      >
        {status === "good" ? "In rotation" : status === "soon" ? "Near limit" : "Retire now"}
      </span>

      <button
        onClick={() => retireMutation.mutate()}
        disabled={retireMutation.isPending}
        style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12, fontWeight: 600, padding: 0 }}
      >
        Retire
      </button>
    </Card>
  );
}
