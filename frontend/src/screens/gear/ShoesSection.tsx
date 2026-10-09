import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listShoes, updateShoe } from "../../api/gear";
import { useAuth } from "../../auth/AuthContext";
import { AddShoeForm } from "./AddShoeForm";
import { ImportShoesPanel } from "./ImportShoesPanel";
import { ShoeCard } from "./ShoeCard";
import { ShoesTable } from "./ShoesTable";
import type { GearView } from "./viewMode";

export function ShoesSection({ view = "cards" }: { view?: GearView }) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: ["shoes"], queryFn: listShoes });
  const [adding, setAdding] = useState(false);
  const [importing, setImporting] = useState(false);

  const shoes = data?.data ?? [];
  const unsetLimitShoes = shoes.filter((s) => s.limit_km === 0);

  const backfillLimitMutation = useMutation({
    mutationFn: async () => {
      const limitKm = user!.default_shoe_limit_km;
      for (const shoe of unsetLimitShoes) {
        await updateShoe(shoe.id, { limit_km: limitKm });
      }
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["shoes"] }),
  });

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 14 }}>
        <h2 style={{ fontSize: 18, fontWeight: 700, margin: 0 }}>Run shoes</h2>
        <div style={{ display: "flex", gap: 8 }}>
          {unsetLimitShoes.length > 0 && (
            <button
              onClick={() => {
                if (
                  window.confirm(
                    `Set a ${user!.default_shoe_limit_km}km wear limit on ${unsetLimitShoes.length} shoe${unsetLimitShoes.length === 1 ? "" : "s"} with no limit set?`,
                  )
                ) {
                  backfillLimitMutation.mutate();
                }
              }}
              disabled={backfillLimitMutation.isPending}
              title="Set the default wear limit (from Preferences) on every shoe that doesn't have one"
              style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, padding: "6px 12px", fontSize: 13, fontWeight: 600 }}
            >
              {backfillLimitMutation.isPending ? "Setting…" : `Set wear limit (${unsetLimitShoes.length})`}
            </button>
          )}
          <button
            onClick={() => setImporting(!importing)}
            style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, padding: "6px 12px", fontSize: 13, fontWeight: 600 }}
          >
            {importing ? "Cancel" : "Import from CSV"}
          </button>
          <button
            onClick={() => setAdding(!adding)}
            style={{ border: "1px solid var(--line)", background: "var(--card)", borderRadius: 8, padding: "6px 12px", fontSize: 13, fontWeight: 600 }}
          >
            {adding ? "Cancel" : "+ Add shoes"}
          </button>
        </div>
      </div>

      {importing && <ImportShoesPanel onDone={() => setImporting(false)} />}
      {adding && <AddShoeForm onDone={() => setAdding(false)} />}

      {shoes.length === 0 ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>No shoes tracked yet.</div>
      ) : (
        view === "table" ? (
          <ShoesTable shoes={shoes} />
        ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(160px, 1fr))", gap: 14 }}>
          {shoes.map((shoe) => (
            <ShoeCard key={shoe.id} shoe={shoe} />
          ))}
        </div>
        )
      )}
    </div>
  );
}
