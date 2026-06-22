import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listShoes } from "../../api/gear";
import { AddShoeForm } from "./AddShoeForm";
import { ShoeCard } from "./ShoeCard";

export function ShoesSection() {
  const { data } = useQuery({ queryKey: ["shoes"], queryFn: listShoes });
  const [adding, setAdding] = useState(false);

  const shoes = data?.data ?? [];

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 14 }}>
        <h2 style={{ fontSize: 18, fontWeight: 700, margin: 0 }}>Run shoes</h2>
        <button
          onClick={() => setAdding(!adding)}
          style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, padding: "6px 12px", fontSize: 13, fontWeight: 600 }}
        >
          {adding ? "Cancel" : "+ Add shoes"}
        </button>
      </div>

      {adding && <AddShoeForm onDone={() => setAdding(false)} />}

      {shoes.length === 0 ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>No shoes tracked yet.</div>
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(160px, 1fr))", gap: 14 }}>
          {shoes.map((shoe) => (
            <ShoeCard key={shoe.id} shoe={shoe} />
          ))}
        </div>
      )}
    </div>
  );
}
