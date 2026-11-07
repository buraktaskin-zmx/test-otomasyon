import { useCallback, useEffect, useRef, useState } from "react";
import { getRun, getScenario, startRun, stopRun } from "../api/endpoints";
import type { RunLogin, ValidationError } from "../api/types";

/** How many scenarios of one queue run at the same time; the rest wait. */
export const MAX_PARALLEL = 4;

const TICK_MS = 2000;

export type QueueStatus =
  | "queued"
  | "starting"
  | "running"
  | "stopping"
  | "passed"
  | "failed"
  | "stopped"
  | "refused";

export interface QueueItem {
  path: string;
  title: string;
  flow: string;
  /** What the run's intent is built from, as the single "Git" run sends it. */
  description: string;
  status: QueueStatus;
  runId?: string;
  executor?: string;
  passed?: number;
  total?: number;
  error?: string;
}

/** A row of the table, as much of it as a run needs. */
export interface QueueRow {
  path: string;
  title: string;
  flow: string;
  description: string;
}

const ACTIVE: ReadonlySet<QueueStatus> = new Set(["starting", "running", "stopping"]);

/*
  Several saved scenarios, run deterministically -- their stored Gherkin, not
  the AI walk -- at most MAX_PARALLEL at a time, the rest waiting here.

  The queue lives in this tab on purpose. The server never queues: an executor
  takes `simultaneouslyRun` runs and refuses the next with 409, which the
  orchestrator answers as 503 "All executors are busy". So that 503 is not a
  failure here: the item goes back to waiting and is tried on the next tick.
  Only when none of THIS queue's runs is in flight does it mean the executor
  is down or taken by someone else, and then it is reported.

  Kept beside useRunPoll rather than inside it: that hook follows exactly one
  run, and the editor's timeline depends on that.

  A page reload loses what was still waiting; a run already started goes on
  on the executor and shows up in Geçmiş.
*/
export function useRunQueue() {
  const [items, setItems] = useState<QueueItem[]>([]);
  const itemsRef = useRef<QueueItem[]>([]);
  const context = useRef<{ app: string; product: string; login?: RunLogin } | null>(null);
  const stopRequested = useRef(false);
  const timer = useRef<number | null>(null);

  const commit = useCallback((next: QueueItem[]) => {
    itemsRef.current = next;
    setItems(next);
  }, []);

  const patch = useCallback((path: string, change: Partial<QueueItem>) => {
    commit(itemsRef.current.map((item) => (item.path === path ? { ...item, ...change } : item)));
  }, [commit]);

  const startOne = useCallback(async (item: QueueItem) => {
    const where = context.current;
    if (!where) return;
    const detail = await getScenario(where.app, where.product, item.path);
    if (stopRequested.current) {
      patch(item.path, { status: "stopped" });
      return;
    }
    if (!detail.ok) {
      patch(item.path, { status: "refused", error: "Senaryo okunamadı: " + detail.error });
      return;
    }
    const started = await startRun(detail.body.feature,
      { app: where.app, flow: item.flow, intent: item.description, product: where.product },
      where.login);
    if (started.ok) {
      patch(item.path, {
        status: "running",
        runId: started.body.runId,
        executor: started.body.executor,
        total: started.body.stepCount,
        passed: 0,
      });
      // Started while Durdur was being pressed: stop it straight away.
      if (stopRequested.current) {
        patch(item.path, { status: "stopping" });
        void stopRun(started.body.runId);
      }
      return;
    }
    if (started.status === 503 && started.error.startsWith("All executors")) {
      // After Durdur nothing is pumped again, so "back to waiting" would wait for ever.
      if (stopRequested.current) {
        patch(item.path, { status: "stopped" });
        return;
      }
      const othersInFlight = itemsRef.current.some((other) =>
        other.path !== item.path && ACTIVE.has(other.status));
      patch(item.path, othersInFlight
        ? { status: "queued" }
        : { status: "failed", error: "Boş executor yok: kapalı ya da başka koşularla dolu" });
      return;
    }
    if (started.status === 422) {
      const first = (started.body as { errors?: ValidationError[] } | null)?.errors?.[0];
      patch(item.path, {
        status: "refused",
        error: first ? "Satır " + first.line + ": " + first.message : "Doğrulama reddetti",
      });
      return;
    }
    patch(item.path, { status: "failed", error: started.error });
  }, [patch]);

  /** Starts waiting items until MAX_PARALLEL are in flight. */
  const pump = useCallback(() => {
    if (stopRequested.current) return;
    let inFlight = itemsRef.current.filter((item) => ACTIVE.has(item.status)).length;
    for (const item of itemsRef.current) {
      if (inFlight >= MAX_PARALLEL) break;
      if (item.status !== "queued") continue;
      patch(item.path, { status: "starting", error: undefined });
      inFlight++;
      void startOne(item);
    }
  }, [patch, startOne]);

  const stopTicking = useCallback(() => {
    if (timer.current !== null) {
      window.clearInterval(timer.current);
      timer.current = null;
    }
  }, []);

  const tick = useCallback(async () => {
    const running = itemsRef.current.filter((item) =>
      (item.status === "running" || item.status === "stopping") && item.runId);
    await Promise.all(running.map(async (item) => {
      const result = await getRun(item.runId!);
      if (!result.ok) return; // a dropped poll is retried on the next tick
      const run = result.body;
      const steps = run.steps ?? [];
      const passed = steps.filter((step) => step.status === "PASSED").length;
      const total = steps.length || item.total;
      if (run.status === "RUNNING") {
        patch(item.path, { passed, total });
      } else if (run.status === "DONE") {
        patch(item.path, { status: "passed", passed, total });
      } else if (run.status === "CANCELED") {
        patch(item.path, { status: "stopped", passed, total });
      } else {
        const failedStep = steps.find((step) => step.status === "FAILED");
        patch(item.path, {
          status: "failed", passed, total,
          error: failedStep?.error ?? run.executorResult?.error?.message ?? undefined,
        });
      }
    }));
    pump();
    const busy = itemsRef.current.some((item) => item.status === "queued" || ACTIVE.has(item.status));
    if (!busy) stopTicking();
  }, [patch, pump, stopTicking]);

  useEffect(() => stopTicking, [stopTicking]);

  const run = useCallback((rows: QueueRow[], app: string, product: string, login?: RunLogin) => {
    if (rows.length === 0) return;
    stopRequested.current = false;
    context.current = { app, product, login };
    commit(rows.map((row) => ({ ...row, status: "queued" as const })));
    stopTicking();
    timer.current = window.setInterval(() => void tick(), TICK_MS);
    pump();
  }, [commit, pump, stopTicking, tick]);

  /** Waiting items are dropped; running ones are asked to stop (cooperative). */
  const stop = useCallback(() => {
    stopRequested.current = true;
    commit(itemsRef.current.map((item) => {
      if (item.status === "queued") return { ...item, status: "stopped" as const };
      if (item.status === "running" && item.runId) {
        void stopRun(item.runId);
        return { ...item, status: "stopping" as const };
      }
      return item;
    }));
  }, [commit]);

  const clear = useCallback(() => {
    if (itemsRef.current.some((item) => item.status === "queued" || ACTIVE.has(item.status))) return;
    commit([]);
  }, [commit]);

  const busy = items.some((item) => item.status === "queued" || ACTIVE.has(item.status));
  return { items, busy, run, stop, clear };
}
