import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createShoe, createShoeCatalogEntry, searchShoeCatalog } from "../../api/gear";
import type { ShoeCatalogEntry } from "../../api/types";

const inputStyle: React.CSSProperties = {
  padding: "8px 12px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 13,
  color: "var(--ink)",
};

export function AddShoeForm({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient();
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<ShoeCatalogEntry | null>(null);
  const [colourway, setColourway] = useState("");
  const [name, setName] = useState("");
  const [limitKm, setLimitKm] = useState("600");

  // "add new model" sub-form state
  const [addingNew, setAddingNew] = useState(false);
  const [newManufacturer, setNewManufacturer] = useState("");
  const [newModel, setNewModel] = useState("");
  const [newVersion, setNewVersion] = useState("");

  const { data } = useQuery({
    queryKey: ["shoe-catalog", query],
    queryFn: () => searchShoeCatalog(query),
    enabled: !selected && query.trim().length > 0,
  });

  const addMutation = useMutation({
    mutationFn: () =>
      createShoe({
        shoe_model_version_id: selected!.shoe_model_version_id,
        colourway,
        name: name || undefined,
        limit_km: Number(limitKm),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["shoes"] });
      onDone();
    },
  });

  const createModelMutation = useMutation({
    mutationFn: () =>
      createShoeCatalogEntry({
        manufacturer: newManufacturer.trim(),
        model: newModel.trim(),
        version: newVersion.trim(),
      }),
    onSuccess: (entry) => {
      queryClient.invalidateQueries({ queryKey: ["shoe-catalog"] });
      setSelected(entry);
      setAddingNew(false);
    },
  });

  if (!selected) {
    const showAddNew = query.trim().length > 0;

    if (addingNew) {
      return (
        <div style={{ display: "flex", flexDirection: "column", gap: 8, marginBottom: 16 }}>
          <span style={{ fontSize: 13, color: "var(--ink3)" }}>Add a new shoe model to the catalog</span>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <input
              placeholder="Manufacturer (e.g. Nike)"
              value={newManufacturer}
              onChange={(e) => setNewManufacturer(e.target.value)}
              style={inputStyle}
              autoFocus
            />
            <input
              placeholder="Model (e.g. Pegasus)"
              value={newModel}
              onChange={(e) => setNewModel(e.target.value)}
              style={inputStyle}
            />
            <input
              placeholder="Version (e.g. 41, optional)"
              value={newVersion}
              onChange={(e) => setNewVersion(e.target.value)}
              style={{ ...inputStyle, width: 150 }}
            />
            <button
              onClick={() => createModelMutation.mutate()}
              disabled={
                !newManufacturer.trim() ||
                !newModel.trim() ||
                createModelMutation.isPending
              }
              style={{
                border: "none",
                borderRadius: 8,
                background: "var(--ember)",
                color: "#fff",
                fontSize: 13,
                fontWeight: 700,
                padding: "8px 16px",
              }}
            >
              {createModelMutation.isPending ? "Adding…" : "Add to catalog"}
            </button>
            <button
              onClick={() => setAddingNew(false)}
              style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 13 }}
            >
              Cancel
            </button>
          </div>
          {createModelMutation.isError && (
            <span style={{ fontSize: 12, color: "var(--ember)" }}>Failed to add shoe model. Please try again.</span>
          )}
        </div>
      );
    }

    return (
      <div style={{ display: "flex", flexDirection: "column", gap: 8, marginBottom: 16 }}>
        <input
          placeholder="Search the shoe catalog (e.g. Nike Vaporfly)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={inputStyle}
          autoFocus
        />
        {data && data.data.length > 0 && (
          <div style={{ display: "flex", flexDirection: "column", border: "1px solid var(--line)", borderRadius: 8, overflow: "hidden" }}>
            {data.data.map((entry) => (
              <button
                key={entry.shoe_model_version_id}
                onClick={() => setSelected(entry)}
                style={{
                  textAlign: "left",
                  padding: "8px 12px",
                  border: "none",
                  borderTop: "1px solid var(--line)",
                  background: "var(--card)",
                  fontSize: 13,
                  color: "var(--ink)",
                }}
              >
                {entry.display_name}
              </button>
            ))}
          </div>
        )}
        {showAddNew && (
          <button
            onClick={() => setAddingNew(true)}
            style={{
              alignSelf: "flex-start",
              border: "none",
              background: "none",
              color: "var(--ink3)",
              fontSize: 13,
              padding: 0,
              cursor: "pointer",
            }}
          >
            + Can't find your shoe? Add it to the catalog
          </button>
        )}
      </div>
    );
  }

  return (
    <div style={{ display: "flex", gap: 8, marginBottom: 16, flexWrap: "wrap", alignItems: "center" }}>
      <span style={{ fontSize: 13, fontWeight: 600 }}>{selected.display_name}</span>
      <input placeholder="Colourway" value={colourway} onChange={(e) => setColourway(e.target.value)} style={inputStyle} />
      <input placeholder="Custom name (optional)" value={name} onChange={(e) => setName(e.target.value)} style={inputStyle} />
      <input
        placeholder="Limit km"
        type="number"
        value={limitKm}
        onChange={(e) => setLimitKm(e.target.value)}
        style={{ ...inputStyle, width: 100 }}
      />
      <button
        onClick={() => addMutation.mutate()}
        disabled={!colourway.trim() || addMutation.isPending}
        style={{ border: "none", borderRadius: 8, background: "var(--ember)", color: "#fff", fontSize: 13, fontWeight: 700, padding: "8px 16px" }}
      >
        Add
      </button>
      <button onClick={() => setSelected(null)} style={{ border: "none", background: "none", color: "var(--ink3)", fontSize: 13 }}>
        Back
      </button>
    </div>
  );
}
