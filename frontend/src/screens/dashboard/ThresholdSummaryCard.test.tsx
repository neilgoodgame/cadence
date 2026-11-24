// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../../test/renderWithProviders";
import { ThresholdSummaryCard } from "./ThresholdSummaryCard";
import { useAuth } from "../../auth/AuthContext";
import { getThresholds, listThresholdSuggestions } from "../../api/athletes";
import type { Athlete, ThresholdsSummary, ThresholdSummaryEntry } from "../../api/types";

vi.mock("../../auth/AuthContext", () => ({ useAuth: vi.fn() }));
vi.mock("../../api/athletes");

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
    best_effort_top_n_running_pace: 0,
    best_effort_top_n_power: null,
    ftp_calculation_method: "twenty_min_test",
    running_power_source: "stryd",
    bike_power_source: "any",
    hr_source: "any",
    cadence_source: "any",
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

function blankEntry(overrides: Partial<ThresholdSummaryEntry> = {}): ThresholdSummaryEntry {
  return {
    value: null, previous_value: null, source_activity_id: null, set_by_user_id: null,
    effective_from: null, stale: false, kind: null, decay: null,
    ...overrides,
  };
}

function thresholdsSummary(ftp: ThresholdSummaryEntry): ThresholdsSummary {
  return { ftp, critical_run_power: blankEntry(), threshold_pace: blankEntry() };
}

function mockThresholds(ftp: ThresholdSummaryEntry) {
  vi.mocked(getThresholds).mockResolvedValue(thresholdsSummary(ftp));
  vi.mocked(listThresholdSuggestions).mockResolvedValue({ data: [] });
}

function expectedShortDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

describe("ThresholdSummaryCard - decay badge", () => {
  it("shows the decay badge and detail line when the active field's entry is decayed", async () => {
    mockAuth(athlete());
    mockThresholds(blankEntry({
      value: 250, previous_value: 260, effective_from: "2026-11-19", stale: false, kind: "decayed",
      decay: { anchor_value: 260, anchor_effective_from: "2026-10-05", decaying_since: "2027-01-26", target: 229 },
    }));
    renderWithProviders(<ThresholdSummaryCard />);

    expect(await screen.findByText("Modelled · test due")).toBeInTheDocument();
    const expectedDate = expectedShortDate("2026-10-05");
    expect(
      screen.getByText(`Easing from 260 since ${expectedDate}, towards about 229.`),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Refresh" })).not.toBeInTheDocument();
  });

  it("does not show the decay badge or the stale Refresh button for a fresh measured entry", async () => {
    mockAuth(athlete());
    mockThresholds(blankEntry({
      value: 260, previous_value: 225, effective_from: "2026-10-05", stale: false, kind: "measured",
    }));
    renderWithProviders(<ThresholdSummaryCard />);

    await screen.findByText("260");
    expect(screen.queryByText("Modelled · test due")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Refresh" })).not.toBeInTheDocument();
    expect(screen.getByText(/Valid for \d+ more days?/)).toBeInTheDocument();
  });

  it("still shows the stale Refresh button for a non-decayed stale entry", async () => {
    mockAuth(athlete());
    mockThresholds(blankEntry({
      value: 240, previous_value: 235, effective_from: "2020-01-01", stale: true, kind: "measured",
    }));
    renderWithProviders(<ThresholdSummaryCard />);

    expect(await screen.findByText("Aged out of window")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh" })).toBeInTheDocument();
    expect(screen.queryByText("Modelled · test due")).not.toBeInTheDocument();
  });
});
