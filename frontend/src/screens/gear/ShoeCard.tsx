import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateShoe } from "../../api/gear";
import { Card } from "../../components/Card";
import type { Shoe } from "../../api/types";
import { WEAR_STATUS_COLOR, wearStatus } from "../../lib/gear";
import { ShoePhotoTimeline } from "./ShoePhotoTimeline";

const RING_RADIUS = 30;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

const editInputStyle: React.CSSProperties = {
  width: 72,
  padding: "3px 8px",
  borderRadius: 6,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 12,
  color: "var(--ink)",
};

export function ShoeCard({ shoe }: { shoe: Shoe }) {
  const queryClient = useQueryClient();
  const [showPhotos, setShowPhotos] = useState(false);
  const [editingMileage, setEditingMileage] = useState(false);
  const [kmInput, setKmInput] = useState("");
  const [limitKmInput, setLimitKmInput] = useState("");
  const retireMutation = useMutation({
    mutationFn: () => updateShoe(shoe.id, { retired: true }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["shoes"] }),
  });
  const updateMileageMutation = useMutation({
    mutationFn: () => updateShoe(shoe.id, { km: Number(kmInput), limit_km: Number(limitKmInput) }),
    onSuccess: () => {
      setEditingMileage(false);
      queryClient.invalidateQueries({ queryKey: ["shoes"] });
    },
  });

  function startEditingMileage() {
    setKmInput(String(shoe.km));
    setLimitKmInput(String(shoe.limit_km));
    setEditingMileage(true);
  }

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
      {editingMileage ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 8, alignItems: "center" }}>
          <div className="mono" style={{ display: "flex", gap: 6, alignItems: "center", fontSize: 12 }}>
            <input type="number" value={kmInput} onChange={(e) => setKmInput(e.target.value)} autoFocus style={editInputStyle} />
            <span>/</span>
            <input type="number" value={limitKmInput} onChange={(e) => setLimitKmInput(e.target.value)} style={editInputStyle} />
            <span>km</span>
          </div>
          <div style={{ display: "flex", gap: 10 }}>
            <button
              onClick={() => updateMileageMutation.mutate()}
              disabled={updateMileageMutation.isPending}
              style={{ border: "none", borderRadius: 6, background: "var(--ember)", color: "#fff", fontSize: 11, fontWeight: 700, padding: "4px 12px", cursor: "pointer" }}
            >
              {updateMileageMutation.isPending ? "…" : "Save"}
            </button>
            <button
              onClick={() => setEditingMileage(false)}
              style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 11, cursor: "pointer" }}
            >
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <div className="mono" style={{ display: "flex", gap: 6, alignItems: "center", fontSize: 12, color: "var(--ink2)" }}>
          {shoe.km} / {shoe.limit_km} km
          <button
            onClick={startEditingMileage}
            title="Edit distance / retirement limit"
            style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12, cursor: "pointer", padding: "0 2px", lineHeight: 1 }}
          >
            ✎
          </button>
        </div>
      )}
      {!editingMileage && (
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
      )}

      {!editingMileage && (
      <div style={{ display: "flex", gap: 14 }}>
        <button
          onClick={() => setShowPhotos((v) => !v)}
          style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12, fontWeight: 600, padding: 0, cursor: "pointer" }}
        >
          {showPhotos ? "Hide photos" : "Photos"}
        </button>
        <button
          onClick={() => retireMutation.mutate()}
          disabled={retireMutation.isPending}
          style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12, fontWeight: 600, padding: 0, cursor: "pointer" }}
        >
          Retire
        </button>
      </div>
      )}

      {showPhotos && (
        <div style={{ alignSelf: "stretch", textAlign: "left" }}>
          <ShoePhotoTimeline shoeId={shoe.id} currentKm={shoe.km} />
        </div>
      )}
    </Card>
  );
}
