import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createBike, listBikes } from "../../api/gear";
import type { BikeKind } from "../../api/types";
import { BikeCard } from "./BikeCard";

const KINDS: BikeKind[] = ["road", "indoor", "gravel", "tt"];

const inputStyle: React.CSSProperties = {
  padding: "8px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 13,
  color: "var(--ink)",
};

export function BikesSection() {
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: ["bikes"], queryFn: listBikes });
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [kind, setKind] = useState<BikeKind>("road");
  const [groupset, setGroupset] = useState("");

  const addMutation = useMutation({
    mutationFn: () => createBike({ name, kind, groupset: groupset || undefined }),
    onSuccess: () => {
      setName("");
      setGroupset("");
      setAdding(false);
      queryClient.invalidateQueries({ queryKey: ["bikes"] });
    },
  });

  const bikes = data?.data ?? [];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 14 }}>
        <h2 style={{ fontSize: 18, fontWeight: 700, margin: 0 }}>Bikes</h2>
        <button
          onClick={() => setAdding(!adding)}
          style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, padding: "6px 12px", fontSize: 13, fontWeight: 600 }}
        >
          {adding ? "Cancel" : "+ Add bike"}
        </button>
      </div>

      {adding && (
        <div style={{ display: "flex", gap: 8, marginBottom: 16, flexWrap: "wrap" }}>
          <input placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} style={inputStyle} />
          <select value={kind} onChange={(e) => setKind(e.target.value as BikeKind)} style={inputStyle}>
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {k}
              </option>
            ))}
          </select>
          <input placeholder="Groupset (optional)" value={groupset} onChange={(e) => setGroupset(e.target.value)} style={inputStyle} />
          <button
            onClick={() => addMutation.mutate()}
            disabled={!name.trim() || addMutation.isPending}
            style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px" }}
          >
            Add
          </button>
        </div>
      )}

      {bikes.length === 0 ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>No bikes in the garage yet.</div>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          {bikes.map((bike) => (
            <BikeCard key={bike.id} bike={bike} />
          ))}
        </div>
      )}
    </div>
  );
}
