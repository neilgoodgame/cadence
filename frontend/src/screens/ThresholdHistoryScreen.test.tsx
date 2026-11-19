// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import { ThresholdHistoryScreen } from "./ThresholdHistoryScreen";
import { useAuth } from "../auth/AuthContext";
import { getThresholdHistory, getThresholds, listThresholdSuggestions, listZones } from "../api/athletes";
import type { Athlete, ThresholdHistoryPoint, ThresholdsSummary } from "../api/types";

vi.mock("../auth/AuthContext", () => ({ useAuth: vi.fn() }));
vi.mock("../api/athletes");

function athlete(overrides: Partial<Athlete> = {}): Athlete {
  return {
    id: "usr_1",
    name: "Test Athlete",
    email: "athlete@example.com",
    email_verified: true,
    age: null,
    weight_kg: null,
    ftp: 260,
    critical_run_power: 280,
    threshold_pace: "4:30",
    threshold_window_days: 112,
    threshold_sanity_pct: 30,
    threshold_warning_days: 21,
    threshold_decay_enabled: true,
    threshold_decay_tau_days: 25,
    threshold_decay_min_pct: 3,
    threshold_decay_max_pct: 12,
    decoupling_vi_limit_bike: 5,
    decoupling_vi_limit_run: 5,
    decoupling_if_limit: 0.5,
    decoupling_min_steady_minutes: 20,
    decoupling_warmup_minutes: 10,
    decoupling_use_workout_warmup: false,
    decoupling_warm_air_temp: 24,
    decoupling_warm_skin_temp: 33,
    decoupling_hot_air_temp: 30,
    decoupling_hot_skin_temp: 36,
    lthr: 160,
    max_hr: 190,
    resting_hr: 50,
    best_effort_top_n: 10,
    ftp_calculation_method: "twenty_min_test",
    running_power_source: "stryd",
    is_coach: false,
    is_admin: false,
    rename_matched_activities: false,
    append_match_date_to_name: false,
    copy_matched_workout_tags: false,
    lap_source: "original",
    overlap_handling: "off",
    default_shoe_limit_km: 600,
    race_block_weeks: 12,
    race_block_week_defaults: {},
    race_key_session_cap: 5,
    course_section_settings: {
      algorithm: "douglas_peucker",
      flat_max_pct: 1,
      steep_min_pct: 4,
      fit_tolerance_m: 5,
      min_section_m: 400,
      keep_min_rise_m: 20,
      spike_window_m: 30,
      smoothing_window_m: 100,
      resample_step_m: 10,
    },
    ...overrides,
  };
}

function mockAuth(user: Athlete) {
  vi.mocked(useAuth).mockReturnValue({
    user,
    selfId: user.id,
    activeAthleteId: null,
    isCoachAccount: false,
    isAdminAccount: false,
    isLoading: false,
    register: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    setUser: vi.fn(),
    switchToAthlete: vi.fn(),
    switchToSelf: vi.fn(),
  });
}

function point(overrides: Partial<ThresholdHistoryPoint> = {}): ThresholdHistoryPoint {
  return {
    value: 250,
    source_activity_id: null,
    set_by_user_id: null,
    effective_from: "2026-01-01",
    current_from: "2026-01-01",
    is_correction_only: false,
    created_at: "2026-01-01T00:00:00Z",
    kind: "measured",
    ...overrides,
  };
}

function blankThresholds(): ThresholdsSummary {
  const blank = { value: null, previous_value: null, source_activity_id: null, set_by_user_id: null, effective_from: null, stale: false, kind: null, decay: null };
  return { ftp: blank, critical_run_power: blank, threshold_pace: blank };
}

function mockQueries(entries: ThresholdHistoryPoint[]) {
  vi.mocked(getThresholdHistory).mockResolvedValue({ field: "ftp", data: entries });
  vi.mocked(getThresholds).mockResolvedValue(blankThresholds());
  vi.mocked(listZones).mockResolvedValue({ data: [] });
  vi.mocked(listThresholdSuggestions).mockResolvedValue({ data: [] });
}

function renderScreen() {
  return renderWithProviders(<ThresholdHistoryScreen />, { route: "/thresholds/ftp", path: "/thresholds/:field" });
}

// Mirrors ThresholdHistoryScreen's own local fmtShortDate - not exported, so re-derived here
// from a fixed ISO date the same way, rather than hardcoding a locale-specific string.
function expectedShortDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

describe("ThresholdHistoryScreen - decayed ledger rows", () => {
  it("shows a lone decayed row inline with a DECAYED pill, not collapsed", async () => {
    mockAuth(athlete());
    mockQueries([
      point({ value: 250, current_from: "2026-11-19", effective_from: "2026-11-19", kind: "decayed" }),
      point({ value: 260, current_from: "2026-10-05", effective_from: "2026-10-05", kind: "manual" }),
      point({ value: 225, current_from: "2026-08-20", effective_from: "2026-08-07", kind: "measured" }),
    ]);
    renderScreen();

    expect(await screen.findByText("DECAYED")).toBeInTheDocument();
    expect(screen.queryByText("Show entries")).not.toBeInTheDocument();
    expect(screen.queryByText(/entries ·/)).not.toBeInTheDocument();
  });

  it("collapses a run of 3 consecutive decayed rows into one summary line, hidden by default", async () => {
    mockAuth(athlete());
    mockQueries([
      point({ value: 250, current_from: "2026-11-19", effective_from: "2026-11-19", kind: "decayed" }),
      point({ value: 253, current_from: "2026-11-18", effective_from: "2026-11-18", kind: "decayed" }),
      point({ value: 255, current_from: "2026-11-17", effective_from: "2026-11-17", kind: "decayed" }),
      point({ value: 260, current_from: "2026-10-05", effective_from: "2026-10-05", kind: "manual" }),
    ]);
    renderScreen();

    const expectedText =
      `3 entries · 255W → 250W · ${expectedShortDate("2026-11-17")} – ${expectedShortDate("2026-11-19")}`;
    expect(await screen.findByText(expectedText)).toBeInTheDocument();
    expect(screen.getByText("Show entries")).toBeInTheDocument();
    expect(screen.queryByText("253W")).not.toBeInTheDocument();
  });

  it("expands and re-collapses the run on click", async () => {
    const user = userEvent.setup();
    mockAuth(athlete());
    mockQueries([
      point({ value: 250, current_from: "2026-11-19", effective_from: "2026-11-19", kind: "decayed" }),
      point({ value: 253, current_from: "2026-11-18", effective_from: "2026-11-18", kind: "decayed" }),
      point({ value: 255, current_from: "2026-11-17", effective_from: "2026-11-17", kind: "decayed" }),
      point({ value: 260, current_from: "2026-10-05", effective_from: "2026-10-05", kind: "manual" }),
    ]);
    renderScreen();

    await user.click(await screen.findByText("Show entries"));

    expect(screen.getByText("253W")).toBeInTheDocument();
    // 1 DECAYED pill on the run's own summary line, plus one per expanded row.
    expect(screen.getAllByText("DECAYED")).toHaveLength(4);
    expect(screen.getByText("Hide entries")).toBeInTheDocument();

    await user.click(screen.getByText("Hide entries"));

    expect(screen.queryByText("253W")).not.toBeInTheDocument();
    expect(screen.getByText("Show entries")).toBeInTheDocument();
  });
});
