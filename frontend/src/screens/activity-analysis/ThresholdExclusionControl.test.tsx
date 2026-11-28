// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../../test/renderWithProviders";
import { ThresholdExclusionControl } from "./ThresholdExclusionControl";
import { ExcludedActivitiesList } from "../ExcludedActivitiesList";
import { useAuth } from "../../auth/AuthContext";
import { getContexts } from "../../api/auth";
import { excludeFromThresholds, includeInThresholds, listThresholdExclusions } from "../../api/athletes";
import { ApiError, type Activity, type ThresholdExclusion, type ThresholdExclusionEffect } from "../../api/types";

vi.mock("../../auth/AuthContext", () => ({ useAuth: vi.fn() }));
vi.mock("../../api/athletes");
vi.mock("../../api/auth");

function mockAuth({ activeAthleteId = null, isCoachAccount = false }: { activeAthleteId?: string | null; isCoachAccount?: boolean } = {}) {
  vi.mocked(useAuth).mockReturnValue({
    user: { id: "usr_1" },
    selfId: "usr_1",
    activeAthleteId,
    isCoachAccount,
  } as unknown as ReturnType<typeof useAuth>);
}

// The control only reads these three fields.
function activity(overrides: Partial<Activity> = {}): Activity {
  return { id: "act_ride", sport: "bike", primary_activity_id: null, ...overrides } as Activity;
}

function exclusion(overrides: Partial<ThresholdExclusion> = {}): ThresholdExclusion {
  return {
    field: "ftp",
    activity_id: "act_ride",
    activity_name: "Concept2 ride",
    activity_date: "2025-04-18",
    set_by_user_id: "usr_1",
    created_at: "2026-11-28T00:17:16Z",
    ...overrides,
  };
}

function effect(overrides: Partial<ThresholdExclusionEffect> = {}): ThresholdExclusionEffect {
  return {
    field: "ftp",
    activity_id: "act_ride",
    excluded: true,
    preview: true,
    value_before: 314,
    value_after: 252,
    rows_removed: [{ kind: "measured", source_activity_id: "act_ride", effective_from: "2025-04-18", current_from: "2025-04-18", value: 314 }],
    rows_added: [],
    ...overrides,
  };
}

beforeEach(() => {
  vi.resetAllMocks();
  mockAuth();
  vi.mocked(listThresholdExclusions).mockResolvedValue({ data: [] });
});

describe("ThresholdExclusionControl", () => {
  it("offers one checkbox per field the sport can define", async () => {
    renderWithProviders(<ThresholdExclusionControl activity={activity({ sport: "run" })} athleteId="usr_1" />);

    expect(await screen.findByLabelText("Exclude from critical running power")).not.toBeChecked();
    expect(screen.getByLabelText("Exclude from threshold pace")).not.toBeChecked();
    expect(screen.queryByLabelText("Exclude from FTP")).toBeNull();
    expect(listThresholdExclusions).toHaveBeenCalledWith("usr_1", { activityId: "act_ride" });
  });

  it("renders nothing for a sport that defines no threshold, or for a duplicate copy", () => {
    const { container } = renderWithProviders(
      <>
        <ThresholdExclusionControl activity={activity({ sport: "swim" })} athleteId="usr_1" />
        <ThresholdExclusionControl activity={activity({ primary_activity_id: "act_primary" })} athleteId="usr_1" />
      </>,
    );

    expect(container).toBeEmptyDOMElement();
    expect(listThresholdExclusions).not.toHaveBeenCalled();
  });

  it("previews the change first and only applies it once confirmed", async () => {
    vi.mocked(excludeFromThresholds).mockResolvedValue(effect());
    renderWithProviders(<ThresholdExclusionControl activity={activity()} athleteId="usr_1" />);

    await userEvent.click(await screen.findByLabelText("Exclude from FTP"));

    const dialog = await screen.findByRole("dialog", { name: "Exclude from FTP" });
    expect(excludeFromThresholds).toHaveBeenCalledExactlyOnceWith("usr_1", "ftp", "act_ride", true);
    expect(dialog).toHaveTextContent("Your current FTP would change from 314 W to 252 W.");
    expect(dialog).toHaveTextContent("Removed from your history");
    expect(dialog).toHaveTextContent("314 W measured (this activity)");

    vi.mocked(listThresholdExclusions).mockResolvedValue({ data: [exclusion()] });
    await userEvent.click(screen.getByRole("button", { name: "Exclude" }));

    expect(excludeFromThresholds).toHaveBeenLastCalledWith("usr_1", "ftp", "act_ride", false);
    expect(await screen.findByLabelText("Exclude from FTP")).toBeChecked();
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("cancelling a preview saves nothing", async () => {
    vi.mocked(excludeFromThresholds).mockResolvedValue(effect({ value_after: 314, rows_removed: [] }));
    renderWithProviders(<ThresholdExclusionControl activity={activity()} athleteId="usr_1" />);

    await userEvent.click(await screen.findByLabelText("Exclude from FTP"));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("Your current FTP stays 314 W. Your history doesn't change.");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(excludeFromThresholds).toHaveBeenCalledTimes(1);
  });

  it("unticking an excluded field previews including it again", async () => {
    vi.mocked(listThresholdExclusions).mockResolvedValue({ data: [exclusion()] });
    vi.mocked(includeInThresholds).mockResolvedValue(effect({ excluded: false, value_before: 252, value_after: 314 }));
    renderWithProviders(<ThresholdExclusionControl activity={activity()} athleteId="usr_1" />);

    await userEvent.click(await screen.findByLabelText("Exclude from FTP"));

    expect(await screen.findByRole("dialog", { name: "Include in FTP again" })).toBeInTheDocument();
    expect(includeInThresholds).toHaveBeenCalledWith("usr_1", "ftp", "act_ride", true);
  });

  it("says the change may still be finishing when applying it times out", async () => {
    vi.mocked(excludeFromThresholds)
      .mockResolvedValueOnce(effect())
      .mockRejectedValueOnce(new ApiError(504, { error: { type: "api_error", code: "timeout", message: "Gateway Timeout", param: null } }));
    renderWithProviders(<ThresholdExclusionControl activity={activity()} athleteId="usr_1" />);

    await userEvent.click(await screen.findByLabelText("Exclude from FTP"));
    await userEvent.click(await screen.findByRole("button", { name: "Exclude" }));

    expect(await screen.findByText(/the change may still be finishing/)).toBeInTheDocument();
  });

  it("shows a viewer the excluded state without a checkbox", async () => {
    mockAuth({ activeAthleteId: "usr_1", isCoachAccount: true });
    vi.mocked(getContexts).mockResolvedValue({ coaching: [{ user_id: "usr_1", role: "viewer" }] } as unknown as Awaited<ReturnType<typeof getContexts>>);
    vi.mocked(listThresholdExclusions).mockResolvedValue({ data: [exclusion()] });
    renderWithProviders(<ThresholdExclusionControl activity={activity()} athleteId="usr_1" />);

    expect(await screen.findByText("Excluded from FTP")).toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).toBeNull();
  });
});

describe("ExcludedActivitiesList", () => {
  it("lists the field's exclusions and previews including one again", async () => {
    vi.mocked(listThresholdExclusions).mockResolvedValue({ data: [exclusion()] });
    vi.mocked(includeInThresholds).mockResolvedValue(effect({ excluded: false, value_before: 247, value_after: 247 }));
    renderWithProviders(<ExcludedActivitiesList athleteId="usr_1" field="ftp" />);

    expect(await screen.findByRole("link", { name: "Concept2 ride" })).toHaveAttribute("href", "/activities/act_ride");
    expect(listThresholdExclusions).toHaveBeenCalledWith("usr_1", { field: "ftp" });

    await userEvent.click(screen.getByRole("button", { name: "Include again" }));

    expect(await screen.findByRole("dialog", { name: "Include in FTP again" })).toHaveTextContent("Your current FTP stays 247 W.");
    expect(includeInThresholds).toHaveBeenCalledWith("usr_1", "ftp", "act_ride", true);
  });

  it("renders nothing when nothing is excluded", async () => {
    const { container } = renderWithProviders(<ExcludedActivitiesList athleteId="usr_1" field="ftp" />);

    await vi.waitFor(() => expect(listThresholdExclusions).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
