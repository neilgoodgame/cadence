// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../../test/renderWithProviders";
import { BestEffortsTab } from "./BestEffortsTab";
import { useAuth } from "../../auth/AuthContext";
import { trimBestEfforts, updateAthlete } from "../../api/athletes";
import type { Athlete, AthleteUpdateResponse } from "../../api/types";

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
    ftp: 250,
    critical_run_power: 260,
    threshold_pace: "4:30",
    threshold_window_days: 112,
    threshold_sanity_pct: 30,
    threshold_warning_days: 21,
    threshold_decay_enabled: false,
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

function athleteUpdateResponse(overrides: Partial<Athlete> = {}): AthleteUpdateResponse {
  return { ...athlete(overrides), zones_recomputed: [] };
}

function mockAuth(user: Athlete, setUser = vi.fn()) {
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
    setUser,
    switchToAthlete: vi.fn(),
    switchToSelf: vi.fn(),
  });
}

// "Save" also labels the (unrelated) entries-per-window button above this section - scope to
// the "Threshold history" section's own heading to find the right one.
function saveButton() {
  const section = screen.getByRole("heading", { name: "Threshold history" }).closest("section")!;
  return within(section).getByRole("button", { name: "Save" });
}

function sectionNamed(heading: string) {
  return within(screen.getByRole("heading", { name: heading }).closest("section")!);
}

describe("BestEffortsTab - gradual decay settings", () => {
  it("disables the decay inputs while threshold_decay_enabled is false", () => {
    mockAuth(athlete({ threshold_decay_enabled: false }));
    renderWithProviders(<BestEffortsTab />);

    expect(screen.getByLabelText("Gradual decay")).not.toBeChecked();
    expect(screen.getByLabelText("Decay time constant (days)")).toBeDisabled();
    expect(screen.getByLabelText("Minimum decay (%)")).toBeDisabled();
    expect(screen.getByLabelText("Maximum decay (%)")).toBeDisabled();
  });

  it("enables the decay inputs when Gradual decay is checked", async () => {
    const user = userEvent.setup();
    mockAuth(athlete({ threshold_decay_enabled: false }));
    renderWithProviders(<BestEffortsTab />);

    await user.click(screen.getByLabelText("Gradual decay"));

    expect(screen.getByLabelText("Decay time constant (days)")).not.toBeDisabled();
    expect(screen.getByLabelText("Minimum decay (%)")).not.toBeDisabled();
    expect(screen.getByLabelText("Maximum decay (%)")).not.toBeDisabled();
  });

  it("shows the validation error and disables Save when minimum exceeds maximum", async () => {
    const user = userEvent.setup();
    mockAuth(athlete({ threshold_decay_enabled: false, threshold_decay_min_pct: 3, threshold_decay_max_pct: 12 }));
    renderWithProviders(<BestEffortsTab />);

    await user.click(screen.getByLabelText("Gradual decay"));
    fireEvent.change(screen.getByLabelText("Minimum decay (%)"), { target: { value: "8" } });
    fireEvent.change(screen.getByLabelText("Maximum decay (%)"), { target: { value: "5" } });

    expect(screen.getByText("Minimum decay must not exceed maximum decay.")).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
  });

  it("clears the error and re-enables Save once minimum no longer exceeds maximum", async () => {
    const user = userEvent.setup();
    mockAuth(athlete({ threshold_decay_enabled: false, threshold_decay_min_pct: 3, threshold_decay_max_pct: 12 }));
    renderWithProviders(<BestEffortsTab />);

    await user.click(screen.getByLabelText("Gradual decay"));
    fireEvent.change(screen.getByLabelText("Minimum decay (%)"), { target: { value: "8" } });
    fireEvent.change(screen.getByLabelText("Maximum decay (%)"), { target: { value: "5" } });
    fireEvent.change(screen.getByLabelText("Maximum decay (%)"), { target: { value: "9" } });

    expect(screen.queryByText("Minimum decay must not exceed maximum decay.")).not.toBeInTheDocument();
    expect(saveButton()).not.toBeDisabled();
  });

  it("saves the four decay settings fields via updateAthlete", async () => {
    const user = userEvent.setup();
    const setUser = vi.fn();
    const stored = athlete({
      threshold_decay_enabled: false, threshold_decay_tau_days: 25, threshold_decay_min_pct: 3, threshold_decay_max_pct: 12,
    });
    mockAuth(stored, setUser);
    vi.mocked(updateAthlete).mockResolvedValue(athleteUpdateResponse({ threshold_decay_enabled: true, threshold_decay_min_pct: 8, threshold_decay_max_pct: 9 }));
    renderWithProviders(<BestEffortsTab />);

    await user.click(screen.getByLabelText("Gradual decay"));
    fireEvent.change(screen.getByLabelText("Minimum decay (%)"), { target: { value: "8" } });
    fireEvent.change(screen.getByLabelText("Maximum decay (%)"), { target: { value: "9" } });
    await user.click(saveButton());

    expect(updateAthlete).toHaveBeenCalledWith(stored.id, {
      threshold_window_days: stored.threshold_window_days,
      threshold_sanity_pct: stored.threshold_sanity_pct,
      threshold_decay_enabled: true,
      threshold_decay_tau_days: 25,
      threshold_decay_min_pct: 8,
      threshold_decay_max_pct: 9,
    });
    await waitFor(() => expect(setUser).toHaveBeenCalled());
  });
});

describe("BestEffortsTab - pace/power retention (issue #597)", () => {
  it("defaults the pace checkbox to checked (unlimited) and the power checkbox to unchecked", () => {
    mockAuth(athlete({ best_effort_top_n_running_pace: 0, best_effort_top_n_power: null }));
    renderWithProviders(<BestEffortsTab />);

    expect(screen.getByLabelText("Track all running pace activities (no limit)")).toBeChecked();
    expect(screen.getByLabelText("Track all power activities (no limit)")).not.toBeChecked();
  });

  it("unchecking the pace checkbox reveals a custom limit, and Save sends it", async () => {
    const user = userEvent.setup();
    const setUser = vi.fn();
    const stored = athlete({ best_effort_top_n_running_pace: 0 });
    mockAuth(stored, setUser);
    vi.mocked(updateAthlete).mockResolvedValue(athleteUpdateResponse({ best_effort_top_n_running_pace: 5 }));
    renderWithProviders(<BestEffortsTab />);
    const pace = sectionNamed("Running pace retention");

    await user.click(pace.getByLabelText("Track all running pace activities (no limit)"));
    fireEvent.change(pace.getByRole("spinbutton"), { target: { value: "5" } });
    await user.click(pace.getByRole("button", { name: "Save" }));

    expect(updateAthlete).toHaveBeenCalledWith(stored.id, { best_effort_top_n_running_pace: 5 });
    // Decreasing from unlimited (0) to a finite cap must trim existing rows down to it.
    await waitFor(() => expect(trimBestEfforts).toHaveBeenCalledWith(stored.id));
    await waitFor(() => expect(setUser).toHaveBeenCalled());
  });

  it("checking the power checkbox and saving sends the unlimited override plus a trim", async () => {
    const user = userEvent.setup();
    const setUser = vi.fn();
    const stored = athlete({ best_effort_top_n_power: null });
    mockAuth(stored, setUser);
    vi.mocked(updateAthlete).mockResolvedValue(athleteUpdateResponse({ best_effort_top_n_power: 0 }));
    renderWithProviders(<BestEffortsTab />);
    const power = sectionNamed("Power retention");

    await user.click(power.getByLabelText("Track all power activities (no limit)"));
    await user.click(power.getByRole("button", { name: "Save" }));

    expect(updateAthlete).toHaveBeenCalledWith(stored.id, { best_effort_top_n_power: 0 });
    await waitFor(() => expect(trimBestEfforts).toHaveBeenCalledWith(stored.id));
    await waitFor(() => expect(setUser).toHaveBeenCalled());
  });

  it("unchecking an already-overridden power setting clears it with the -1 sentinel", async () => {
    const user = userEvent.setup();
    const setUser = vi.fn();
    const stored = athlete({ best_effort_top_n_power: 0 });
    mockAuth(stored, setUser);
    vi.mocked(updateAthlete).mockResolvedValue(athleteUpdateResponse({ best_effort_top_n_power: null }));
    renderWithProviders(<BestEffortsTab />);
    const power = sectionNamed("Power retention");
    expect(power.getByLabelText("Track all power activities (no limit)")).toBeChecked();

    await user.click(power.getByLabelText("Track all power activities (no limit)"));
    await user.click(power.getByRole("button", { name: "Save" }));

    expect(updateAthlete).toHaveBeenCalledWith(stored.id, { best_effort_top_n_power: -1 });
  });
});
