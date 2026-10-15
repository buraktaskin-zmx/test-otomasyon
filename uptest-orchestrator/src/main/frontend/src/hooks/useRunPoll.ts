import { useCallback, useEffect, useRef, useState } from "react";
import { getRun, startRun, stopRun } from "../api/endpoints";
import type { ApiResult } from "../api/client";
import type { Origin, RunDetail, RunStarted } from "../api/types";

const POLL_MS = 2000;

/*
  app.js:350-399 (run/poll), with its one real lifecycle bug fixed.

  BUG FIX: the original poll() did `if (!result.ok) { return; }` -- a single
  dropped response froze the timeline forever, with no retry and no visible
  error. Here a failed poll reschedules itself exactly like a successful
  RUNNING one, so a transient network blip self-heals instead of silently
  wedging the UI (the plan's "doğrulama" step 6 tests this explicitly: stop
  the orchestrator mid-run and confirm the UI does not freeze).

  Single current run, matching today's app.js (`state.runId` is a scalar,
  not a registry) -- representing several runs in flight at once is a
  documented future gap, not something this port's scope commits to.
*/
export function useRunPoll() {
  const [run, setRun] = useState<RunDetail | null>(null);
  const [starting, setStarting] = useState(false);
  const pollTimer = useRef<number | null>(null);
  const currentRunId = useRef<string | null>(null);

  const stopPolling = useCallback(() => {
    if (pollTimer.current !== null) {
      window.clearTimeout(pollTimer.current);
      pollTimer.current = null;
    }
  }, []);

  const poll = useCallback(() => {
    const runId = currentRunId.current;
    if (!runId) return;

    void getRun(runId).then((result) => {
      // A newer run superseded this one while the request was in flight.
      if (currentRunId.current !== runId) return;

      if (!result.ok) {
        pollTimer.current = window.setTimeout(poll, POLL_MS);
        return;
      }

      setRun(result.body);
      if (result.body.status === "RUNNING") {
        pollTimer.current = window.setTimeout(poll, POLL_MS);
      } else {
        stopPolling();
      }
    });
  }, [stopPolling]);

  useEffect(() => stopPolling, [stopPolling]);

  const attachAndPoll = useCallback(
    (runId: string) => {
      currentRunId.current = runId;
      setRun(null);
      stopPolling();
      poll();
    },
    [poll, stopPolling],
  );

  /** Starts a run and, on 202, immediately begins polling it. Returns the
   *  raw ApiResult so the caller can still branch on 422 (bare
   *  ValidationResult) / 503 (ErrorBody) for its own toast/panel update. */
  const start = useCallback(
    async (feature: string, origin: Origin): Promise<ApiResult<RunStarted>> => {
      setStarting(true);
      const result = await startRun(feature, origin);
      setStarting(false);
      if (result.ok) attachAndPoll(result.body.runId);
      return result;
    },
    [attachAndPoll],
  );

  const stop = useCallback(async () => {
    if (!currentRunId.current) return;
    await stopRun(currentRunId.current);
    // No optimistic update: CANCELED is only confirmed once the next poll
    // actually reports it -- stopping is cooperative, not immediate.
  }, []);

  return { run, starting, start, stop };
}
