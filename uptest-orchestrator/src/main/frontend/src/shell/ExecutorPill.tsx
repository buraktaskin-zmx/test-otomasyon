import { useCallback, useEffect, useState } from "react";
import { getExecutors } from "../api/endpoints";
import { useInterval } from "../hooks/useInterval";
import type { ExecutorNode } from "../api/types";

const REFRESH_MS = 15000;

type Health = { up: number; total: number } | null;

/*
  Owns its own clock. The header is the one place in the studio that is always
  on screen, so "are any executors alive" is answered here and nowhere else.

  A failed poll leaves the last known counts on screen rather than blanking
  them: a dropped request is not evidence that the executors went away, and
  flickering to "0 / 0" every time the proxy hiccups would train people to
  ignore the light that matters.
*/
export function ExecutorPill({ onRefresh }: { onRefresh?: () => void }) {
  const [health, setHealth] = useState<Health>(null);

  const load = useCallback(async () => {
    const result = await getExecutors();
    if (!result.ok) return;
    const nodes: ExecutorNode[] = result.body;
    setHealth({ up: nodes.filter((n) => n.reachable).length, total: nodes.length });
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useInterval(() => void load(), REFRESH_MS);

  const dot =
    health === null ? "dot-unknown" : health.up === 0 ? "dot-down" : "dot-up";
  const label =
    health === null ? "bağlanıyor\u2026" : health.up + " / " + health.total + " executor";

  return (
    <button
      id="executorStatus"
      className="node-pill"
      type="button"
      title="Yenilemek için tıklayın"
      onClick={() => {
        void load();
        onRefresh?.();
      }}
    >
      <span className={"dot " + dot} />
      <span id="executorLabel">{label}</span>
    </button>
  );
}
