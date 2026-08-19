import { describe, expect, it } from "vitest";
import { withStallTimeout } from "./athletes";

async function* immediate<T>(values: T[]): AsyncGenerator<T> {
  for (const v of values) yield v;
}

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function collect<T>(gen: AsyncGenerator<T>): Promise<T[]> {
  const out: T[] = [];
  for await (const v of gen) out.push(v);
  return out;
}

describe("withStallTimeout", () => {
  it("passes through every value when the source keeps up", async () => {
    const result = await collect(withStallTimeout(immediate([1, 2, 3]), 200));
    expect(result).toEqual([1, 2, 3]);
  });

  it("throws once no value arrives within the timeout", async () => {
    async function* stalls(): AsyncGenerator<number> {
      yield 1;
      // Never resolves within the test's timeout - simulates a dead connection whose
      // reader.read() promise never settles (see the production incident this fixes).
      await new Promise<void>(() => {});
      yield 2;
    }

    await expect(collect(withStallTimeout(stalls(), 30))).rejects.toThrow(/connection lost/i);
  });

  it("still throws even after successfully yielding several earlier values", async () => {
    async function* slowsDownThenStalls(): AsyncGenerator<number> {
      yield 1;
      await delay(5);
      yield 2;
      await new Promise<void>(() => {});
    }

    const out: number[] = [];
    await expect(
      (async () => {
        for await (const v of withStallTimeout(slowsDownThenStalls(), 30)) out.push(v);
      })(),
    ).rejects.toThrow();
    expect(out).toEqual([1, 2]);
  });

  it("completes cleanly when the source finishes before ever stalling", async () => {
    async function* finishesQuickly(): AsyncGenerator<number> {
      yield 1;
      await delay(5);
    }
    await expect(collect(withStallTimeout(finishesQuickly(), 200))).resolves.toEqual([1]);
  });
});
