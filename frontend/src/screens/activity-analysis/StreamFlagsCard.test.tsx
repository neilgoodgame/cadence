// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../../test/renderWithProviders";
import { StreamFlagsCard } from "./StreamFlagsCard";
import { detectStreamFlags, patchStream, unpatchStream } from "../../api/activities";
import type { StreamFlags, StreamPatchResult } from "../../api/types";

vi.mock("../../api/activities");

function flags(overrides: Partial<StreamFlags> = {}): StreamFlags {
  return {
    field: "heartrate",
    kind: "contact_loss",
    segments: [{ start_t: 100, end_t: 105, reference_confirmed: true }],
    ...overrides,
  };
}

function patchResult(overrides: Partial<StreamPatchResult> = {}): StreamPatchResult {
  return {
    patch_id: "pat_1",
    field: "heartrate",
    from_source: "src_reference",
    detected_kind: null,
    segments: [[100, 105]],
    counts: { copied: 6, kept_original: 0, nulled: 0, dropouts_nulled: 0 },
    ...overrides,
  };
}

describe("StreamFlagsCard", () => {
  it("detects and lists a flagged segment", async () => {
    const user = userEvent.setup();
    vi.mocked(detectStreamFlags).mockResolvedValue(flags());
    renderWithProviders(
      <StreamFlagsCard activityId="act_1" field="heartrate" referenceSourceId="src_reference" referenceLabel="Tickr" />,
    );

    await user.click(screen.getByRole("button", { name: "Detect issues" }));

    expect(await screen.findByText("t=100–105 (6s)")).toBeInTheDocument();
    expect(detectStreamFlags).toHaveBeenCalledWith("act_1", "heartrate", "contact_loss", "src_reference");
  });

  it("shows a cadence-lock toggle only for heartrate, and passes the chosen kind through", async () => {
    const user = userEvent.setup();
    vi.mocked(detectStreamFlags).mockResolvedValue(flags({ kind: "cadence_lock", segments: [] }));
    renderWithProviders(
      <StreamFlagsCard activityId="act_1" field="heartrate" referenceSourceId="src_reference" referenceLabel="Tickr" />,
    );

    await user.click(screen.getByRole("button", { name: "Cadence lock" }));
    await user.click(screen.getByRole("button", { name: "Detect issues" }));

    expect(await screen.findByText("No issues detected.")).toBeInTheDocument();
    expect(detectStreamFlags).toHaveBeenCalledWith("act_1", "heartrate", "cadence_lock", "src_reference");
  });

  it("marks an unconfirmed cadence-lock segment as such", async () => {
    const user = userEvent.setup();
    vi.mocked(detectStreamFlags).mockResolvedValue(
      flags({ kind: "cadence_lock", segments: [{ start_t: 10, end_t: 69, reference_confirmed: false }] }),
    );
    renderWithProviders(
      <StreamFlagsCard activityId="act_1" field="heartrate" referenceSourceId="src_reference" referenceLabel="Tickr" />,
    );

    await user.click(screen.getByRole("button", { name: "Cadence lock" }));
    await user.click(screen.getByRole("button", { name: "Detect issues" }));

    expect(await screen.findByText("unconfirmed - no reference corroboration")).toBeInTheDocument();
  });

  it("previews then applies a patch, replacing the button with Patched + Undo", async () => {
    const user = userEvent.setup();
    vi.mocked(detectStreamFlags).mockResolvedValue(flags());
    vi.mocked(patchStream).mockImplementation((_id, params) =>
      Promise.resolve(patchResult({ patch_id: params.preview ? null : "pat_1" })),
    );
    renderWithProviders(
      <StreamFlagsCard activityId="act_1" field="heartrate" referenceSourceId="src_reference" referenceLabel="Tickr" />,
    );
    await user.click(screen.getByRole("button", { name: "Detect issues" }));
    await screen.findByText("t=100–105 (6s)");

    await user.click(screen.getByRole("button", { name: "Patch from Tickr" }));

    expect(await screen.findByText("copied 6, nulled 0")).toBeInTheDocument();
    expect(patchStream).toHaveBeenCalledWith("act_1", {
      field: "heartrate",
      from_source: "src_reference",
      segments: [[100, 105]],
      preview: true,
    });

    await user.click(screen.getByRole("button", { name: "Apply patch" }));

    expect(patchStream).toHaveBeenCalledWith("act_1", {
      field: "heartrate",
      from_source: "src_reference",
      segments: [[100, 105]],
      preview: false,
    });
    expect(await screen.findByText("Patched ✓")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Undo" })).toBeInTheDocument();
  });

  it("keeps showing Patched + Undo once the fix makes the segment stop being re-detected", async () => {
    // Regression test: a live browser run caught this - invalidateAfterWrite refetches
    // detectStreamFlags after a successful patch, and a *real* backend correctly stops
    // flagging a run it just fixed. If the rendered rows were taken directly from the query
    // result instead of a session-tracked applied-patches map, the segment (and its Undo
    // button) would silently vanish the instant the patch worked - exactly backwards, since
    // that's the one moment an athlete most plausibly wants Undo available. A static mock
    // response (as the other tests here use) can't catch this; this one changes what the mock
    // returns on the second call, the way a real backend would.
    const user = userEvent.setup();
    vi.mocked(detectStreamFlags)
      .mockResolvedValueOnce(flags())
      .mockResolvedValueOnce(flags({ segments: [] }));
    vi.mocked(patchStream).mockImplementation((_id, params) =>
      Promise.resolve(patchResult({ patch_id: params.preview ? null : "pat_1" })),
    );
    renderWithProviders(
      <StreamFlagsCard activityId="act_1" field="heartrate" referenceSourceId="src_reference" referenceLabel="Tickr" />,
    );
    await user.click(screen.getByRole("button", { name: "Detect issues" }));
    await screen.findByText("t=100–105 (6s)");
    await user.click(screen.getByRole("button", { name: "Patch from Tickr" }));
    await screen.findByText("copied 6, nulled 0");

    await user.click(screen.getByRole("button", { name: "Apply patch" }));

    await waitFor(() => expect(detectStreamFlags).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("Patched ✓")).toBeInTheDocument();
    expect(screen.getByText("t=100–105 (6s)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Undo" })).toBeInTheDocument();
    expect(screen.queryByText("No issues detected.")).not.toBeInTheDocument();
  });

  it("undoes an applied patch after confirmation", async () => {
    const user = userEvent.setup();
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.mocked(detectStreamFlags).mockResolvedValue(flags());
    vi.mocked(patchStream).mockImplementation((_id, params) =>
      Promise.resolve(patchResult({ patch_id: params.preview ? null : "pat_1" })),
    );
    vi.mocked(unpatchStream).mockResolvedValue({ patch_id: "pat_1", undone_at: "2026-11-24T00:00:00Z" });
    renderWithProviders(
      <StreamFlagsCard activityId="act_1" field="heartrate" referenceSourceId="src_reference" referenceLabel="Tickr" />,
    );
    await user.click(screen.getByRole("button", { name: "Detect issues" }));
    await screen.findByText("t=100–105 (6s)");
    await user.click(screen.getByRole("button", { name: "Patch from Tickr" }));
    await screen.findByText("copied 6, nulled 0");
    await user.click(screen.getByRole("button", { name: "Apply patch" }));
    await screen.findByText("Patched ✓");

    await user.click(screen.getByRole("button", { name: "Undo" }));

    expect(window.confirm).toHaveBeenCalledWith("Undo this patch? This restores the original values.");
    await waitFor(() => expect(unpatchStream).toHaveBeenCalledWith("act_1", "pat_1"));
    expect(await screen.findByRole("button", { name: "Patch from Tickr" })).toBeInTheDocument();
  });

  it("reports flagged segments up via onSegmentsChange", async () => {
    const user = userEvent.setup();
    const onSegmentsChange = vi.fn();
    vi.mocked(detectStreamFlags).mockResolvedValue(flags());
    renderWithProviders(
      <StreamFlagsCard
        activityId="act_1"
        field="heartrate"
        referenceSourceId="src_reference"
        referenceLabel="Tickr"
        onSegmentsChange={onSegmentsChange}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Detect issues" }));

    await waitFor(() => expect(onSegmentsChange).toHaveBeenCalledWith([[100, 105]]));
  });
});
