import { afterEach, describe, expect, it, vi } from "vitest";
import { recomputeBestEffortsStream } from "./athletes";

async function collect<T>(gen: AsyncGenerator<T>): Promise<T[]> {
  const out: T[] = [];
  for await (const v of gen) out.push(v);
  return out;
}

function sseResponse(blocks: string[]): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const block of blocks) controller.enqueue(new TextEncoder().encode(block));
      controller.close();
    },
  });
  return new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

function jsonResponse(data: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status: 200,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

describe("recomputeBestEffortsStream", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("parses progress/done events from a Java-style SSE stream", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      sseResponse([
        'event: progress\ndata: {"current":1,"total":2}\n\n',
        'event: progress\ndata: {"current":2,"total":2}\n\n',
        'event: done\ndata: {"processed":2}\n\n',
      ]),
    );
    vi.stubGlobal("fetch", fetchMock);

    const events = await collect(recomputeBestEffortsStream("usr_1"));

    expect(events).toEqual([
      { type: "progress", current: 1, total: 2 },
      { type: "progress", current: 2, total: 2 },
      { type: "done", processed: 2 },
    ]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("falls back to polling a Django-style job when the response isn't SSE", async () => {
    const fetchMock = vi
      .fn()
      // Initial POST - Django returns the freshly-created job, not a stream.
      .mockResolvedValueOnce(
        jsonResponse(
          {
            id: "ber_1", object: "best_effort_recompute_job", status: "queued",
            kind: "", total_items: null, processed_items: 0,
            error_message: "", created_at: "2026-01-01T00:00:00Z", completed_at: null,
          },
          { "Retry-After": "0" },
        ),
      )
      // First poll - still processing.
      .mockResolvedValueOnce(
        jsonResponse(
          {
            id: "ber_1", object: "best_effort_recompute_job", status: "processing",
            kind: "", total_items: 10, processed_items: 4,
            error_message: "", created_at: "2026-01-01T00:00:00Z", completed_at: null,
          },
          { "Retry-After": "0" },
        ),
      )
      // Second poll - done.
      .mockResolvedValueOnce(
        jsonResponse({
          id: "ber_1", object: "best_effort_recompute_job", status: "ready",
          kind: "", total_items: 10, processed_items: 10,
          error_message: "", created_at: "2026-01-01T00:00:00Z", completed_at: "2026-01-01T00:01:00Z",
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const events = await collect(recomputeBestEffortsStream("usr_1"));

    expect(events).toEqual([
      { type: "progress", current: 0, total: 0 },
      { type: "progress", current: 4, total: 10 },
      { type: "done", processed: 10 },
    ]);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    // Polls hit the job detail URL, not the POST endpoint again.
    expect(fetchMock.mock.calls[1][0]).toContain("/best-efforts/recompute/ber_1");
    expect(fetchMock.mock.calls[2][0]).toContain("/best-efforts/recompute/ber_1");
  });

  it("throws when a Django-style job ends in failed", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse(
          {
            id: "ber_2", object: "best_effort_recompute_job", status: "queued",
            kind: "", total_items: null, processed_items: 0,
            error_message: "", created_at: "2026-01-01T00:00:00Z", completed_at: null,
          },
          { "Retry-After": "0" },
        ),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          id: "ber_2", object: "best_effort_recompute_job", status: "failed",
          kind: "", total_items: 5, processed_items: 1,
          error_message: "boom", created_at: "2026-01-01T00:00:00Z", completed_at: "2026-01-01T00:00:30Z",
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(collect(recomputeBestEffortsStream("usr_1"))).rejects.toThrow(/boom/);
  });
});
