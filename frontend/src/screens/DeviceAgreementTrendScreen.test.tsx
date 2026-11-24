// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../test/renderWithProviders";
import { DeviceAgreementTrendScreen } from "./DeviceAgreementTrendScreen";
import { useAuth } from "../auth/AuthContext";
import { getDeviceAgreementTrend } from "../api/athletes";
import type { Athlete, DeviceAgreementTrendPoint } from "../api/types";

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
    best_effort_top_n_running_pace: 0,
    best_effort_top_n_power: null,
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

function point(overrides: Partial<DeviceAgreementTrendPoint> = {}): DeviceAgreementTrendPoint {
  return {
    activity_id: "act_1",
    name: "Morning ride",
    date: "2026-06-03",
    overlap_seconds: 1830,
    bias: -8.2,
    mae: 11.4,
    rmse: 14.9,
    pearson_r: 0.97,
    within_tolerance_pct: 71.2,
    lag_seconds: 1,
    dropout_seconds_a: 4,
    dropout_seconds_b: 0,
    ...overrides,
  };
}

function mockTrend(
  points: DeviceAgreementTrendPoint[],
  skipped = { no_device_a: 0, no_device_b: 0, insufficient_overlap: 0 },
) {
  vi.mocked(getDeviceAgreementTrend).mockResolvedValue({
    field: "power",
    device_a: "trainer",
    device_b: "pedals",
    sport: "bike",
    from_date: "2026-08-24",
    to_date: "2026-11-22",
    points,
    skipped,
  });
}

function renderScreen(route = "/devices/power/agreement-trend") {
  return renderWithProviders(<DeviceAgreementTrendScreen />, { route, path: "/devices/:field/agreement-trend" });
}

describe("DeviceAgreementTrendScreen", () => {
  it("renders the trend chart and a caption for non-zero skip counters", async () => {
    mockAuth(athlete());
    mockTrend(
      [point(), point({ activity_id: "act_2", name: "Evening ride", date: "2026-11-10" })],
      { no_device_a: 0, no_device_b: 12, insufficient_overlap: 1 },
    );
    renderScreen();

    expect(await screen.findByLabelText("Device agreement trend chart")).toBeInTheDocument();
    // Default power+bike pickers are trainer (A) vs pedals (B), so the no_device_b counter
    // names pedals.
    expect(
      screen.getByText("12 activities in range had no pedals power source; 1 had both sources but not enough overlap to compare"),
    ).toBeInTheDocument();
  });

  it("redirects an invalid field to power", async () => {
    mockAuth(athlete());
    mockTrend([]);
    renderScreen("/devices/bogus/agreement-trend");

    expect(await screen.findByText("Power device agreement")).toBeInTheDocument();
  });
});
