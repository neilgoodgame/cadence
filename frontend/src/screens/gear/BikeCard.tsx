import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createComponent, getBike, updateBike } from "../../api/gear";
import { Card } from "../../components/Card";
import type { Bike } from "../../api/types";
import { ComponentRow } from "./ComponentRow";
import { WEAR_STATUS_COLOR, WEAR_STATUS_LABEL, type WearStatus, wearStatus } from "../../lib/gear";

const inputStyle: React.CSSProperties = {
  padding: "6px 10px",
  borderRadius: 6,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 13,
  color: "var(--ink)",
};

function worstStatus(statuses: WearStatus[]): WearStatus | null {
  if (statuses.includes("replace")) return "replace";
  if (statuses.includes("soon")) return "soon";
  if (statuses.length > 0) return "good";
  return null;
}

export function BikeCard({ bike }: { bike: Bike }) {
  const [expanded, setExpanded] = useState(false);
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [limitKm, setLimitKm] = useState("");
  const [editingDistance, setEditingDistance] = useState(false);
  const [distanceKmInput, setDistanceKmInput] = useState("");
  const queryClient = useQueryClient();

  const { data: detail } = useQuery({
    queryKey: ["bike", bike.id],
    queryFn: () => getBike(bike.id),
    enabled: expanded,
  });

  const addMutation = useMutation({
    mutationFn: () => createComponent(bike.id, { name, limit_km: Number(limitKm) }),
    onSuccess: () => {
      setName("");
      setLimitKm("");
      setAdding(false);
      queryClient.invalidateQueries({ queryKey: ["bike", bike.id] });
    },
  });

  const updateDistanceMutation = useMutation({
    mutationFn: () => updateBike(bike.id, { distance_km: Number(distanceKmInput) }),
    onSuccess: () => {
      setEditingDistance(false);
      queryClient.invalidateQueries({ queryKey: ["bikes"] });
    },
  });

  function startEditingDistance(e: React.MouseEvent) {
    e.stopPropagation();
    setDistanceKmInput(String(bike.distance_km));
    setEditingDistance(true);
  }

  const overall = detail ? worstStatus(detail.components.map((c) => wearStatus(c.km, c.limit_km))) : null;

  return (
    <Card>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", cursor: "pointer" }} onClick={() => setExpanded(!expanded)}>
        <div>
          <div style={{ fontSize: 16, fontWeight: 700 }}>{bike.name}</div>
          <div style={{ fontSize: 12, color: "var(--ink3)", marginTop: 2 }}>
            {bike.kind.toUpperCase()}
            {bike.groupset && ` · ${bike.groupset}`}
          </div>
        </div>
        {overall && (
          <span
            style={{
              fontSize: 11,
              fontWeight: 600,
              padding: "2px 8px",
              borderRadius: 20,
              color: WEAR_STATUS_COLOR[overall],
              background: `${WEAR_STATUS_COLOR[overall]}22`,
            }}
          >
            {overall === "good" ? "All healthy" : WEAR_STATUS_LABEL[overall]}
          </span>
        )}
      </div>

      <div className="mono" style={{ display: "flex", gap: 18, marginTop: 12, fontSize: 13, color: "var(--ink2)", alignItems: "center" }}>
        {editingDistance ? (
          <span style={{ display: "flex", gap: 6, alignItems: "center" }} onClick={(e) => e.stopPropagation()}>
            <input
              type="number"
              value={distanceKmInput}
              onChange={(e) => setDistanceKmInput(e.target.value)}
              autoFocus
              style={{ ...inputStyle, width: 80, padding: "3px 8px" }}
            />
            <span>km</span>
            <button
              onClick={() => updateDistanceMutation.mutate()}
              disabled={updateDistanceMutation.isPending}
              style={{ border: "none", borderRadius: 6, background: "var(--ember)", color: "#fff", fontSize: 11, fontWeight: 700, padding: "3px 9px", cursor: "pointer" }}
            >
              {updateDistanceMutation.isPending ? "…" : "Save"}
            </button>
            <button
              onClick={() => setEditingDistance(false)}
              style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 11, cursor: "pointer" }}
            >
              Cancel
            </button>
          </span>
        ) : (
          <span style={{ display: "flex", gap: 6, alignItems: "center" }}>
            {bike.distance_km} km
            <button
              onClick={startEditingDistance}
              title="Edit distance"
              style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12, cursor: "pointer", padding: "0 2px", lineHeight: 1 }}
            >
              ✎
            </button>
          </span>
        )}
        <span>{bike.hours}h</span>
        <span>{bike.rides} rides</span>
        <span>{bike.components} components</span>
      </div>

      {expanded && detail && (
        <div style={{ marginTop: 14 }}>
          {detail.components.map((component) => (
            <ComponentRow key={component.id} bikeId={bike.id} component={component} />
          ))}

          {adding ? (
            <div style={{ display: "flex", gap: 8, marginTop: 10, alignItems: "center" }}>
              <input placeholder="Chain" value={name} onChange={(e) => setName(e.target.value)} style={inputStyle} />
              <input
                placeholder="Limit km"
                type="number"
                value={limitKm}
                onChange={(e) => setLimitKm(e.target.value)}
                style={{ ...inputStyle, width: 100 }}
              />
              <button
                onClick={() => addMutation.mutate()}
                disabled={!name.trim() || !limitKm || addMutation.isPending}
                style={{ border: "none", borderRadius: 6, background: "var(--ember)", color: "#fff", fontSize: 12, fontWeight: 700, padding: "6px 12px" }}
              >
                Add
              </button>
              <button onClick={() => setAdding(false)} style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 12 }}>
                Cancel
              </button>
            </div>
          ) : (
            <button
              onClick={() => setAdding(true)}
              style={{ border: "none", background: "none", color: "var(--ember)", fontSize: 12, fontWeight: 600, marginTop: 10, padding: 0 }}
            >
              + Add component
            </button>
          )}
        </div>
      )}
    </Card>
  );
}
