import type { RaceDayForm, RaceDayFormBand } from "../../api/types";

const BAND_LABEL: Record<RaceDayFormBand, string> = {
  very_fresh: "Very fresh",
  fresh: "Fresh",
  neutral: "Neutral",
  fatigued: "Fatigued",
};
const BAND_COLOR: Record<RaceDayFormBand, string> = {
  very_fresh: "#2fa66a",
  fresh: "#2fa66a",
  neutral: "var(--ink2)",
  fatigued: "#e0442e",
};

function tile(label: string, value: number | null) {
  return (
    <div style={{ flex: 1 }}>
      <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ink3)" }}>
        {label}
      </div>
      <div className="mono" style={{ fontSize: 20, fontWeight: 700, marginTop: 2 }}>{value != null ? value.toFixed(0) : "–"}</div>
    </div>
  );
}

/** README §4.2.3 "Race-day form" card. */
export function RaceDayFormCard({ form }: { form: RaceDayForm }) {
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--line)", borderRadius: 14, padding: 18, minWidth: 290 }}>
      <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--ink3)", marginBottom: 10 }}>
        {form.projected ? "Projected race-day form" : "Race-day form"}
      </div>
      {form.insufficient_data ? (
        <div style={{ fontSize: 13, color: "var(--ink3)" }}>Not enough scheduled yet.</div>
      ) : (
        <>
          <div style={{ display: "flex", gap: 14 }}>
            {tile("CTL", form.ctl)}
            {tile("ATL", form.atl)}
            {tile("TSB", form.tsb)}
          </div>
          {form.band && (
            <span
              style={{
                display: "inline-block",
                marginTop: 12,
                fontSize: 12,
                fontWeight: 700,
                padding: "3px 10px",
                borderRadius: 20,
                color: BAND_COLOR[form.band],
                border: `1px solid ${BAND_COLOR[form.band]}`,
              }}
            >
              {BAND_LABEL[form.band]}
            </span>
          )}
        </>
      )}
    </div>
  );
}
