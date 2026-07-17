import { useEffect, useState } from "react";

type PollResult<T> = { data: T; retryAfterSeconds: number | null };

/** Polls `poll(id)` on an interval driven by the response's own Retry-After header (falling
 * back to 2s when absent) until `isTerminal` says to stop. Starts from `initial` so the
 * caller doesn't need a separate "first fetch" path before polling kicks in. */
export function usePolling<T>(
  initial: PollResult<T>,
  poll: (id: string) => Promise<PollResult<T>>,
  getId: (data: T) => string,
  isTerminal: (data: T) => boolean,
): T {
  const [result, setResult] = useState(initial);

  useEffect(() => {
    if (isTerminal(result.data)) {
      return;
    }
    let cancelled = false;
    const delayMs = (result.retryAfterSeconds ?? 2) * 1000;
    const timer = setTimeout(async () => {
      try {
        const next = await poll(getId(result.data));
        if (!cancelled) {
          setResult(next);
        }
      } catch {
        // A transient failure (network hiccup, backend saturated mid-batch) must not
        // end polling: re-set the same result under a fresh identity so the effect
        // re-runs and schedules the next attempt.
        if (!cancelled) {
          setResult({ ...result });
        }
      }
    }, delayMs);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
    // Only re-run when the polled data actually changes (a new tick) - poll/getId/isTerminal
    // are stable for the component's lifetime.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result]);

  return result.data;
}
