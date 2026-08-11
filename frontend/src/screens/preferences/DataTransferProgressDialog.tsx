import type { DataTransferStep } from "../../api/types";

const STEPS: DataTransferStep[] = ["equipment", "workouts", "activities", "races", "scheduled_workouts"];

const STEP_LABELS: Record<DataTransferStep, string> = {
  equipment: "Equipment (bikes, shoes, components)",
  workouts: "Workouts",
  activities: "Activities (streams + laps - this is the slow part)",
  races: "Races",
  scheduled_workouts: "Scheduled workouts",
};

function ProgressBar({ fraction }: { fraction: number }) {
  return (
    <div style={{ height: 8, borderRadius: 4, background: "var(--elev)", overflow: "hidden" }}>
      <div
        style={{
          height: "100%",
          width: `${Math.round(fraction * 100)}%`,
          background: "var(--ember)",
          borderRadius: 4,
          transition: "width 0.3s ease",
        }}
      />
    </div>
  );
}

/** Modal shown while an export/import job is in flight - what kind of data it's currently
 * working on plus a step-based progress bar. Neither backend tracks finer-grained progress
 * within a section (the "activities" section, by far the slowest, has no per-activity
 * counter - see ExportWriter.write's/export_writer.py's docstring for why), so the bar fills
 * in 5 discrete steps rather than smoothly - an honest reflection of what's actually known,
 * not simulated smoothness. */
export function DataTransferProgressDialog({
  title,
  status,
  currentStep,
  errorMessage,
  onClose,
  children,
}: {
  title: string;
  status: "queued" | "processing" | "ready" | "failed";
  currentStep: DataTransferStep | null;
  errorMessage?: string | null;
  onClose: () => void;
  children?: React.ReactNode;
}) {
  const isTerminal = status === "ready" || status === "failed";
  const stepIndex = currentStep ? STEPS.indexOf(currentStep) : -1;
  const fraction = status === "ready" ? 1 : stepIndex >= 0 ? stepIndex / STEPS.length : 0;

  return (
    <div
      onClick={isTerminal ? onClose : undefined}
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(0,0,0,0.4)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        zIndex: 100,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: "var(--card)",
          borderRadius: 14,
          padding: 24,
          width: 420,
          display: "flex",
          flexDirection: "column",
          gap: 16,
        }}
      >
        <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>{title}</h2>

        {!isTerminal && (
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            <div style={{ fontSize: 13, color: "var(--ink2)" }}>
              {currentStep ? STEP_LABELS[currentStep] : "Starting…"}
            </div>
            <ProgressBar fraction={fraction} />
          </div>
        )}

        {status === "failed" && (
          <>
            <div style={{ fontSize: 13, color: "#e0442e" }}>{errorMessage ?? "Something went wrong."}</div>
            <button
              onClick={onClose}
              style={{
                alignSelf: "flex-start",
                border: "1px solid var(--line)",
                borderRadius: 8,
                padding: "8px 16px",
                fontSize: 13,
                fontWeight: 600,
                background: "transparent",
                color: "var(--ink)",
              }}
            >
              Close
            </button>
          </>
        )}

        {status === "ready" && children}
      </div>
    </div>
  );
}
