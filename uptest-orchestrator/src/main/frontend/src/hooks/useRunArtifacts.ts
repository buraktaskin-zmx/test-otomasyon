import { useEffect, useState } from "react";
import { getRunArtifacts } from "../api/endpoints";
import type { Artifact, RunStatus } from "../api/types";

/*
  app.js:440-441,465-481 fetched artifacts once a run left RUNNING and then
  APPENDED the links into the DOM (`el.runFooter.innerHTML += ...`) -- call
  it twice (e.g. a second status transition) and the links duplicate. That
  bug class cannot recur here by construction: `artifacts` is state derived
  from a single effect keyed on (runId, status), and rendering from an
  array is idempotent no matter how many times the effect re-runs.
*/
export function useRunArtifacts(runId: string | null, status: RunStatus | null): Artifact[] | null {
  const [artifacts, setArtifacts] = useState<Artifact[] | null>(null);

  useEffect(() => {
    setArtifacts(null);
    if (!runId || status == null || status === "RUNNING") return;

    let cancelled = false;
    void getRunArtifacts(runId).then((result) => {
      if (cancelled) return;
      if (result.ok) setArtifacts(result.body);
    });
    return () => {
      cancelled = true;
    };
  }, [runId, status]);

  return artifacts;
}
