import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { deleteComponent, serviceComponent } from "../../api/gear";
import type { Component } from "../../api/types";
import { WEAR_STATUS_COLOR, WEAR_STATUS_LABEL, wearStatus } from "../../lib/gear";

export function ComponentRow({ bikeId, component }: { bikeId: string; component: Component }) {
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["bike", bikeId] });
  const [loggingService, setLoggingService] = useState(false);

  const serviceMutation = useMutation({
    mutationFn: () => serviceComponent(component.id, { action: "replaced", reset: true }),
    onSuccess: () => {
      setLoggingService(false);
      invalidate();
    },
  });
  const deleteMutation = useMutation({
    mutationFn: () => deleteComponent(component.id),
    onSuccess: invalidate,
  });

  const status = wearStatus(component.km, component.limit_km);
  const fraction = component.limit_km > 0 ? Math.min(component.km / component.limit_km, 1) : 0;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6, padding: "10px 0", borderTop: "1px solid var(--line)" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <div style={{ fontSize: 13, fontWeight: 600 }}>
          {component.name}
          {component.model && <span style={{ color: "var(--ink3)", fontWeight: 400 }}> · {component.model}</span>}
        </div>
        <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
          <span className="mono" style={{ fontSize: 12, color: "var(--ink2)" }}>
            {component.km} / {component.limit_km} km
          </span>
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
            {WEAR_STATUS_LABEL[status]}
          </span>
        </div>
      </div>
      <div style={{ height: 6, borderRadius: 4, background: "var(--elev)", overflow: "hidden" }}>
        <div style={{ height: "100%", width: `${fraction * 100}%`, background: WEAR_STATUS_COLOR[status] }} />
      </div>
      <div style={{ display: "flex", gap: 12 }}>
        <button
          onClick={() => setLoggingService(true)}
          disabled={loggingService}
          style={{ border: "none", background: "none", color: "var(--ember)", fontSize: 12, fontWeight: 600, padding: 0 }}
        >
          Log replacement
        </button>
        <button
          onClick={() => deleteMutation.mutate()}
          style={{ border: "none", background: "none", color: "#e0442e", fontSize: 12, fontWeight: 600, padding: 0 }}
        >
          Remove
        </button>
      </div>
      {loggingService && (
        <div style={{ fontSize: 12, color: "var(--ink2)" }}>
          Resets this component's distance to 0.{" "}
          <button
            onClick={() => serviceMutation.mutate()}
            disabled={serviceMutation.isPending}
            style={{ border: "none", background: "none", color: "var(--ember)", fontWeight: 700, padding: 0 }}
          >
            Confirm
          </button>{" "}
          <button onClick={() => setLoggingService(false)} style={{ border: "none", background: "none", color: "var(--ink3)", padding: 0 }}>
            Cancel
          </button>
        </div>
      )}
    </div>
  );
}
